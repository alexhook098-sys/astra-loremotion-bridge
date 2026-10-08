package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView title;

    @Override
    protected void onCreate(Bundle state) {

        super.onCreate(state);

        LinearLayout box =
                new LinearLayout(this);

        box.setOrientation(
                LinearLayout.VERTICAL
        );

        box.setPadding(
                40,
                40,
                40,
                40
        );

        box.setGravity(
                Gravity.CENTER_HORIZONTAL
        );

        title = new TextView(this);

        title.setText(
                "ASTRA Browser Bridge\n\n" +
                "Local API: 127.0.0.1:18765"
        );

        title.setTextSize(20);

        box.addView(title);

        // Accessibility settings
        Button accessibility =
                new Button(this);

        accessibility.setText(
                "Открыть специальные возможности"
        );

        accessibility.setOnClickListener(
                v -> startActivity(
                        new Intent(
                                Settings.ACTION_ACCESSIBILITY_SETTINGS
                        )
                )
        );

        box.addView(accessibility);

        // Start Browser Bridge
        Button start =
                new Button(this);

        start.setText(
                "ЗАПУСТИТЬ BROWSER BRIDGE"
        );

        start.setOnClickListener(v -> {

            try {

                startBrowserBridge();

                title.setText(
                        "ASTRA Browser Bridge\n\n" +
                        "Команда запуска отправлена\n\n" +
                        "http://127.0.0.1:18765"
                );

            } catch (Exception e) {

                String message =
                        e.getMessage() == null
                                ? "без сообщения"
                                : e.getMessage();

                title.setText(
                        "ASTRA Browser Bridge\n\n" +
                        "ОШИБКА ЗАПУСКА:\n\n" +
                        e.getClass().getName() +
                        "\n\n" +
                        message
                );
            }
        });

        box.addView(start);

        // Open LoreMotion
        Button open =
                new Button(this);

        open.setText(
                "ОТКРЫТЬ LOREMOTION В VIVALDI"
        );

        open.setOnClickListener(
                v -> LocalBridgeServer.openUrl(
                        "https://loremotion.com/generate/"
                )
        );

        box.addView(open);

        setContentView(box);
    }

    private void startBrowserBridge() {

        Intent intent =
                new Intent(
                        this,
                        BrowserBridgeService.class
                );

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O) {

            startForegroundService(intent);

        } else {

            startService(intent);
        }
    }
            }
