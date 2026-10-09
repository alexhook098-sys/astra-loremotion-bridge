package com.astra.loremotionbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
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
    private VirtualDisplay virtualDisplay;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private int width, height, density;
    private volatile boolean frameReceived;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            AstraControl.setCaptureError("Service restarted without projection permission data");
            stopSelf();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = getProjectionData(intent);
        if (resultCode != -1 && data != null && projection == null) {
            try {
                startCapture(resultCode, data);
            } catch (Exception e) {
                AstraControl.setCaptureError(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
                stopSelf();
            }
        } else if (projection == null) {
            AstraControl.setCaptureError("Missing MediaProjection permission result/data");
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getProjectionData(Intent source) {
        if (Build.VERSION.SDK_INT >= 33) return source.getParcelableExtra("data", Intent.class);
        return source.getParcelableExtra("data");
    }

    private void startCapture(int resultCode, Intent data) {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) throw new IllegalStateException("MediaProjectionManager unavailable");
        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) throw new IllegalStateException("getMediaProjection returned null");
        captureThread = new HandlerThread("ASTRA-Capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        projectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() {
                AstraControl.setCaptureError("MediaProjection was stopped by Android or user");
                releaseDisplayAndReader();
                projection = null;
                stopSelf();
            }
        };
        projection.registerCallback(projectionCallback, captureHandler);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) throw new IllegalStateException("WindowManager unavailable");
        wm.getDefaultDisplay().getRealMetrics(metrics);
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        density = metrics.densityDpi;
        if (width <= 0 || height <= 0 || density <= 0) throw new IllegalStateException("Invalid display metrics");

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(this::captureLatestFrame, captureHandler);
        virtualDisplay = projection.createVirtualDisplay("ASTRA-Screen", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
        if (virtualDisplay == null) throw new IllegalStateException("createVirtualDisplay returned null");
        AstraControl.setCaptureError("VirtualDisplay created; waiting for first frame");
        captureHandler.postDelayed(() -> {
            if (!frameReceived && projection != null) {
                AstraControl.setCaptureError("No frame after 5s. Android did not deliver ImageReader frames.");
            }
        }, 5000);
    }

    private void captureLatestFrame(ImageReader reader) {
        Image image = null;
        Bitmap padded = null;
        Bitmap cropped = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) throw new IllegalStateException("Image has no planes");
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            if (pixelStride <= 0 || rowStride < pixelStride * width) {
                throw new IllegalStateException("Invalid pixel/row stride: " + pixelStride + "/" + rowStride);
            }
            int rowPadding = rowStride - pixelStride * width;
            int bitmapWidth = width + rowPadding / pixelStride;
            buffer.rewind();
            padded = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            AstraControl.setLastScreen(cropped);
            cropped = null;
            frameReceived = true;
        } catch (Exception e) {
            AstraControl.setCaptureError("Frame error: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        } finally {
            if (image != null) image.close();
            if (padded != null && !padded.isRecycled()) padded.recycle();
            if (cropped != null && !cropped.isRecycled()) cropped.recycle();
        }
    }

    private void releaseDisplayAndReader() {
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("ASTRA Screen Control")
                .setContentText("Захват экрана активен")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true).build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "ASTRA Screen Capture", NotificationManager.IMPORTANCE_LOW));
    }

    private void cleanupCapture() {
        releaseDisplayAndReader();
        if (projection != null) {
            if (projectionCallback != null) {
                try { projection.unregisterCallback(projectionCallback); } catch (Exception ignored) {}
                projectionCallback = null;
            }
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
    }

    @Override public void onDestroy() {
        cleanupCapture();
        instance = null;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
