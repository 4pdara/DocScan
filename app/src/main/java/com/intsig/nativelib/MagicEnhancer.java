package com.intsig.nativelib;

import android.graphics.Bitmap;

/** Minimal ABI declarations for the separately supplied research engine. */
public final class MagicEnhancer {
    private MagicEnhancer() {}
    public static native String GetVersion();
    public static native int initDeshadowModelNew(int threads);
    public static native int deshadowBitmapNew(Bitmap bitmap, int mode, int session);
    public static native void releaseDeshadowModelNew(int session);
}
