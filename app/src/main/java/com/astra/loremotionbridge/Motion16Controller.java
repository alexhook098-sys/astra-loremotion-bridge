package com.astra.loremotionbridge;

import android.content.Context;
import android.content.Intent;

public final class Motion16Controller {
    private Motion16Controller() {}

    public static String start(Context context, String url) {
        return start(context, url, false);
    }

    public static String start(Context context, String url, boolean background) {
        if (url == null || url.isEmpty()) url = Motion16Activity.PROJECT_URL;
        Motion16Activity current = Motion16Activity.getInstance();

        try {
            Intent intent = new Intent(context, Motion16Activity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP |
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            intent.putExtra("url", url);
            intent.putExtra("background", background);
            context.startActivity(intent);
            return "{\"ok\":true,\"action\":\"motion16-start-requested\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    public static String open(String url) {
        if (url == null || url.isEmpty()) url = Motion16Activity.PROJECT_URL;
        Motion16Activity current = Motion16Activity.getInstance();
        if (current == null) return start(MainApplication.context(), url);
        current.openUrl(url);
        return "{\"ok\":true,\"action\":\"motion16-open-requested\"}";
    }

    public static String evaluate(String script) {
        Motion16Activity activity = Motion16Activity.getInstance();
        if (activity == null) {
            return "{\"ok\":false,\"error\":\"motion16_not_started\"}";
        }

        final Object lock = new Object();
        final String[] result = new String[1];
        final boolean[] done = new boolean[1];

        activity.evaluateScript(
            script == null ? "document.title" : script,
            value -> {
                synchronized (lock) {
                    result[0] = value;
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        );

        synchronized (lock) {
            try {
                if (!done[0]) lock.wait(15000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{\"ok\":false,\"error\":\"interrupted\"}";
            }
        }

        if (!done[0]) {
            return "{\"ok\":false,\"error\":\"motion16_eval_timeout\"}";
        }

        return "{\"ok\":true,\"value\":"
            + (result[0] == null ? "null" : result[0]) + "}";
    }

    public static String download() {
        Motion16Activity current = Motion16Activity.getInstance();
        if (current == null) {
            return "{\"ok\":false,\"error\":\"motion16_not_started\"}";
        }
        current.downloadReadyVideo();
        return "{\"ok\":true,\"action\":\"motion16-download-requested\"}";
    }

    public static String status() {
        Motion16Activity current = Motion16Activity.getInstance();
        return current == null
            ? "{\"ok\":true,\"ready\":false,\"state\":\"stopped\"}"
            : current.statusJson();
    }

    public static String minimize() {
        Motion16Activity current = Motion16Activity.getInstance();
        if (current == null) {
            return "{\"ok\":false,\"error\":\"motion16_not_started\"}";
        }
        current.minimizeMotion16();
        return "{\"ok\":true,\"action\":\"motion16-minimize-requested\"}";
    }

    public static String close() {
        Motion16Activity current = Motion16Activity.getInstance();
        if (current == null)
            return "{\"ok\":true,\"state\":\"already-stopped\"}";
        current.runOnUiThread(current::finish);
        return "{\"ok\":true,\"action\":\"motion16-close-requested\"}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
