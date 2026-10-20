package com.astra.loremotionbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusText;

    private static final int BG = Color.rgb(7, 11, 26);
    private static final int PANEL = Color.rgb(18, 25, 49);
    private static final int CYAN = Color.rgb(53, 217, 255);
    private static final int PURPLE = Color.rgb(139, 92, 255);
    private static final int PINK = Color.rgb(255, 79, 216);
    private static final int WHITE = Color.rgb(231, 241, 255);
    private static final int MUTED = Color.rgb(145, 164, 197);

    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (statusText == null) return;
            String bridgeError = LocalBridgeServer.getLastError();
            statusText.setText(
                    "LOREMOTION BRIDGE  /  LOCAL SERVICE\n\n"
                    + "HTTP ENDPOINT     : 18765\n"
                    + "СОСТОЯНИЕ          : "
                    + (LocalBridgeServer.isRunning() ? "РАБОТАЕТ"
                    : LocalBridgeServer.isStarting() ? "ЗАПУСКАЕТСЯ"
                    : "НЕ ЗАПУЩЕН")
                    + "\n\nОШИБКА СЕРВЕРА\n"
                    + (bridgeError == null ? "Пока нет данных" : bridgeError)
                    + "\n\nWEB ENGINE         : Android WebView\n"
                    + "Вход в аккаунт — через кнопку WebView ниже."
            );
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    18765
            );
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(28), dp(22), dp(24));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(layout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = new TextView(this);
        eyebrow.setText("✦   SYSTEM ONLINE   /   01");
        eyebrow.setTextSize(11);
        eyebrow.setTextColor(CYAN);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setGravity(Gravity.CENTER);
        layout.addView(eyebrow, matchWrap());

        TextView title = new TextView(this);
        title.setText("A S T R A");
        title.setTextSize(36);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(WHITE);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.16f);
        title.setShadowLayer(dp(18), 0, 0, CYAN);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(10);
        layout.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText("N E U R A L   I N T E R F A C E");
        subtitle.setTextSize(10);
        subtitle.setTextColor(PURPLE);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLetterSpacing(0.10f);
        LinearLayout.LayoutParams subParams = matchWrap();
        subParams.topMargin = dp(5);
        subParams.bottomMargin = dp(26);
        layout.addView(subtitle, subParams);

        TextView section = new TextView(this);
        section.setText("01   /   СИСТЕМА И СОСТОЯНИЕ");
        section.setTextSize(11);
        section.setTextColor(CYAN);
        section.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams sectionParams = matchWrap();
        sectionParams.bottomMargin = dp(10);
        layout.addView(section, sectionParams);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(17), dp(18), dp(17));
        GradientDrawable panel = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(21, 32, 62), Color.rgb(13, 18, 39)});
        panel.setCornerRadius(dp(20));
        panel.setStroke(dp(1), Color.rgb(48, 133, 190));
        statusCard.setBackground(panel);
        statusCard.setElevation(dp(4));

        TextView statusHeading = new TextView(this);
        statusHeading.setText("◉   BRIDGE MONITOR");
        statusHeading.setTextSize(13);
        statusHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusHeading.setTextColor(CYAN);
        statusHeading.setLetterSpacing(0.05f);
        statusCard.addView(statusHeading, matchWrap());

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setTextColor(WHITE);
        statusText.setLineSpacing(dp(4), 1.0f);
        statusText.setPadding(0, dp(16), 0, dp(3));
        statusCard.addView(statusText, matchWrap());

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(25);
        layout.addView(statusCard, cardParams);

        TextView controlsTitle = new TextView(this);
        controlsTitle.setText("02   /   УПРАВЛЕНИЕ");
        controlsTitle.setTextSize(11);
        controlsTitle.setTextColor(PINK);
        controlsTitle.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams controlsParams = matchWrap();
        controlsParams.bottomMargin = dp(10);
        layout.addView(controlsTitle, controlsParams);

        Button start = new Button(this);
        start.setText("▶   ЗАПУСТИТЬ ASTRA BROWSER");
        start.setOnClickListener(v -> {
            statusText.setText("Запускаю фоновый мост. Для входа в аккаунт открой WebView кнопкой ниже.");
            Intent service = new Intent(this, BrowserBridgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            WebViewBrowserController.ensureHost(this);
            handler.removeCallbacks(statusPoll);
            handler.post(statusPoll);
        });
        styleButton(start, CYAN, true);
        layout.addView(start);

        Button showWebView = new Button(this);
        showWebView.setText("◎   ПОКАЗАТЬ WEBVIEW");
        showWebView.setOnClickListener(v -> {
            String result = WebViewBrowserController.bringToFront(this);
            statusText.setText("WebView: " + result);
        });
        styleButton(showWebView, PURPLE, false);
        layout.addView(showWebView);

        Button showLog = new Button(this);
        showLog.setText("≋   ЖУРНАЛ ОШИБОК");
        showLog.setOnClickListener(v -> {
            TextView logText = new TextView(this);
            logText.setText(HeadlessBrowserRuntime.getLogText());
            logText.setTextIsSelectable(true);
            logText.setTextSize(12);
            logText.setTextColor(WHITE);
            logText.setPadding(dp(20), dp(16), dp(20), dp(16));
            logText.setBackgroundColor(BG);
            ScrollView logScroll = new ScrollView(this);
            logScroll.addView(logText, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            new AlertDialog.Builder(this)
                    .setTitle("ASTRA — журнал запуска")
                    .setView(logScroll)
                    .setPositiveButton("ЗАКРЫТЬ", null)
                    .setNeutralButton("КОПИРОВАТЬ", (dialog, which) -> {
                        android.content.ClipboardManager clipboard =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "ASTRA runtime log", HeadlessBrowserRuntime.getLogText()));
                        }
                    })
                    .show();
        });
        styleButton(showLog, PINK, false);
        layout.addView(showLog);

        Button refresh = new Button(this);
        refresh.setText("↻   ОБНОВИТЬ СТАТУС");
        refresh.setOnClickListener(v -> {
            handler.removeCallbacks(statusPoll);
            handler.post(statusPoll);
        });
        styleButton(refresh, CYAN, false);
        layout.addView(refresh);

        TextView footer = new TextView(this);
        footer.setText("ASTRA  •  LOCAL CONTROL NODE");
        footer.setTextSize(10);
        footer.setTextColor(MUTED);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams footerParams = matchWrap();
        footerParams.topMargin = dp(15);
        layout.addView(footer, footerParams);

        setContentView(scroll);
        statusPoll.run();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void styleButton(Button button, int accent, boolean primary) {
        button.setTextColor(primary ? BG : WHITE);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setLetterSpacing(0.015f);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                primary
                        ? new int[]{CYAN, Color.rgb(100, 190, 255)}
                        : new int[]{PANEL, Color.rgb(24, 28, 57)});
        shape.setCornerRadius(dp(15));
        shape.setStroke(dp(1), accent);
        button.setBackground(shape);
        button.setElevation(dp(2));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(11);
        button.setLayoutParams(params);
    }

    @Override protected void onResume() {
        super.onResume();
        if (statusText != null) {
            handler.removeCallbacks(statusPoll);
            handler.post(statusPoll);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statusPoll);
        statusText = null;
        super.onDestroy();
    }
}
