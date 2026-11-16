package com.astra.loremotionbridge;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

public final class PerchanceBrowserController {
    private PerchanceBrowserController() {}

    public static String start(Context context, String prompt, boolean background) {
        try {
            Intent intent = new Intent(context, PerchanceBrowserActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            intent.putExtra(PerchanceBrowserActivity.EXTRA_AUTOMATE,
                    prompt != null && !prompt.trim().isEmpty());
            if (prompt != null) intent.putExtra(PerchanceBrowserActivity.EXTRA_PROMPT, prompt);
            context.startActivity(intent);
            if (background) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    PerchanceBrowserActivity a = PerchanceBrowserActivity.getInstance();
                    if (a != null) a.minimizePerchance();
                }, 350L);
            }
            return "{\"ok\":true,\"action\":\"perchance-start-requested\",\"background\":"
                    + background + "}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    public static String status() {
        PerchanceBrowserActivity a = PerchanceBrowserActivity.getInstance();
        return a == null
                ? "{\"ok\":true,\"ready\":false,\"state\":\"stopped\"}"
                : a.statusJson();
    }

    public static String evaluate(String script) {
        PerchanceBrowserActivity a = PerchanceBrowserActivity.getInstance();
        if (a == null) return "{\"ok\":false,\"error\":\"perchance_not_started\"}";
        final Object lock = new Object();
        final String[] result = new String[1];
        final boolean[] done = new boolean[1];
        a.evaluateScript(script == null ? "document.title" : script, value -> {
            synchronized (lock) { result[0] = value; done[0] = true; lock.notifyAll(); }
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
                : "{\"ok\":false,\"error\":\"perchance_eval_timeout\"}";
    }

    public static String minimize() {
        PerchanceBrowserActivity a = PerchanceBrowserActivity.getInstance();
        if (a == null) return "{\"ok\":false,\"error\":\"perchance_not_started\"}";
        a.minimizePerchance();
        return "{\"ok\":true,\"action\":\"perchance-minimize-requested\"}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
