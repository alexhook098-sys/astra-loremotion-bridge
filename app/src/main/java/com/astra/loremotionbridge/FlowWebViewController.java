package com.astra.loremotionbridge;

import android.content.Context;
import android.content.Intent;

public final class FlowWebViewController {
    private static final String PROJECT_URL =
            "https://flow.google.com/u/1/project/c311a6f3-e3de-4490-b97c-54ddd8610af1";

    private FlowWebViewController() {}

    public static String start(Context context, String url) {
        return start(context, url, null, true);
    }

    public static String start(Context context, String url, String prompt, boolean background) {
        if (url == null || url.isEmpty()) url = PROJECT_URL;
        try {
            Intent intent = new Intent(context, FlowWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra("url", url);
            intent.putExtra("background", background);
            boolean automate = prompt != null && !prompt.trim().isEmpty();
            intent.putExtra(FlowWebViewActivity.EXTRA_AUTOMATE, automate);
            if (automate) intent.putExtra(FlowWebViewActivity.EXTRA_PROMPT, prompt.trim());

            android.content.pm.ResolveInfo resolved =
                context.getPackageManager().resolveActivity(intent, 0);
            if (resolved == null)
                return "{\"ok\":false,\"error\":\"flow_activity_not_resolvable\"}";

            context.startActivity(intent);
            android.util.Log.i("ASTRA-FLOW", "start(): url=" + url
                    + " automate=" + automate + " background=" + background);
            return "{\"ok\":true,\"action\":\"flow-start-requested\",\"resolved\":true,"
                    + "\"background\":" + background + ",\"automationQueued\":" + automate
                    + ",\"activity\":\"" + esc(resolved.activityInfo.name)
                    + "\",\"url\":\"" + esc(url) + "\"}";
        } catch (Exception e) {
            android.util.Log.e("ASTRA-FLOW", "start(): startActivity failed", e);
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    public static String startBackup(Context context) {
        return start(context, "https://flow.google.com/u/2/project/4daa2354-7afc-44e5-b51d-1652eb4aaaac");
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
        if (activity == null) return "{\"ok\":false,\"error\":\"flow_not_started\"}";
        activity.reloadInBackground();
        return "{\"ok\":true,\"action\":\"flow-reload-requested\"}";
    }

    public static String minimize() {
        FlowWebViewActivity activity = FlowWebViewActivity.getInstance();
        if (activity == null) return "{\"ok\":false,\"error\":\"flow_not_started\"}";
        activity.minimizeFlow();
        return "{\"ok\":true,\"action\":\"flow-minimize-requested\"}";
    }

    public static String close() {
        FlowWebViewActivity activity = FlowWebViewActivity.getInstance();
        if (activity == null) return "{\"ok\":true,\"action\":\"flow-already-closed\"}";
        activity.closeFlow();
        return "{\"ok\":true,\"action\":\"flow-close-requested\"}";
    }

    public static String evaluate(String script) {
        FlowWebViewActivity activity = FlowWebViewActivity.getInstance();
        if (activity == null) return "{\"ok\":false,\"error\":\"flow_not_started\"}";
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
            try { if (!done[0]) lock.wait(15000L); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{\"ok\":false,\"error\":\"interrupted\"}";
            }
        }
        return done[0]
                ? "{\"ok\":true,\"value\":" + (result[0] == null ? "null" : result[0]) + "}"
                : "{\"ok\":false,\"error\":\"flow_eval_timeout\"}";
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
