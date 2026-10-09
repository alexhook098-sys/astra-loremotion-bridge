package com.astra.loremotionbridge;

public final class AstraControl {

    private AstraControl() {}

    public static String status() {
        return "{\"ok\":true,\"layer\":\"astra-control\",\"version\":\"0.1\"}";
    }

    public static String observe() {
        String service = AstraAccessibilityService.serviceInfo();
        String windows = AstraAccessibilityService.windows();

        return "{\"ok\":true,\"service\":" + service
                + ",\"windows\":" + windows + "}";
    }
}
