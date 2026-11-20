package com.astra.loremotionbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/** Persistent elapsed-time notification for one ZSky task. */
public final class ZSkyTaskTimerService extends Service {
    public static final String ACTION_START = "com.astra.loremotionbridge.zsky_timer.START";
    public static final String ACTION_STAGE = "com.astra.loremotionbridge.zsky_timer.STAGE";
    public static final String ACTION_STOP = "com.astra.loremotionbridge.zsky_timer.STOP";
    public static final String EXTRA_STAGE = "stage";
    private static final String CHANNEL_ID = "astra_zsky_task";
    private static final int NOTIFICATION_ID = 18771;
    private long startedAt;
    private String stage = "Открываю ZSky";

    public static void start(android.content.Context context) {
        Intent i = new Intent(context, ZSkyTaskTimerService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
            else context.startService(i);
        } catch (Exception e) {
            Log.e("ASTRA-ZSKY-TIMER", "Could not start task timer", e);
        }
    }

    public static void update(android.content.Context context, String value) {
        Intent i = new Intent(context, ZSkyTaskTimerService.class)
                .setAction(ACTION_STAGE).putExtra(EXTRA_STAGE, value);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
            else context.startService(i);
        } catch (Exception e) {
            Log.e("ASTRA-ZSKY-TIMER", "Could not update task stage", e);
        }
    }

    public static void stop(android.content.Context context) {
        Intent i = new Intent(context, ZSkyTaskTimerService.class).setAction(ACTION_STOP);
        try { context.startService(i); }
        catch (Exception e) { Log.e("ASTRA-ZSKY-TIMER", "Could not stop task timer", e); }
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startedAt = SystemClock.elapsedRealtime();
        startForegroundCompat(buildNotification());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Log.i("ASTRA-ZSKY-TIMER", "Task timer stopped after chat handoff");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STAGE.equals(action)) {
            String next = intent.getStringExtra(EXTRA_STAGE);
            if (next != null && !next.trim().isEmpty()) stage = next.trim();
            if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime();
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        } else if (startedAt == 0L) {
            startedAt = SystemClock.elapsedRealtime();
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        }
        return START_NOT_STICKY;
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, ChatActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(this, 18771, open, flags);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("ASTRA · задача ZSky")
                .setContentText(stage)
                .setContentIntent(tap)
                .setWhen(startedAt == 0L ? SystemClock.elapsedRealtime() : startedAt)
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "ASTRA · задачи ZSky", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Время выполнения фоновой задачи ZSky");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}