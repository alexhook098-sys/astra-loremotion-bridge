package com.astra.loremotionbridge;

public final class BrowserController {
    private BrowserController() {}

    public static String tap(float x, float y) {
        return "{\"ok\":" + AstraAccessibilityService.tap(x, y) + ",\"action\":\"tap\",\"x\":" + x + ",\"y\":" + y + "}";
    }

    public static String swipe(float x1, float y1, float x2, float y2, long duration) {
        return "{\"ok\":" + AstraAccessibilityService.swipe(x1, y1, x2, y2, duration)
                + ",\"action\":\"swipe\"}";
    }

    public static String scroll(String direction) {
        return AstraAccessibilityService.scroll(direction);
    }

    public static String back() {
        return "{\"ok\":" + AstraAccessibilityService.globalBack() + ",\"action\":\"back\"}";
    }

    public static String home() {
        return "{\"ok\":" + AstraAccessibilityService.globalHome() + ",\"action\":\"home\"}";
    }

    public static String focus(String query) {
        return AstraAccessibilityService.focusInfo(query);
    }
}
