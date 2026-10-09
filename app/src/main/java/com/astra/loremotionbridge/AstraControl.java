package com.astra.loremotionbridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class AstraControl {
    private static volatile Bitmap lastScreen;
    private static volatile long lastFrameAt;
    private static volatile String captureError = "";
    private static volatile int framesReceived;

    public static synchronized boolean screenChanged(android.graphics.Bitmap before) {
        android.graphics.Bitmap after = lastScreen;
        if (before == null || after == null) return false;
        if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) return true;

        int w = after.getWidth();
        int h = after.getHeight();
        int different = 0;

        for (int i = 0; i < 64; i++) {
            int x = (i * 997) % w;
            int y = (i * 577) % h;
            if (before.getPixel(x, y) != after.getPixel(x, y)) {
                different++;
            }
        }

        return different >= 4;
    }

    public static synchronized android.graphics.Bitmap copyLastScreen() {
        return lastScreen == null ? null : lastScreen.copy(
                lastScreen.getConfig() == null
                        ? android.graphics.Bitmap.Config.ARGB_8888
                        : lastScreen.getConfig(),
                false
        );
    }

    public static synchronized void setLastScreen(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        Bitmap old = lastScreen;
        lastScreen = bitmap;
        lastFrameAt = System.currentTimeMillis();
        framesReceived++;
        captureError = "";
        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
    }

    public static void setCaptureError(String error) {
        captureError = error == null ? "" : error;
    }

    private AstraControl() {}

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    public static String status() {
        return "{\"ok\":true,\"layer\":\"astra-control\",\"version\":\"0.2\"}";
    }

    public static String screenStatus() {
        Bitmap screen = lastScreen;
        boolean service = ScreenCaptureService.instance != null;
        boolean frame = screen != null && !screen.isRecycled();
        long age = frame ? Math.max(0L, System.currentTimeMillis() - lastFrameAt) : -1L;
        return "{\"ok\":true"
                + ",\"captureService\":" + service
                + ",\"frameAvailable\":" + frame
                + ",\"width\":" + (frame ? screen.getWidth() : 0)
                + ",\"height\":" + (frame ? screen.getHeight() : 0)
                + ",\"framesReceived\":" + framesReceived
                + ",\"frameAgeMs\":" + age
                + ",\"captureError\":\"" + esc(captureError) + "\"}";
    }

    public static byte[] screenPng() throws IOException {
        synchronized (AstraControl.class) {
            Bitmap source = lastScreen;
            if (source == null || source.isRecycled()) return null;
            int maxWidth = 720;
            float scale = Math.min(1f, maxWidth / (float) source.getWidth());
            int outWidth = Math.max(1, Math.round(source.getWidth() * scale));
            int outHeight = Math.max(1, Math.round(source.getHeight() * scale));
            Bitmap copy = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(copy);
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                canvas.drawBitmap(source, null, new Rect(0, 0, outWidth, outHeight), paint);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                copy.compress(Bitmap.CompressFormat.PNG, 100, out);
                return out.toByteArray();
            } finally {
                copy.recycle();
            }
        }
    }

    public static String tap(String query, float x, float y) {
        if (query != null && !query.isEmpty()) {
            String found = AstraAccessibilityService.clickInfo(query);
            if (found.contains("\"ok\":true") && !found.contains("\"found\":false")) {
                return "{\"ok\":true,\"strategy\":\"accessibility\",\"query\":\"" + esc(query) + "\"}";
            }
        }
        boolean tapped = AstraAccessibilityService.tap(x, y);
        return "{\"ok\":" + tapped + ",\"strategy\":\"coordinate\",\"x\":" + x + ",\"y\":" + y + "}";
    }

    public static String observe() {
        String service = AstraAccessibilityService.serviceInfo();
        String windows = AstraAccessibilityService.windows();
        return "{\"ok\":true,\"service\":" + service + ",\"windows\":" + windows + "}";
    }
}
