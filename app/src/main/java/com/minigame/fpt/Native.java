package com.minigame.fpt;

import android.view.Surface;

/** Cầu nối tới bridge.cpp */
public final class Native {
    static {
        System.loadLibrary("minibridge");
    }

    private Native() {}

    /** Trả về null nếu thành công, ngược lại là thông báo lỗi. */
    public static native String nativeLoad(String corePath, String gamePath, String systemDir, String saveDir);

    /** Chạy 1 khung hình, trả về số mẫu âm thanh (short) đã ghi vào mảng, hoặc -1 nếu lỗi. */
    public static native int nativeRun(short[] audio);

    public static native void nativeUnload();

    public static native void nativeSaveSram();

    public static native void nativeSetInput(int port, int mask);

    public static native void nativeSetSurface(Surface surface);

    public static native void nativeRedraw();

    public static native double nativeGetSampleRate();

    public static native double nativeGetFps();

    public static native float nativeGetAspect();
}
