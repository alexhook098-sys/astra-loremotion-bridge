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

    private static final String CHANNEL = "astra_screen_capture";
    private static final int NOTIFICATION_ID = 18766;

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private ImageReader imageReader;
    private android.hardware.display.VirtualDisplay virtualDisplay;

    private int width;
    private int height;
    private int density;

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;
        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");

        if (resultCode != -1 && data != null && projection == null) {
            startCapture(resultCode, data);
        }

        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent data) {

        MediaProjectionManager manager =
                (MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);

        projection = manager.getMediaProjection(resultCode, data);

        if (projection == null) {
            stopSelf();
            return;
        }

        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }

                projection = null;
                projectionCallback = null;

                stopSelf();
            }
        };

        projection.registerCallback(projectionCallback, null);

        DisplayMetrics metrics = new DisplayMetrics();

        WindowManager wm =
                (WindowManager) getSystemService(WINDOW_SERVICE);

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

        imageReader.setOnImageAvailableListener(
                reader -> captureLatestFrame(reader),
                null
        );

        virtualDisplay = projection.createVirtualDisplay(
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

    private void captureLatestFrame(ImageReader reader) {

        Image image = null;
        Bitmap bitmap = null;
        Bitmap cropped = null;

        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();

            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();

            int rowPadding =
                    rowStride - pixelStride * width;

            int bitmapWidth =
                    width + rowPadding / pixelStride;

            bitmap = Bitmap.createBitmap(
                    bitmapWidth,
                    height,
                    Bitmap.Config.ARGB_8888
            );

            buffer.rewind();
            bitmap.copyPixelsFromBuffer(buffer);

            cropped = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    width,
                    height
            );

            AstraControl.setLastScreen(cropped);
            cropped = null;

        } catch (Exception ignored) {

        } finally {

            if (image != null) {
                image.close();
            }

            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }

            if (cropped != null && !cropped.isRecycled()) {
                cropped.recycle();
            }
        }
    }

    private Notification buildNotification() {

        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("ASTRA Screen Control")
                .setContentText("Захват экрана активен")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager nm =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

        nm.createNotificationChannel(
                new NotificationChannel(
                        CHANNEL,
                        "ASTRA Screen Capture",
                        NotificationManager.IMPORTANCE_LOW
                )
        );
    }

    private void cleanupCapture() {

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (projection != null) {
            if (projectionCallback != null) {
                projection.unregisterCallback(projectionCallback);
                projectionCallback = null;
            }

            projection.stop();
            projection = null;
        }
    }

    @Override
    public void onDestroy() {

        cleanupCapture();

        instance = null;

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
