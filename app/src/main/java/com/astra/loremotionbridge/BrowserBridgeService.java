package com.astra.loremotionbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class BrowserBridgeService extends Service {
    private static final String TAG = "ASTRA-BrowserBridge";
    private static final String CHANNEL_ID = "astra_browser_bridge";
    private static final int NOTIFICATION_ID = 18765;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ASTRA Browser Agent")
                .setContentText("Ready — waiting for a video request")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            LocalBridgeServer.start();
            HeadlessBrowserRuntime.startAsync(this);
            Log.i(TAG, "Bridge and headless runtime requested");
        } catch (Exception e) {
            Log.e(TAG, "FAILED TO START BROWSER BRIDGE", e);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        LocalBridgeServer.start();
        HeadlessBrowserRuntime.startAsync(this);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "ASTRA Browser Agent", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Local bridge and persistent headless Chromium runtime");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
