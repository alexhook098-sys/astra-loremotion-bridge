package com.quramsoft.agifEncoder;

import android.graphics.Bitmap;

/** JNI bridge matching Samsung's Quram GIF encoder API. The native library is provided by compatible Samsung firmware. */
public final class QuramAGIFEncoder {
    private long mHandle = 0L;
    static { System.loadLibrary("agifencoder.quram"); }
    public QuramAGIFEncoder() { nativeInitHandle(this); }
    public boolean start(String path) { return nativeStart(mHandle, path); }
    public boolean addFrameTP(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return false;
        setSize(bitmap.getWidth(), bitmap.getHeight());
        return nativeAddFrameTP(mHandle, bitmap);
    }
    public boolean finish() { return nativeFinish(mHandle); }
    public void setDelay(int delayMs) { nativeSetDelay(mHandle, delayMs); }
    public void setRepeat(int repeat) { nativeSetRepeat(mHandle, repeat); }
    public void setSize(int width, int height) { nativeSetSize(mHandle, width, height); }
    public void setDispose(int dispose) { nativeSetDispose(mHandle, dispose); }
    public void setMaxTaskTP(int count) { nativeSetMaxTaskTP(mHandle, count); }
    private native void nativeInitHandle(QuramAGIFEncoder owner);
    private native boolean nativeStart(long handle, String path);
    private native boolean nativeAddFrameTP(long handle, Bitmap bitmap);
    private native boolean nativeFinish(long handle);
    private native void nativeSetDelay(long handle, int delayMs);
    private native void nativeSetRepeat(long handle, int repeat);
    private native void nativeSetSize(long handle, int width, int height);
    private native void nativeSetDispose(long handle, int dispose);
    private native void nativeSetMaxTaskTP(long handle, int count);
}