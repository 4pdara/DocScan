package com.intsig.nativelib;

import android.graphics.Bitmap;

/** Minimal handwritten JNI boundary; signatures checked against the reference DEX. */
public final class ImageProcessor {
    private ImageProcessor() { }
    public static native int ReflectBinaryColorShiftBitmap(Bitmap bitmap, int mode);
    public static native int ReflectBinaryColorShiftBitmapBG(Bitmap bitmap, Bitmap background, int mode);
}

