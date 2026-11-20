package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Dedicated overview for background media-generation jobs. */
public final class TaskCenterActivity extends Activity {
    private static final int BG = Color.rgb(8, 11, 24);
    private static final int PANEL = Color.rgb(20, 27, 49);
    private static final int CYAN = Color.rgb(101, 221, 245);
    private static final int WHITE = Color.rgb(234, 241, 255);
    private LinearLayout content;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            if (!isFinishing() && !isDestroyed()) handler.postDelayed(this, 1500L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(22), dp(18), dp(24));
        scroll.addView(content);
        setContentView(scroll);
        handler.post(refresh);
    }

    private void render() {
        if (content == null) return;
        content.removeAllViews();
        TextView title = text("✦  ASTRA · ЦЕНТР ЗАДАЧ", 20, CYAN, true);
        content.addView(title);
        TextView subtitle = text("Генерации продолжаются независимо от переписки.", 13,
                Color.rgb(160, 181, 211), false);
        subtitle.setPadding(0, dp(7), 0, dp(18));
        content.addView(subtitle);

        SharedPreferences p = getSharedPreferences("astra_chat", MODE_PRIVATE);
        String state = p.getString("zsky_task_state", "idle");
        String detail = p.getString("zsky_task_detail", "Пока нет активных заданий");
        long started = p.getLong("zsky_task_started", 0L);
        long elapsed = started > 0 ? Math.max(0, (System.currentTimeMillis()-started)/1000) : 0;
        String clock = String.format(java.util.Locale.ROOT, "%02d:%02d", elapsed/60, elapsed%60);
        String label;
        if ("loading".equals(state) || "queued".equals(state)) label = "ПОДКЛЮЧЕНИЕ";
        else if ("prompt_entered".equals(state)) label = "ПОДГОТОВКА";
        else if ("rendering".equals(state) || "submitted_unconfirmed".equals(state)) label = "РЕНДЕРИНГ";
        else if ("result_ready".equals(state)) label = "РЕЗУЛЬТАТ ГОТОВ";
        else if ("downloading".equals(state)) label = "СКАЧИВАНИЕ";
        else if ("completed".equals(state)) label = "ГОТОВО";
        else if ("failed".equals(state) || "automation_timeout".equals(state)) label = "ОШИБКА";
        else label = "ОЖИДАНИЕ";

        LinearLayout card = card();
        card.addView(text("ZSKY · " + label, 16,
                "failed".equals(state) ? Color.rgb(255,150,150) : CYAN, true));
        TextView info = text(detail, 14, WHITE, false);
        info.setPadding(0, dp(10), 0, dp(8));
        card.addView(info);
        card.addView(text(started > 0 ? "Время задачи · " + clock : "Задача ещё не запускалась",
                12, Color.rgb(160,181,211), false));
        content.addView(card);

        Button back = button("←  ВЕРНУТЬСЯ В ASTRA", CYAN);
        back.setOnClickListener(v -> finish());
        content.addView(back, params(48, 10));

        if ("completed".equals(state) || "failed".equals(state)
                || "automation_timeout".equals(state)) {
            Button reopen = button("ОТКРЫТЬ ZSKY", CYAN);
            reopen.setOnClickListener(v -> {
                ZSkyWebViewActivity.start(this, ZSkyWebViewActivity.ZSKY_URL, false);
            });
            content.addView(reopen, params(48, 8));
        }

        content.addView(text("ПОСЛЕДНИЕ РЕЗУЛЬТАТЫ", 14, CYAN, true), params(32, 18));
        JSONArray history;
        try { history = new JSONArray(p.getString("messages", "[]")); }
        catch (Exception e) { history = new JSONArray(); }
        int shown = 0;
        for (int i = history.length()-1; i >= 0 && shown < 12; i--) {
            JSONObject item = history.optJSONObject(i);
            if (item == null || !"zsky_video".equals(item.optString("type"))) continue;
            String uri = item.optString("uri", "");
            if (uri.isEmpty()) continue;
            LinearLayout result = card();
            result.addView(text("✓  Видео сохранено", 14, WHITE, true));
            TextView path = text("Movies/ASTRA/ZSky", 12, Color.rgb(160,181,211), false);
            path.setPadding(0, dp(6), 0, dp(8));
            result.addView(path);
            Button open = button("ОТКРЫТЬ MP4", CYAN);
            final String savedUri = uri;
            open.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(android.net.Uri.parse(savedUri), "video/mp4");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);
                } catch (Exception e) {
                    android.widget.Toast.makeText(this, "Не удалось открыть видео", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
            result.addView(open);
            content.addView(result);
            shown++;
        }
        if (shown == 0) content.addView(text("Готовые видео появятся здесь после сохранения.", 12,
                Color.rgb(160,181,211), false), params(34, 4));
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15), dp(15), dp(15), dp(15));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL); bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(65,103,140));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        content.addView(box, lp);
        return box;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private Button button(String value, int color) {
        Button b = new Button(this); b.setText(value); b.setTextSize(12);
        b.setTextColor(color); b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(PANEL);
        bg.setCornerRadius(dp(14)); bg.setStroke(dp(1), color); b.setBackground(bg);
        return b;
    }
    private LinearLayout.LayoutParams params(int height, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(height));
        p.topMargin = dp(top); return p;
    }
    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        super.onDestroy();
    }
}
