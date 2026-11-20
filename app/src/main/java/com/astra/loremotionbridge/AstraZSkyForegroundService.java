package com.astra.loremotionbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

/**
 * Keeps ASTRA's ZSky job visible in Android's notification shade and provides
 * one-tap return paths to Chat and the authenticated studio.
 *
 * This service protects process lifetime and exposes job state; it does not
 * override Chromium's WebView background-rendering policy.
 */
public final class AstraZSkyForegroundService extends Service {
    private static final String CHANNEL = "astra_zsky_tasks";
    private static final int NOTIFICATION_ID = 7401;
    private static final String ACTION_UPDATE = "com.astra.loremotionbridge.ZSKY_UPDATE";
    private String currentState = "loading";
    private String currentDetail = "Подключаюсь к ZSky";

    public static void update(Context context, String state, String detail) {
        if (context == null) return;
        Intent intent = new Intent(context, AstraZSkyForegroundService.class);
        intent.setAction(ACTION_UPDATE);
        intent.putExtra("state", state == null ? "unknown" : state);
        intent.putExtra("detail", detail == null ? "" : detail);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.getApplicationContext().startForegroundService(intent);
            else context.getApplicationContext().startService(intent);
        } catch (RuntimeException e) {
            android.util.Log.w("ASTRA-ZSKY-SERVICE", "Unable to start task notification service", e);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        // Android requires foreground promotion immediately after service start.
        startInForeground(buildNotification());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            currentState = intent.getStringExtra("state") == null
                    ? currentState : intent.getStringExtra("state");
            currentDetail = intent.getStringExtra("detail") == null
                    ? currentDetail : intent.getStringExtra("detail");
        }
        Notification notification = buildNotification();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification);

        if (isTerminal(currentState)) {
            // Keep a normal, dismissible result notification after the worker exits.
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelfResult(startId);
        }
        return START_NOT_STICKY;
    }

    private void startInForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        boolean terminal = isTerminal(currentState);
        String title = "ASTRA · ZSky";
        String stage = stageLabel(currentState);
        Intent chat = new Intent(this, ChatActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent chatPending = PendingIntent.getActivity(this, 7402, chat,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent studio = new Intent(this, ZSkyWebViewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        studio.setData(android.net.Uri.parse("astra://zsky/studio"));
        PendingIntent studioPending = PendingIntent.getActivity(this, 7403, studio,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title + " · " + stage)
                .setContentText(currentDetail)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(currentDetail + (terminal ? "" : "\nГенерация выполняется в ASTRA.")))
                .setContentIntent(chatPending)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setOngoing(!terminal)
                .setAutoCancel(terminal)
                .addAction(0, "ОТКРЫТЬ ASTRA", chatPending)
                .addAction(0, "ОТКРЫТЬ ZSKY", studioPending);
        if (!terminal) b.setProgress(0, 0, true);
        return b.build();
    }

    private String stageLabel(String state) {
        if ("loading".equals(state) || "queued".equals(state)) return "ПОДКЛЮЧЕНИЕ";
        if ("prompt_entered".equals(state)) return "ПОДГОТОВКА";
        if ("rendering".equals(state) || "submitted_unconfirmed".equals(state)) return "РЕНДЕРИНГ";
        if ("result_ready".equals(state)) return "РЕЗУЛЬТАТ ГОТОВ";
        if ("downloading".equals(state)) return "СКАЧИВАНИЕ";
        if ("completed".equals(state)) return "ВИДЕО СОХРАНЕНО";
        if ("failed".equals(state) || "automation_timeout".equals(state)) return "НУЖНО ВНИМАНИЕ";
        return "ЗАДАЧА";
    }

    private boolean isTerminal(String state) {
        return "completed".equals(state) || "failed".equals(state)
                || "automation_timeout".equals(state);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "ASTRA · фоновые генерации", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Статус ZSky, скачивание видео и быстрый возврат в ASTRA");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
