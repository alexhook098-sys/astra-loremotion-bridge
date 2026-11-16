package com.astra.loremotionbridge;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;

/** Private, on-device memory for one ASTRA user. No network access. */
public final class AstraMemory {
    private static final String PREFS = "astra_personal_memory";
    private static final String KEY = "facts";
    private static final int MAX_ITEMS = 40;
    private AstraMemory() {}

    private static SharedPreferences prefs() {
        return MainApplication.context().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized boolean remember(String fact) {
        if (fact == null) return false;
        String value = fact.trim();
        if (value.isEmpty() || value.length() > 500) return false;
        JSONArray old = read();
        JSONArray next = new JSONArray();
        try {
            for (int i = Math.max(0, old.length() - (MAX_ITEMS - 1)); i < old.length(); i++) {
                String existing = old.optString(i, "");
                if (!existing.equalsIgnoreCase(value)) next.put(existing);
            }
            next.put(value);
            prefs().edit().putString(KEY, next.toString()).apply();
            return true;
        } catch (Exception ignored) { return false; }
    }

    public static synchronized String summary() {
        JSONArray data = read();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < data.length(); i++) {
            String item = data.optString(i, "").trim();
            if (!item.isEmpty()) {
                if (out.length() > 0) out.append("\n");
                out.append("- ").append(item);
            }
        }
        return out.toString();
    }

    public static synchronized void clear() {
        prefs().edit().remove(KEY).apply();
    }

    private static JSONArray read() {
        try { return new JSONArray(prefs().getString(KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }
}
