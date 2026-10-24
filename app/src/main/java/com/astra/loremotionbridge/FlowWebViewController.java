package com.astra.loremotionbridge;

import android.content.Context;
import android.content.Intent;

public final class FlowWebViewController {
    private static final String PROJECT_URL =
            "https://flow.google.com/project/f40f4fe3-b63d-4e8a-82cd-4afb2e30558d";

    private FlowWebViewController() {}

    public static String start(Context context, String url) {
        if (url == null || url.isEmpty()) url = PROJECT_URL;
        FlowWebViewActivity activity = FlowWebViewActivity.getInstance();
        if (activity != null) {
            activity.openUrlInBackground(url);
            return "{\"ok\":true,\"action\":\"flow-start-reused\",\"url\":\""
                    + esc(url) + "\"}";
        }
        try {
            Intent intent = new Intent(context, FlowWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra("url", url);
            intent.putExtra("background", true);
            context.startActivity(intent);
            return "{\"ok\":true,\"action\":\"flow-start-requested\",\"url\":\""
                    + esc(url) + "\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }
    public static String open(Context context, String url) {
        if (url == null || url.isEmpty()) url = PROJECT_URL;
        FlowWebViewActivity activity = FlowWebViewActivity.getInstance();
        if (activity != null) {
            activity.openUrl(url);
            return "{\"ok\":true,\"action\":\"flow-open-requested\",\"url\":\""
                    + esc(url) + "\"}";
        }
        return start(context, url);
    }

    public static String status() {
        FlowWebViewActivity activity = FlowWebViewActivity.getInstance();
        return activity == null
                ? "{\"ok\":true,\"ready\":false,\"state\":\"stopped\"}"
                : activity.statusJson();
    }

    public static String minimize() {
        FlowWebViewActivity activity = FlowWebViewActivity.getInstance();
        if (activity == null) {
            return "{\"ok\":false,\"error\":\"flow_not_started\"}";
        }
        activity.minimizeFlow();
        return "{\"ok\":true,\"action\":\"flow-minimize-requested\"}";
    }

    public static String close() {
        FlowWebViewActivity activity = FlowWebViewActivity.getInstance();
        if (activity == null) {
            return "{\"ok\":true,\"action\":\"flow-already-closed\"}";
        }
        activity.closeFlow();
        return "{\"ok\":true,\"action\":\"flow-close-requested\"}";
    }

    public static String evaluate(String script) {
        FlowWebViewActivity activity = FlowWebViewActivity.getInstance();
        if (activity == null) {
            return "{\"ok\":false,\"error\":\"flow_not_started\"}";
        }

        final Object lock = new Object();
        final String[] result = new String[1];
        final boolean[] done = new boolean[1];

        activity.evaluate(script == null ? "document.title" : script, value -> {
            synchronized (lock) {
                result[0] = value;
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

        if (!done[0]) {
            return "{\"ok\":false,\"error\":\"flow_eval_timeout\"}";
        }
        return "{\"ok\":true,\"value\":"
                + (result[0] == null ? "null" : result[0]) + "}";
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
