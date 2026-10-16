package com.astra.loremotionbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView bridgeState;
    private TextView detailText;
    private boolean startupPending = false;
    private long startupDeadline = 0L;

    private final int bg = Color.rgb(12, 14, 18);
    private final int panel = Color.rgb(23, 26, 32);
    private final int red = Color.rgb(220, 48, 62);
    private final int silver = Color.rgb(226, 230, 236);
    private final int muted = Color.rgb(151, 160, 174);

    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (bridgeState == null || detailText == null) return;

            boolean running = LocalBridgeServer.isRunning();
            String error = LocalBridgeServer.getLastError();

            if (startupPending && !running && error == null
                    && System.currentTimeMillis() < startupDeadline) {
                bridgeState.setText("ЗАПУСКАЮ МОСТ…");
                bridgeState.setTextColor(silver);
                detailText.setText("Ожидаю подтверждения запуска локального HTTP-сервера.");
                handler.postDelayed(this, 300L);
                return;
            }

            startupPending = false;
            bridgeState.setText(running ? "●  МОСТ РАБОТАЕТ" : "●  МОСТ ОСТАНОВЛЕН");
            bridgeState.setTextColor(running ? Color.rgb(104, 220, 151) : red);

            String details = "Локальный HTTP-мост: "
                    + (running ? "активен" : "не запущен")
                    + "\nWebView открывается отдельно по запросу ASTRA.";
            if (error != null && !error.trim().isEmpty()) {
                details += "\n\nПоследняя ошибка:\n" + error;
            }
            detailText.setText(details);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(0);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 18765);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = new TextView(this);
        eyebrow.setText("A S T R A     /     SYSTEM");
        eyebrow.setTextColor(red);
        eyebrow.setTextSize(12);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(eyebrow);

        TextView title = new TextView(this);
        title.setText("ASTRA");
        title.setTextColor(silver);
        title.setTextSize(42);
        title.setLetterSpacing(0.08f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(7);
        root.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText("PERSONAL SYSTEM INTERFACE");
        subtitle.setTextColor(muted);
        subtitle.setTextSize(11);
        subtitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(2);
        root.addView(subtitle, subParams);

        View line = new View(this);
        line.setBackgroundColor(red);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(dp(54), dp(3));
        lineParams.topMargin = dp(20);
        lineParams.bottomMargin = dp(22);
        root.addView(line, lineParams);

        TextView statusHeading = new TextView(this);
        statusHeading.setText("СОСТОЯНИЕ СИСТЕМЫ");
        statusHeading.setTextColor(muted);
        statusHeading.setTextSize(11);
        statusHeading.setLetterSpacing(0.07f);
        statusHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(statusHeading);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(17), dp(18), dp(17));
        statusCard.setBackground(roundRect(panel, dp(14), Color.rgb(47, 52, 62)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(10);
        cardParams.bottomMargin = dp(26);
        root.addView(statusCard, cardParams);

        bridgeState = new TextView(this);
        bridgeState.setText("ПРОВЕРКА…");
        bridgeState.setTextSize(16);
        bridgeState.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusCard.addView(bridgeState);

        detailText = new TextView(this);
        detailText.setTextColor(muted);
        detailText.setTextSize(13);
        detailText.setLineSpacing(dp(4), 1.0f);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(10);
        statusCard.addView(detailText, detailParams);

        TextView actionsHeading = new TextView(this);
        actionsHeading.setText("УПРАВЛЕНИЕ");
        actionsHeading.setTextColor(muted);
        actionsHeading.setTextSize(11);
        actionsHeading.setLetterSpacing(0.07f);
        actionsHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(actionsHeading);

        addAction(root, "01   ЗАПУСТИТЬ HTTP-МОСТ", "Подключение локального управления ASTRA", true, v -> {
            handler.removeCallbacks(statusPoll);
            startupPending = true;
            startupDeadline = System.currentTimeMillis() + 10000L;
            bridgeState.setText("ЗАПУСКАЮ МОСТ…");
            bridgeState.setTextColor(silver);
            detailText.setText("Запускаю фоновую службу и ожидаю ответ сервера.");
            try {
                Intent service = new Intent(this, BrowserBridgeService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(service);
                } else {
                    startService(service);
                }
                handler.post(statusPoll);
            } catch (Exception e) {
                startupPending = false;
                bridgeState.setText("ОШИБКА ЗАПУСКА");
                bridgeState.setTextColor(red);
                detailText.setText(e.getClass().getSimpleName() + "\n"
                        + String.valueOf(e.getMessage()));
            }
        });

        addAction(root, "02   ОТКРЫТЬ WEBVIEW", "Открыть рабочую страницу LoreMotion", false, v -> {
            String result = WebViewBrowserController.ensureHost(this);
            detailText.setText("Результат WebView:\n" + result);
        });

        addAction(root, "03   ЖУРНАЛ СОБЫТИЙ", "Просмотр и копирование журнала запуска", false, v -> {
            TextView logText = new TextView(this);
            logText.setText(HeadlessBrowserRuntime.getLogText());
            logText.setTextIsSelectable(true);
            logText.setTextSize(12);
            logText.setTextColor(silver);
            logText.setPadding(dp(16), dp(14), dp(16), dp(14));

            ScrollView logScroll = new ScrollView(this);
            logScroll.setBackgroundColor(panel);
            logScroll.addView(logText, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            new AlertDialog.Builder(this)
                    .setTitle("ASTRA / ЖУРНАЛ")
                    .setView(logScroll)
                    .setPositiveButton("ЗАКРЫТЬ", null)
                    .setNeutralButton("КОПИРОВАТЬ", (dialog, which) -> {
                        ClipboardManager clipboard =
                                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText(
                                    "ASTRA runtime log", HeadlessBrowserRuntime.getLogText()));
                        }
                    })
                    .show();
        });

        addAction(root, "04   ОБНОВИТЬ СТАТУС", "Повторно проверить состояние HTTP-моста", false, v -> {
            handler.removeCallbacks(statusPoll);
            handler.post(statusPoll);
        });

        TextView footer = new TextView(this);
        footer.setText("ASTRA  •  LOREMOTION BRIDGE  •  v0.1.0");
        footer.setTextColor(Color.rgb(91, 99, 112));
        footer.setTextSize(10);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(26);
        root.addView(footer, footerParams);

        setContentView(scroll);
        statusPoll.run();
    }

    private void addAction(LinearLayout parent, String label, String description,
                           boolean primary, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(roundRect(primary
                ? Color.rgb(42, 25, 31) : panel, dp(12),
                primary ? Color.rgb(122, 43, 55) : Color.rgb(47, 52, 62)));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(listener);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(primary ? Color.rgb(255, 224, 226) : silver);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText(description);
        desc.setTextColor(muted);
        desc.setTextSize(12);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = dp(5);
        card.addView(desc, descParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(9);
        parent.addView(card, params);
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onResume() {
        super.onResume();
        if (bridgeState != null) {
            handler.removeCallbacks(statusPoll);
            handler.post(statusPoll);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statusPoll);
        bridgeState = null;
        detailText = null;
        super.onDestroy();
    }
}
