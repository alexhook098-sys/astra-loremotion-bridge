package com.astra.loremotionbridge;

public final class AstraControl {

    private static volatile android.graphics.Bitmap lastScreen;

    public static void setLastScreen(android.graphics.Bitmap bitmap) {
        android.graphics.Bitmap old = lastScreen;
        lastScreen = bitmap;
        if (old != null && old != bitmap && !old.isRecycled()) {
            old.recycle();
        }
    }


    private AstraControl() {}

    public static String status() {
        return "{\"ok\":true,\"layer\":\"astra-control\",\"version\":\"0.1\"}";
    }

    public static String screenStatus() {
        android.graphics.Bitmap screen = lastScreen;
        boolean service = ScreenCaptureService.instance != null;
        boolean frame = screen != null && !screen.isRecycled();

        return "{\\"ok\\":true"
                + ",\\"captureService\\":" + service
                + ",\\"frameAvailable\\":" + frame
                + ",\\"width\\":" + (frame ? screen.getWidth() : 0)
                + ",\\"height\\":" + (frame ? screen.getHeight() : 0)
                + "}";
    }

    public static String tap(String query, float x, float y) {
        if (query != null && !query.isEmpty()) {
            String found = AstraAccessibilityService.clickInfo(query);

            if (found.contains("\"ok\":true") &&
                !found.contains("\"found\":false")) {
                return "{\"ok\":true,\"strategy\":\"accessibility\",\"query\":\""
                        + query.replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\"}";
            }
        }

        boolean tapped = AstraAccessibilityService.tap(x, y);

        return "{\"ok\":" + tapped
                + ",\"strategy\":\"coordinate\",\"x\":" + x
                + ",\"y\":" + y + "}";
    }

    public static String observe() {
        String service = AstraAccessibilityService.serviceInfo();
        String windows = AstraAccessibilityService.windows();

        return "{\"ok\":true,\"service\":" + service
                + ",\"windows\":" + windows + "}";
    }
}
