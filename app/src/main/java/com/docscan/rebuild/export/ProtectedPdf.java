package com.docscan.rebuild.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A PDF of JPEG pages that opens only with a password: the standard security handler, revision 6 (AES-256,
 * ISO 32000-2 section 7.6.4). Every stream is encrypted with the file key; the owner password is random, so
 * nobody can lift the protection without the password. Plain Java with no Android types, so the same class is
 * checked on a computer against qpdf.
 */
public final class ProtectedPdf {
    /** One page: JPEG bytes, the image's pixel size and the page size in points. */
    public static final class Page {
        final byte[] jpeg; final int pixelWidth, pixelHeight; final double width, height;
        public Page(byte[] jpeg, int pixelWidth, int pixelHeight, double width, double height) {
            this.jpeg = jpeg; this.pixelWidth = pixelWidth; this.pixelHeight = pixelHeight; this.width = width; this.height = height;
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final byte[] fileKey = new byte[32];
    private final List<Integer> offsets = new ArrayList<>();
    private long position;

    private ProtectedPdf() { random.nextBytes(fileKey); }

    /** Pages are asked for one at a time, in order, so a long document never holds more than one page in memory. */
    public interface Pages { int count(); Page page(int index) throws IOException; }

    public static void write(final List<Page> pages, String password, OutputStream out) throws IOException, GeneralSecurityException {
        write(new Pages() {
            @Override public int count() { return pages.size(); }
            @Override public Page page(int index) { return pages.get(index); }
        }, password, out);
    }

    public static void write(Pages pages, String password, OutputStream out) throws IOException, GeneralSecurityException {
        if (pages.count() == 0) throw new IllegalArgumentException("No pages");
        if (password.isEmpty()) throw new IllegalArgumentException("Empty password");
        new ProtectedPdf().document(pages, password, out);
    }

    /** UTF-8 of the NFKC-normalised password, at most 127 bytes (the SASLprep step of the standard). */
    static byte[] passwordBytes(String password) {
        byte[] bytes = Normalizer.normalize(password, Normalizer.Form.NFKC).getBytes(StandardCharsets.UTF_8);
        return bytes.length > 127 ? Arrays.copyOf(bytes, 127) : bytes;
    }

    /** Algorithm 2.B: the revision 6 hash. */
    static byte[] hash(byte[] password, byte[] salt, byte[] userKey) throws GeneralSecurityException {
        byte[] k = MessageDigest.getInstance("SHA-256").digest(concat(password, salt, userKey));
        int round = 0; byte[] e;
        while (true) {
            byte[] single = concat(password, k, userKey);
            byte[] k1 = new byte[single.length * 64];
            for (int i = 0; i < 64; i++) System.arraycopy(single, 0, k1, i * single.length, single.length);
            Cipher aes = Cipher.getInstance("AES/CBC/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(k, 16), "AES"), new IvParameterSpec(Arrays.copyOfRange(k, 16, 32)));
            e = aes.doFinal(k1);
            int sum = 0; for (int i = 0; i < 16; i++) sum += e[i] & 0xFF;
            String digest = sum % 3 == 0 ? "SHA-256" : sum % 3 == 1 ? "SHA-384" : "SHA-512";
            k = MessageDigest.getInstance(digest).digest(e);
            round++;
            if (round >= 64 && (e[e.length - 1] & 0xFF) <= round - 32) break;
        }
        return Arrays.copyOf(k, 32);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0; for (byte[] p : parts) length += p.length;
        byte[] all = new byte[length]; int at = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, all, at, p.length); at += p.length; }
        return all;
    }

    private byte[] randomBytes(int n) { byte[] b = new byte[n]; random.nextBytes(b); return b; }

    private static byte[] aesNoIv(byte[] key, byte[] data) throws GeneralSecurityException {
        Cipher aes = Cipher.getInstance("AES/CBC/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        return aes.doFinal(data);
    }

    /** A stream or string under AESV3: a random 16-byte IV, then AES-256-CBC with PKCS#7 padding. */
    private byte[] seal(byte[] data) throws GeneralSecurityException {
        byte[] iv = randomBytes(16);
        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(fileKey, "AES"), new IvParameterSpec(iv));
        return concat(iv, aes.doFinal(data));
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2 + 2).append('<');
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        return s.append('>').toString();
    }

