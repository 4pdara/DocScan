package com.intsig.nativelib;

import android.graphics.Bitmap;

/** Minimal handwritten JNI boundary for the independently verified inversion call. */
public final class ImageProcessor {
    private ImageProcessor() { }
    public static native int ReflectBinaryColorShiftBitmap(Bitmap bitmap, int mode);
}
