package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.Intent;
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
                "Local API: 127.0.0.1:18765\n\n" +
                "Тестовая версия"
        );

        title.setTextSize(20);

        box.addView(title);

        // Специальные возможности
        Button accessibility =
                new Button(this);

        accessibility.setText(
                "ОТКРЫТЬ СПЕЦИАЛЬНЫЕ ВОЗМОЖНОСТИ"
        );

        accessibility.setOnClickListener(v -> {

            startActivity(
                    new Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS
                    )
            );

        });

        box.addView(accessibility);

        // ТЕСТ КНОПКИ
        Button start =
                new Button(this);

        start.setText(
                "ЗАПУСТИТЬ BROWSER BRIDGE"
        );

        start.setOnClickListener(v -> {

            title.setText(
                    "ASTRA Browser Bridge\n\n" +
                    "КНОПКА РАБОТАЕТ!\n\n" +
                    "onClick успешно сработал."
            );

            start.setText(
                    "РАБОТАЕТ ✓"
            );

        });

        box.addView(start);

        // LoreMotion
        Button open =
                new Button(this);

        open.setText(
                "ОТКРЫТЬ LOREMOTION В VIVALDI"
        );

        open.setOnClickListener(v -> {

            LocalBridgeServer.openUrl(
                    "https://loremotion.com/generate/"
            );

        });

        box.addView(open);

        setContentView(box);
    }
            }