    private static String number(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        s = s.replaceAll("0+$", ""); return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private void emit(OutputStream out, byte[] bytes) throws IOException { out.write(bytes); position += bytes.length; }
    private void emit(OutputStream out, String text) throws IOException { emit(out, text.getBytes(StandardCharsets.ISO_8859_1)); }
    private void begin(OutputStream out, int id) throws IOException {
        while (offsets.size() < id) offsets.add(0);
        offsets.set(id - 1, (int) position);
        emit(out, id + " 0 obj\n");
    }
    private void stream(OutputStream out, int id, String dictionary, byte[] plain) throws IOException, GeneralSecurityException {
        byte[] sealed = seal(plain);
        begin(out, id);
        emit(out, "<< " + dictionary + " /Length " + sealed.length + " >>\nstream\n");
        emit(out, sealed);
        emit(out, "\nendstream\nendobj\n");
    }

    private void document(Pages pages, String password, OutputStream out) throws IOException, GeneralSecurityException {
        byte[] user = passwordBytes(password);
        byte[] owner = randomBytes(32);
        // User entries: validation hash, then the file key wrapped by a key derived from the password.
        byte[] userValidation = randomBytes(8), userKeySalt = randomBytes(8);
        byte[] u = concat(hash(user, userValidation, new byte[0]), userValidation, userKeySalt);
        byte[] ue = aesNoIv(hash(user, userKeySalt, new byte[0]), fileKey);
        byte[] ownerValidation = randomBytes(8), ownerKeySalt = randomBytes(8);
        byte[] o = concat(hash(owner, ownerValidation, u), ownerValidation, ownerKeySalt);
        byte[] oe = aesNoIv(hash(owner, ownerKeySalt, u), fileKey);
        int permissions = -4; // everything allowed once the document is open
        byte[] perms = new byte[16];
        for (int i = 0; i < 4; i++) perms[i] = (byte) (permissions >> (8 * i));
        for (int i = 4; i < 8; i++) perms[i] = (byte) 0xFF;
        perms[8] = 'T'; perms[9] = 'a'; perms[10] = 'd'; perms[11] = 'b';
        System.arraycopy(randomBytes(4), 0, perms, 12, 4);
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(fileKey, "AES"));
        perms = ecb.doFinal(perms);

        emit(out, "%PDF-1.7\n%âãÏÓ\n");
        int pageCount = pages.count();
        // 1 catalog, 2 page tree, 3 encryption; then page, content and image for each page.
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) kids.append(4 + i * 3).append(" 0 R ");
        begin(out, 1); emit(out, "<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        begin(out, 2); emit(out, "<< /Type /Pages /Kids [ " + kids + "] /Count " + pageCount + " >>\nendobj\n");
        begin(out, 3);
        emit(out, "<< /Filter /Standard /V 5 /R 6 /Length 256 /CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV3 /Length 32 >> >> /StmF /StdCF /StrF /StdCF"
            + " /O " + hex(o) + " /U " + hex(u) + " /OE " + hex(oe) + " /UE " + hex(ue) + " /Perms " + hex(perms)
            + " /P " + permissions + " /EncryptMetadata true >>\nendobj\n");
        for (int i = 0; i < pageCount; i++) {
            Page page = pages.page(i); int id = 4 + i * 3;
            String w = number(page.width), h = number(page.height);
            begin(out, id);
            emit(out, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + w + " " + h + "] /Resources << /XObject << /Im0 " + (id + 2) + " 0 R >> >> /Contents " + (id + 1) + " 0 R >>\nendobj\n");
            stream(out, id + 1, "", ("q " + w + " 0 0 " + h + " 0 0 cm /Im0 Do Q").getBytes(StandardCharsets.ISO_8859_1));
            stream(out, id + 2, "/Type /XObject /Subtype /Image /Width " + page.pixelWidth + " /Height " + page.pixelHeight
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", page.jpeg);
        }
        long xref = position;
        int count = offsets.size() + 1;
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        table.write(("xref\n0 " + count + "\n0000000000 65535 f \n").getBytes(StandardCharsets.ISO_8859_1));
        for (int offset : offsets) table.write(String.format(Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.ISO_8859_1));
        emit(out, table.toByteArray());
        String id = hex(randomBytes(16));
        emit(out, "trailer\n<< /Size " + count + " /Root 1 0 R /Encrypt 3 0 R /ID [" + id + " " + id + "] >>\nstartxref\n" + xref + "\n%%EOF\n");
        out.flush();
    }
}
