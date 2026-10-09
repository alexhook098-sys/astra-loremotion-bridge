package com.astra.loremotionbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    public static volatile ScreenCaptureService instance;

    private MediaProjection projection;
    private ImageReader imageReader;

    private int width;
    private int height;
    private int density;

    private static final String CHANNEL = "astra_screen_capture";

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    public void startCapture(int resultCode, Intent data) {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        projection = manager.getMediaProjection(resultCode, data);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);

        width = metrics.widthPixels;
        height = metrics.heightPixels;
        density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
        );

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;

            try {
                image = reader.acquireLatestImage();
                if (image == null) return;

                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();

                int pixelStride = plane.getPixelStride();
                int rowStride = plane.getRowStride();
                int rowPadding = rowStride - pixelStride * width;

                Bitmap bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                );

                bitmap.copyPixelsFromBuffer(buffer);

                int actualWidth = Math.min(width, bitmap.getWidth());

                Bitmap cropped = Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        actualWidth,
                        height
                );

                bitmap.recycle();

                AstraControl.setLastScreen(cropped);

            } catch (Exception ignored) {
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, null);

        projection.createVirtualDisplay(
                "ASTRA-Screen",
                width,
                height,
                density,
                0,
                imageReader.getSurface(),
                null,
                null
        );
    }

    private void createNotificationChannel() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                    new NotificationChannel(
                            CHANNEL,
                            "ASTRA Screen Capture",
                            NotificationManager.IMPORTANCE_LOW
                    )
            );
        }
    }

    private void startForegroundSafe() {
        Notification notification =
                new Notification.Builder(this, CHANNEL)
                        .setContentTitle("ASTRA Screen Control")
                        .setContentText("Захват экрана активен")
                        .setSmallIcon(android.R.drawable.ic_menu_view)
                        .setOngoing(true)
                        .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    18766,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(18766, notification);
        }
    }

    public void activateForeground() {
        startForegroundSafe();
    }

    @Override
    public void onDestroy() {
        instance = null;

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (projection != null) {
            projection.stop();
            projection = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
