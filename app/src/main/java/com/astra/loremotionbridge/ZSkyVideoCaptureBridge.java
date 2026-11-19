package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.ContentValues;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import java.io.OutputStream;

/** Native receiver for ZSky page-owned Blob videos. */
public final class ZSkyVideoCaptureBridge {
    private static final String TAG = "ASTRA-ZSKY-CAPTURE";
    private static final long MAX_VIDEO_BYTES = 250L * 1024L * 1024L;
    private final Activity activity;
    private android.net.Uri target;
    private OutputStream output;
    private long written;

    public ZSkyVideoCaptureBridge(Activity activity) { this.activity = activity; }

    @JavascriptInterface
    public boolean downloadRemoteVideo(String url, String userAgent, String referer) {
        try {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://")))
                return false;
            android.app.DownloadManager manager =
                    (android.app.DownloadManager) activity.getSystemService(Activity.DOWNLOAD_SERVICE);
            if (manager == null) return false;
            String name = "zsky_" + System.currentTimeMillis() + ".mp4";
            android.app.DownloadManager.Request request =
                    new android.app.DownloadManager.Request(android.net.Uri.parse(url));
            request.setTitle("ASTRA ZSky video");
            request.setDescription("Downloading generated video");
            request.setMimeType("video/mp4");
            request.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_MOVIES, "ASTRA/ZSky/" + name);
            if (userAgent != null && !userAgent.isEmpty())
                request.addRequestHeader("User-Agent", userAgent);
            if (referer != null && (referer.startsWith("https://") || referer.startsWith("http://")))
                request.addRequestHeader("Referer", referer);
            long id = manager.enqueue(request);
            Log.i(TAG, "remote HTTPS video queued id=" + id);
            return id > 0;
        } catch (Exception e) {
            Log.e(TAG, "downloadRemoteVideo failed", e);
            return false;
        }
    }

    @JavascriptInterface
    public synchronized boolean beginVideo(String mime, String fileName) {
        try {
            if (output != null) return false;
            String cleanMime = (mime != null && mime.startsWith("video/"))
                    ? mime.split(";")[0] : "video/mp4";
            String safeName = (fileName != null && fileName.matches("[A-Za-z0-9_.-]+"))
                    ? fileName : "zsky_" + System.currentTimeMillis() + ".mp4";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, safeName);
            values.put(MediaStore.Video.Media.MIME_TYPE, cleanMime);
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/ASTRA/ZSky");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }
            target = activity.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new java.io.IOException("MediaStore insert failed");
            output = activity.getContentResolver().openOutputStream(target, "w");
            if (output == null) throw new java.io.IOException("MediaStore output unavailable");
            written = 0L;
            Log.i(TAG, "capture started");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "beginVideo failed", e);
            cleanup(true);
            return false;
        }
    }

    @JavascriptInterface
    public synchronized boolean appendChunk(String base64) {
        try {
            if (output == null || base64 == null) return false;
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            if (written + bytes.length > MAX_VIDEO_BYTES)
                throw new java.io.IOException("video exceeds 250 MB");
            output.write(bytes);
            written += bytes.length;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "appendChunk failed", e);
            cleanup(true);
            return false;
        }
    }

    @JavascriptInterface
    public synchronized boolean finishVideo() {
        try {
            if (output == null || target == null || written == 0L)
                throw new java.io.IOException("empty video");
            output.flush();
            output.close();
            output = null;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                if (activity.getContentResolver().update(target, ready, null, null) <= 0)
                    throw new java.io.IOException("MediaStore publish failed");
            }
            android.net.Uri savedUri = target;
            Log.i(TAG, "video saved uri=" + savedUri + " bytes=" + written);
            target = null;
            written = 0L;
            ChatActivity.onZSkyVideoReady(activity.getApplicationContext(), savedUri);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "finishVideo failed", e);
            cleanup(true);
            return false;
        }
    }

    @JavascriptInterface
    public synchronized void failVideo(String reason) {
        Log.w(TAG, "page capture failed: " + reason);
        cleanup(true);
    }

    public synchronized void abortVideo() { cleanup(true); }

    private void cleanup(boolean deleteTarget) {
        try { if (output != null) output.close(); } catch (Exception ignored) {}
        output = null;
        if (deleteTarget && target != null) {
            try { activity.getContentResolver().delete(target, null, null); }
            catch (Exception ignored) {}
        }
        target = null;
        written = 0L;
    }
}
