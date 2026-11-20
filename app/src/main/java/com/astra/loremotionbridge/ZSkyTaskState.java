package com.astra.loremotionbridge;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable task state shared by the ZSky worker and ASTRA chat UI. */
public final class ZSkyTaskState {
    private static final String PREFS = "astra_chat";
    private ZSkyTaskState() {}

    public static synchronized void update(Context context, String state, String detail) {
        if (context == null) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String next = state == null ? "unknown" : state;
        String previous = prefs.getString("zsky_task_state", "idle");
        boolean terminal = "completed".equals(previous) || "failed".equals(previous)
                || "automation_timeout".equals(previous);
        boolean newTask = "queued".equals(next) || "loading".equals(next);
        if (terminal && !newTask) return;
        if (!newTask && rank(next) < rank(previous)) return;
        long now = System.currentTimeMillis();
        SharedPreferences.Editor edit = prefs.edit()
                .putString("zsky_task_state", next)
                .putString("zsky_task_detail", detail == null ? "" : detail)
                .putLong("zsky_task_updated", now);
        if (newTask) edit.putLong("zsky_task_started", now).remove("zsky_task_finished");
        if ("completed".equals(next) || "failed".equals(next)
                || "automation_timeout".equals(next)) edit.putLong("zsky_task_finished", now);
        edit.commit();
        try { AstraZSkyForegroundService.update(context, next, detail); }
        catch (Exception ignored) { android.util.Log.w("ASTRA-ZSKY-SERVICE", "Notification update unavailable", ignored); }
    }

    private static int rank(String state) {
        if ("loading".equals(state) || "queued".equals(state)) return 1;
        if ("prompt_entered".equals(state)) return 2;
        if ("rendering".equals(state) || "submitted_unconfirmed".equals(state)) return 3;
        if ("result_ready".equals(state)) return 4;
        if ("downloading".equals(state)) return 5;
        if ("completed".equals(state) || "failed".equals(state)
                || "automation_timeout".equals(state)) return 6;
        return 0;
    }
}
