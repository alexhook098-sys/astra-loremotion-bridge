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

        TextView title =
                new TextView(this);

        title.setText(
                "ASTRA Browser Bridge\n\n" +
                "Local API: 127.0.0.1:18765"
        );

        title.setTextSize(20);

        box.addView(title);

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

        Button start =
                new Button(this);

        start.setText(
                "Запустить Browser Bridge"
        );

        start.setOnClickListener(v -> {

            LocalBridgeServer.start();

            title.setText(
                    "ASTRA Browser Bridge\n\n" +
                    "Сервер: http://127.0.0.1:18765"
            );
        });

        box.addView(start);

        Button open =
                new Button(this);

        open.setText(
                "Открыть LoreMotion в Vivaldi"
        );

        open.setOnClickListener(
                v -> LocalBridgeServer.openUrl(
                        "https://loremotion.com/generate/"
                )
        );

        box.addView(open);

        setContentView(box);

        LocalBridgeServer.start();
    }
}
