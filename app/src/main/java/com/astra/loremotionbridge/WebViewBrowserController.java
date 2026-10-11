package com.astra.loremotionbridge;

import android.content.Context;
import android.content.Intent;

public final class WebViewBrowserController {
    private WebViewBrowserController() {}

    public static String start(Context context, String url) {
        try {
            Intent i = new Intent(context, WebViewBrowserActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("url", url);
            context.startActivity(i);
            return "{\"ok\":true,\"action\":\"webview-start\",\"url\":\"" +
                    esc(url) + "\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    public static String open(String url) {
        WebViewBrowserActivity a = WebViewBrowserActivity.getInstance();
        if (a == null)
            return "{\"ok\":false,\"error\":\"webview_not_started\"}";
        a.openUrl(url);
        return "{\"ok\":true,\"action\":\"webview-open\",\"url\":\"" +
                esc(url) + "\"}";
    }

    public static String status() {
        WebViewBrowserActivity a = WebViewBrowserActivity.getInstance();
        return a == null
                ? "{\"ok\":true,\"ready\":false,\"state\":\"stopped\"}"
                : a.statusJson();
    }

    public static String evaluate(String script) {
        WebViewBrowserActivity a = WebViewBrowserActivity.getInstance();
        if (a == null)
            return "{\"ok\":false,\"error\":\"webview_not_started\"}";

        final Object lock = new Object();
        final String[] result = new String[1];
        final String[] error = new String[1];
        final boolean[] done = new boolean[1];

        a.evaluate(script, (value, err) -> {
            synchronized (lock) {
                result[0] = value;
                error[0] = err;
                done[0] = true;
                lock.notifyAll();
            }
        });

        synchronized (lock) {
            try {
                if (!done[0]) lock.wait(15000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{\"ok\":false,\"error\":\"interrupted\"}";
            }
        }

        if (!done[0])
            return "{\"ok\":false,\"error\":\"eval_timeout\"}";
        if (error[0] != null)
            return "{\"ok\":false,\"error\":\"" + esc(error[0]) + "\"}";

        return "{\"ok\":true,\"value\":" +
                (result[0] == null ? "null" : result[0]) + "}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
