package org.docscan.research;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.*;
import com.github.unidbg.virtualmodule.android.AndroidModule;
import com.github.unidbg.virtualmodule.android.JniGraphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/** Independent synthetic-input probes. Does not initialize or alter SDK authentication. */
public final class FilterProbe {
    public static void main(String[] args) throws Exception {
        File libraries = new File(args[0]);
        File output = new File(args[1]);
        String kind = args[2];
        int mode = Integer.parseInt(args[3]);
        int width = args.length > 4 ? Integer.parseInt(args[4]) : 96;
        int height = args.length > 5 ? Integer.parseInt(args[5]) : 128;
        if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("Output unavailable");
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int v = y % 12 < 3 && x > width / 10 && x < width * 9 / 10 ? 25 : 130 + x * 110 / width;
            image.setRGB(x, y, 0xff000000 | v << 16 | Math.min(255, v + 8) << 8 | Math.max(0, v - 12));
        }
        ImageIO.write(image, "PNG", new File(output, "source.png"));
        try (AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new Unicorn2Factory(true)).setProcessName("org.docscan.filterprobe").build()) {
            emulator.getMemory().setLibraryResolver(new AndroidResolver(23));
            VM vm = emulator.createDalvikVM();
            new AndroidModule(emulator, vm).register(emulator.getMemory());
            new JniGraphics(emulator, vm).register(emulator.getMemory());
            for (String name : new String[]{"opencv", "ncnn", "MNN", "CVNN", "magicenhancer"})
                vm.loadLibrary(new File(libraries, "lib" + name + ".so"), true);
            DvmClass magic = vm.resolveClass("com/intsig/nativelib/MagicEnhancer");
            NativeProbe.CaptureBitmap bitmap = new NativeProbe.CaptureBitmap(vm, image);
            long start = System.nanoTime();
            int result;
            switch (kind) {
                case "new": {
                    int session = magic.callStaticJniMethodInt(emulator, "initDeshadowModelNew(I)I", 1);
                    System.out.println("SESSION=" + session);
                    if (session < 0) throw new IllegalStateException("Model unavailable");
                    try { result = magic.callStaticJniMethodInt(emulator, "deshadowBitmapNew(Landroid/graphics/Bitmap;II)I", bitmap, mode, session); }
                    finally { magic.callStaticJniMethod(emulator, "releaseDeshadowModelNew(I)V", session); }
                    break;
                }
                case "legacy":
                    result = magic.callStaticJniMethodInt(emulator, "deshadowBitmap(Landroid/graphics/Bitmap;I)I", bitmap, mode);
                    break;
                case "silver":
                    result = magic.callStaticJniMethodInt(emulator, "ImageSilverBulletBitmap(Landroid/graphics/Bitmap;III)I", bitmap, 1, 1, 1);
                    break;
                case "invert": {
                    vm.loadLibrary(new File(libraries, "libimageprocessor.so"), true);
                    DvmClass processor = vm.resolveClass("com/intsig/nativelib/ImageProcessor");
                    result = processor.callStaticJniMethodInt(emulator, "ReflectBinaryColorShiftBitmap(Landroid/graphics/Bitmap;I)I", bitmap, mode);
                    break;
                }
                default: throw new IllegalArgumentException("Unknown probe");
            }
            if (bitmap.processed == null) throw new IllegalStateException("No pixels returned");
            byte[] pixels = bitmap.processed;
            long changed = 0, badAlpha = 0;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int at = (y * width + x) * 4;
                int color = (pixels[at + 3] & 255) << 24 | (pixels[at] & 255) << 16
                        | (pixels[at + 1] & 255) << 8 | pixels[at + 2] & 255;
                if (color != image.getRGB(x, y)) changed++;
                if ((pixels[at + 3] & 255) != 255) badAlpha++;
                image.setRGB(x, y, color);
            }
            ImageIO.write(image, "PNG", new File(output, kind + "-" + mode + ".png"));
            System.out.printf("RESULT kind=%s mode=%d status=%d width=%d height=%d changed=%d alphaErrors=%d elapsed_ms=%d rgba_sha256=%s%n",
                    kind, mode, result, width, height, changed, badAlpha, (System.nanoTime() - start) / 1_000_000,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pixels)));
        }
    }
}
