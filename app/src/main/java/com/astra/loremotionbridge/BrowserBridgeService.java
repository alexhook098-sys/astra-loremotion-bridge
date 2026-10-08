package com.astra.loremotionbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class BrowserBridgeService extends Service {

    private static final String CHANNEL_ID = "astra_browser_bridge";
    private static final int NOTIFICATION_ID = 18765;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        Notification notification =
                new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("ASTRA Browser Bridge")
                        .setContentText("Browser Bridge работает на порту 18765")
                        .setSmallIcon(android.R.drawable.ic_menu_view)
                        .setOngoing(true)
                        .build();

        startForeground(NOTIFICATION_ID, notification);

        LocalBridgeServer.start();
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        LocalBridgeServer.start();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "ASTRA Browser Bridge",
                            NotificationManager.IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "Local browser automation bridge"
            );

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class
                    );

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
          }
