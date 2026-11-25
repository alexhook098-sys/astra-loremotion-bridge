package com.astra.loremotionbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

public class BrowserBridgeService extends Service {
    private static final String TAG = "ASTRA-BrowserBridge";
    private static final String CHANNEL_ID = "astra_browser_bridge";
    private static final int NOTIFICATION_ID = 18765;
    private static volatile BrowserBridgeService instance;
    private PowerManager.WakeLock aliceWakeLock;
    private volatile boolean aliceTaskActive;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        Notification notification = buildNotification("Ready — waiting for a video request");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            LocalBridgeServer.start();
            Log.i(TAG, "Bridge started; WebView backend available");
        } catch (Exception e) {
            Log.e(TAG, "FAILED TO START BROWSER BRIDGE", e);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        LocalBridgeServer.start();
        return START_STICKY;
    }

    public static void setAliceTaskActive(boolean active) {
        BrowserBridgeService service = instance;
        if (service != null) service.updateAliceTaskState(active);
    }

    private Notification buildNotification(String message) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ASTRA Browser Agent")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    private synchronized void updateAliceTaskState(boolean active) {
        aliceTaskActive = active;
        try {
            if (active) {
                if (aliceWakeLock == null) {
                    PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
                    if (manager != null) {
                        aliceWakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                "ASTRA:AliceMediaAgent");
                        aliceWakeLock.setReferenceCounted(false);
                    }
                }
                if (aliceWakeLock != null && !aliceWakeLock.isHeld()) {
                    // Safety timeout releases the lock even if Android destroys the Activity unexpectedly.
                    aliceWakeLock.acquire(60L * 60L * 1000L);
                }
                updateNotification("Alice Media is rendering in background");
                Log.i(TAG, "Alice task active; partial wake lock acquired");
            } else {
                if (aliceWakeLock != null && aliceWakeLock.isHeld()) aliceWakeLock.release();
                updateNotification("Ready — waiting for a video request");
                Log.i(TAG, "Alice task finished; partial wake lock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not update Alice background state", e);
        }
    }

    private void updateNotification(String message) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(message));
    }

    @Override public void onDestroy() {
        updateAliceTaskState(false);
        if (instance == this) instance = null;
        super.onDestroy();
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
