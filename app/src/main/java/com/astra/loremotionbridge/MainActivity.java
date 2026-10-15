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
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusText;

    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (statusText == null) return;
            statusText.setText("ASTRA LoreMotion Bridge\n\n"
                    + "Фоновый сервер: HTTP 18765\n"
                    + "Браузер: Android WebView\n"
                    + "Вход в аккаунт: кнопка «ПОКАЗАТЬ WEBVIEW».");
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

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(36, 48, 36, 40);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        statusText = new TextView(this);
        statusText.setTextSize(17);
        layout.addView(statusText);

        Button start = new Button(this);
        start.setText("ЗАПУСТИТЬ ASTRA BRIDGE");
        start.setOnClickListener(v -> {
            statusText.setText("Запускаю фоновый мост. WebView будет открыт по запросу ASTRA; для входа нажми кнопку ниже.");
            Intent service = new Intent(this, BrowserBridgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }

            handler.removeCallbacks(statusPoll);
            handler.post(statusPoll);
        });
        layout.addView(start);

        Button showWebView = new Button(this);
        showWebView.setText("ПОКАЗАТЬ WEBVIEW");
        showWebView.setOnClickListener(v -> {
            String result = WebViewBrowserController.ensureHost(this);
            statusText.setText("WebView: " + result);
        });
        layout.addView(showWebView);

        Button showLog = new Button(this);
        showLog.setText("ПОКАЗАТЬ ЖУРНАЛ ОШИБОК");
        showLog.setOnClickListener(v -> {
            TextView logText = new TextView(this);
            logText.setText(HeadlessBrowserRuntime.getLogText());
            logText.setTextIsSelectable(true);
            logText.setTextSize(12);
            logText.setPadding(24, 16, 24, 16);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(logText, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            new AlertDialog.Builder(this)
                    .setTitle("ASTRA — журнал запуска")
                    .setView(scroll)
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
        layout.addView(showLog);

        Button refresh = new Button(this);
        refresh.setText("ОБНОВИТЬ СТАТУС");
        refresh.setOnClickListener(v -> {
            handler.removeCallbacks(statusPoll);
            handler.post(statusPoll);
        });
        layout.addView(refresh);

        setContentView(layout);
        statusPoll.run();
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
