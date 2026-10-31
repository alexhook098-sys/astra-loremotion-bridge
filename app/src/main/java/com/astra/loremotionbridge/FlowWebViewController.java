package com.astra.loremotionbridge;

import android.content.Context;
import android.content.Intent;

public final class FlowWebViewController {
    private static final String PROJECT_URL =
            "https://flow.google.com/u/2/project/4daa2354-7afc-44e5-b51d-1652eb4aaaac";

    private FlowWebViewController() {}

    public static String start(Context context, String url) {
        if (url == null || url.isEmpty()) url = PROJECT_URL;
        FlowWebViewActivity activity = FlowWebViewActivity.getInstance();
        if (activity != null) {
            bringToFront(context);
            activity.openUrl(url);
            return "{\"ok\":true,\"action\":\"flow-start-reused-foreground\",\"url\":\"" + esc(url) + "\"}";
        }
        try {
            Intent intent = new Intent(context, FlowWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra("url", url);
            // Keep Flow visible while generation and editor interaction run.
            // Android may throttle or block WebView interaction while this Activity is backgrounded.
            intent.putExtra("background", false);
            android.content.pm.ResolveInfo resolved =
                context.getPackageManager().resolveActivity(intent, 0);
            if (resolved == null)
                return "{\"ok\":false,\"error\":\"flow_activity_not_resolvable\"}";
            android.util.Log.i("ASTRA-FLOW", "start(): launching URL=" + url
                + " activity=" + resolved.activityInfo.name);
            context.startActivity(intent);
            android.util.Log.i("ASTRA-FLOW", "start(): startActivity returned normally");
            return "{\"ok\":true,\"action\":\"flow-start-requested\",\"resolved\":true,\"activity\":\""
                + esc(String.valueOf(resolved.activityInfo.name)) + "\",\"url\":\""
                + esc(url) + "\"}";
        } catch (Exception e) {
            android.util.Log.e("ASTRA-FLOW", "start(): startActivity failed", e);
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    public static String startBackup(Context context) {
        return start(context, "https://flow.google.com/u/1/project/54203513-938c-412e-9974-9ddf842bd7d5");
    }

    public static String bringToFront(Context context) {
        if (FlowWebViewActivity.getInstance() == null)
            return "{\"ok\":false,\"error\":\"flow_not_started\"}";
        try {
            Intent intent = new Intent(context, FlowWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            context.startActivity(intent);
            return "{\"ok\":true,\"action\":\"flow-brought-to-front\"}";
        } catch (Exception e) {
            android.util.Log.e("ASTRA-FLOW", "bringToFront() failed", e);
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

    public static String reload() {
        FlowWebViewActivity activity = FlowWebViewActivity.getInstance();
        if (activity == null) {
            return "{\"ok\":false,\"error\":\"flow_not_started\"}";
        }
        activity.reloadInBackground();
        return "{\"ok\":true,\"action\":\"flow-reload-requested\"}";
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

    public static String downloadOriginal720() {
        FlowWebViewActivity activity = FlowWebViewActivity.getInstance();
        if (activity == null) {
            return "{\"ok\":false,\"error\":\"flow_not_started\"}";
        }
        final Object lock = new Object();
        final String[] result = new String[1];
        final boolean[] done = new boolean[1];
        activity.downloadOriginal720(value -> {
            synchronized (lock) {
                result[0] = value;
                done[0] = true;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            try {
                if (!done[0]) lock.wait(12000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{\"ok\":false,\"error\":\"interrupted\"}";
            }
        }
        if (!done[0]) return "{\"ok\":false,\"error\":\"flow_download_action_timeout\"}";
        String value = result[0] == null ? "" : result[0];
        boolean ok = value.contains("OK|");
        return "{\"ok\":" + ok + ",\"action\":\"flow-download-original-720\",\"result\":\"" + esc(value) + "\"}";
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
