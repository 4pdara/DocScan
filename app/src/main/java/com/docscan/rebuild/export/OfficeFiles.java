package com.docscan.rebuild.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Excel (.xlsx) and PowerPoint (.pptx) files written directly as Office Open XML (ECMA-376), with no library.
 * Plain Java with no Android types, so the same class is checked on a computer by opening the files with
 * openpyxl, python-pptx and LibreOffice.
 */
public final class OfficeFiles {
    private OfficeFiles() {}

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships";

    static String escape(String text) {
        StringBuilder s = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': s.append("&amp;"); break;
                case '<': s.append("&lt;"); break;
                case '>': s.append("&gt;"); break;
                case '"': s.append("&quot;"); break;
                default:
                    // XML 1.0 forbids most control characters; tabs and new lines are kept.
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
                    s.append(c);
            }
        }
        return s.toString();
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        put(zip, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }

    /** Column letters for a zero-based index: A, B, ..., Z, AA, ... */
    static String column(int index) {
        StringBuilder s = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) s.insert(0, (char) ('A' + (n - 1) % 26));
        return s.toString();
    }

    /** Sheet names: at most 31 characters and none of : \ / ? * [ ]. */
    static String sheetName(String name) {
        String clean = name.replaceAll("[:\\\\/?*\\[\\]]", " ").trim();
        if (clean.isEmpty()) clean = "Sheet";
        return clean.length() > 31 ? clean.substring(0, 31) : clean;
    }

    /** One worksheet per entry; every cell is text. [rightToLeft] shows the sheets right to left (Hebrew). */
    public static void xlsx(List<String> names, List<List<List<String>>> sheets, boolean rightToLeft, OutputStream out) throws IOException {
        if (names.size() != sheets.size() || sheets.isEmpty()) throw new IllegalArgumentException("Sheets");
        ZipOutputStream zip = new ZipOutputStream(out);
        StringBuilder types = new StringBuilder(XML)
            .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= sheets.size(); i++)
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        put(zip, "[Content_Types].xml", types.append("</Types>").toString());
        put(zip, "_rels/.rels", XML + "<Relationships xmlns=\"" + PKG_REL + "\"><Relationship Id=\"rId1\" Type=\"" + REL + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        StringBuilder workbook = new StringBuilder(XML).append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"").append(REL).append("\"><sheets>");
        StringBuilder rels = new StringBuilder(XML).append("<Relationships xmlns=\"").append(PKG_REL).append("\">");
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int i = 1; i <= sheets.size(); i++) {
            String name = sheetName(names.get(i - 1)); String unique = name; int n = 2;
            while (!used.add(unique.toLowerCase(java.util.Locale.ROOT))) { String suffix = " (" + n++ + ")"; unique = name.substring(0, Math.min(name.length(), 31 - suffix.length())) + suffix; }
            workbook.append("<sheet name=\"").append(escape(unique)).append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(REL).append("/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        rels.append("<Relationship Id=\"rId").append(sheets.size() + 1).append("\" Type=\"").append(REL).append("/styles\" Target=\"styles.xml\"/>");
        put(zip, "xl/workbook.xml", workbook.append("</sheets></workbook>").toString());
        put(zip, "xl/_rels/workbook.xml.rels", rels.append("</Relationships>").toString());
        put(zip, "xl/styles.xml", XML + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Arial\"/></font></fonts>"
            + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
        for (int i = 1; i <= sheets.size(); i++) {
            List<List<String>> rows = sheets.get(i - 1);
            int widest = 1; for (List<String> row : rows) widest = Math.max(widest, row.size());
            StringBuilder sheet = new StringBuilder(XML).append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\"").append(rightToLeft ? " rightToLeft=\"1\"" : "").append("/></sheetViews>")
                .append("<cols><col min=\"1\" max=\"").append(widest).append("\" width=\"28\" customWidth=\"1\"/></cols><sheetData>");
            for (int r = 0; r < rows.size(); r++) {
                sheet.append("<row r=\"").append(r + 1).append("\">");
                List<String> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    String value = row.get(c); if (value == null || value.isEmpty()) continue;
                    sheet.append("<c r=\"").append(column(c)).append(r + 1).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(value.length() > 32767 ? value.substring(0, 32767) : value)).append("</t></is></c>");
                }
                sheet.append("</row>");
            }
            put(zip, "xl/worksheets/sheet" + i + ".xml", sheet.append("</sheetData></worksheet>").toString());
        }
        zip.finish(); out.flush();
    }

    /** A slide: one page picture (JPEG) of the given pixel size. */
    public static final class Slide {
        final byte[] jpeg; final int width, height;
        public Slide(byte[] jpeg, int width, int height) { this.jpeg = jpeg; this.width = width; this.height = height; }
    }
    /** Slides are asked for one at a time, so a long document never holds more than one page picture in memory. */
    public interface Slides { int count(); Slide slide(int index) throws IOException; }

    private static final long A4_WIDTH = 7560000L, A4_HEIGHT = 10692000L; // EMU, 210 x 297 mm
    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    /** Each page on its own portrait A4 slide, the picture as large as fits with a small margin. */
    public static void pptx(Slides slides, OutputStream out) throws IOException {
        int count = slides.count(); if (count == 0) throw new IllegalArgumentException("No slides");
        ZipOutputStream zip = new ZipOutputStream(out);
        StringBuilder types = new StringBuilder(XML).append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            .append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
            .append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
            .append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
            .append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
            .append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>");
        for (int i = 1; i <= count; i++) types.append("<Override PartName=\"/ppt/slides/slide").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
        put(zip, "[Content_Types].xml", types.append("</Types>").toString());
        put(zip, "_rels/.rels", XML + "<Relationships xmlns=\"" + PKG_REL + "\"><Relationship Id=\"rId1\" Type=\"" + REL + "/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>");
        StringBuilder presentation = new StringBuilder(XML).append("<p:presentation xmlns:a=\"").append(A).append("\" xmlns:r=\"").append(REL).append("\" xmlns:p=\"").append(P).append("\">")
            .append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>");
        StringBuilder rels = new StringBuilder(XML).append("<Relationships xmlns=\"").append(PKG_REL).append("\">")
            .append("<Relationship Id=\"rId1\" Type=\"").append(REL).append("/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>");
        for (int i = 1; i <= count; i++) {
            presentation.append("<p:sldId id=\"").append(255 + i).append("\" r:id=\"rId").append(i + 1).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"").append(REL).append("/slide\" Target=\"slides/slide").append(i).append(".xml\"/>");
        }
        rels.append("<Relationship Id=\"rId").append(count + 2).append("\" Type=\"").append(REL).append("/theme\" Target=\"theme/theme1.xml\"/>");
        presentation.append("</p:sldIdLst><p:sldSz cx=\"").append(A4_WIDTH).append("\" cy=\"").append(A4_HEIGHT).append("\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>");
        put(zip, "ppt/presentation.xml", presentation.toString());
        put(zip, "ppt/_rels/presentation.xml.rels", rels.append("</Relationships>").toString());
        String emptyTree = "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree>";
        String ns = " xmlns:a=\"" + A + "\" xmlns:r=\"" + REL + "\" xmlns:p=\"" + P + "\"";
        put(zip, "ppt/slideMasters/slideMaster1.xml", XML + "<p:sldMaster" + ns + "><p:cSld>" + emptyTree + "</p:cSld>"
            + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
            + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>");
        put(zip, "ppt/slideMasters/_rels/slideMaster1.xml.rels", XML + "<Relationships xmlns=\"" + PKG_REL + "\">"
            + "<Relationship Id=\"rId1\" Type=\"" + REL + "/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"" + REL + "/theme\" Target=\"../theme/theme1.xml\"/></Relationships>");
        put(zip, "ppt/slideLayouts/slideLayout1.xml", XML + "<p:sldLayout" + ns + " type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\">" + emptyTree
            + "</p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>");
        put(zip, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", XML + "<Relationships xmlns=\"" + PKG_REL + "\">"
            + "<Relationship Id=\"rId1\" Type=\"" + REL + "/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>");
        put(zip, "ppt/theme/theme1.xml", theme());
        for (int i = 1; i <= count; i++) {
            Slide slide = slides.slide(i - 1);
            long margin = 180000L; // 5 mm
            double scale = Math.min((A4_WIDTH - 2 * margin) / (double) slide.width, (A4_HEIGHT - 2 * margin) / (double) slide.height);
            long cx = Math.round(slide.width * scale), cy = Math.round(slide.height * scale);
            long x = (A4_WIDTH - cx) / 2, y = (A4_HEIGHT - cy) / 2;
            put(zip, "ppt/media/image" + i + ".jpeg", slide.jpeg);
            put(zip, "ppt/slides/slide" + i + ".xml", XML + "<p:sld" + ns + "><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
                + "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Page " + i + "\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>"
                + "<p:blipFill><a:blip r:embed=\"rId2\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>"
                + "<p:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"
                + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>");
            put(zip, "ppt/slides/_rels/slide" + i + ".xml.rels", XML + "<Relationships xmlns=\"" + PKG_REL + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + REL + "/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"" + REL + "/image\" Target=\"../media/image" + i + ".jpeg\"/></Relationships>");
        }
        zip.finish(); out.flush();
    }

    /** The smallest complete theme PowerPoint accepts: colours, fonts and three of each format style. */
    private static String theme() {
        StringBuilder t = new StringBuilder(XML).append("<a:theme xmlns:a=\"").append(A).append("\" name=\"DocScan\"><a:themeElements>")
            .append("<a:clrScheme name=\"DocScan\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1>")
            .append("<a:dk2><a:srgbClr val=\"1F2A2A\"/></a:dk2><a:lt2><a:srgbClr val=\"EEF2F2\"/></a:lt2>");
        String[] accents = {"0E8C7F", "4480E8", "FF715D", "F2B33D", "6B5BD6", "2E9E52"};
        for (int i = 0; i < 6; i++) t.append("<a:accent").append(i + 1).append("><a:srgbClr val=\"").append(accents[i]).append("\"/></a:accent").append(i + 1).append(">");
        t.append("<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>")
            .append("<a:fontScheme name=\"DocScan\"><a:majorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"Arial\"/></a:majorFont>")
            .append("<a:minorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"Arial\"/></a:minorFont></a:fontScheme>")
            .append("<a:fmtScheme name=\"DocScan\"><a:fillStyleLst>");
        for (int i = 0; i < 3; i++) t.append("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>");
        t.append("</a:fillStyleLst><a:lnStyleLst>");
        for (int w : new int[]{6350, 12700, 19050}) t.append("<a:ln w=\"").append(w).append("\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>");
        t.append("</a:lnStyleLst><a:effectStyleLst>");
        for (int i = 0; i < 3; i++) t.append("<a:effectStyle><a:effectLst/></a:effectStyle>");
        t.append("</a:effectStyleLst><a:bgFillStyleLst>");
        for (int i = 0; i < 3; i++) t.append("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>");
        return t.append("</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>").toString();
    }
}
