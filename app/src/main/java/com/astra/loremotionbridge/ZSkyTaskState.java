package com.astra.loremotionbridge;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable task state shared by the ZSky worker and ASTRA chat UI. */
public final class ZSkyTaskState {
    private static final String PREFS = "astra_chat";
    private ZSkyTaskState() {}

    public static void update(Context context, String state, String detail) {
        if (context == null) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor edit = prefs.edit()
                .putString("zsky_task_state", state == null ? "unknown" : state)
                .putString("zsky_task_detail", detail == null ? "" : detail)
                .putLong("zsky_task_updated", now);
        if ("queued".equals(state) || "loading".equals(state)
                || "prompt_entered".equals(state) || "submitted_unconfirmed".equals(state)) {
            if (!prefs.contains("zsky_task_started")
                    || "completed".equals(prefs.getString("zsky_task_state", "")))
                edit.putLong("zsky_task_started", now);
        }
        if ("completed".equals(state) || "failed".equals(state)
                || "automation_timeout".equals(state)) edit.putLong("zsky_task_finished", now);
        edit.apply();
    }
}
