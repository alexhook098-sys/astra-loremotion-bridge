package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native receiver for ZSky Blob videos and HTTPS MP4 media URLs. */
public final class ZSkyVideoCaptureBridge {
    private static final String TAG = "ASTRA-ZSKY-CAPTURE";
    private static final long MAX_VIDEO_BYTES = 250L * 1024L * 1024L;
    private final Activity activity;
    private final Set<String> remoteDownloads =
            Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService downloadExecutor =
            Executors.newSingleThreadExecutor();
    private Uri target;
    private OutputStream output;
    private long written;

    public ZSkyVideoCaptureBridge(Activity activity) { this.activity = activity; }

    /**
     * Queue an authenticated HTTPS media download on a native worker.
     * No DownloadManager notification is shown; ChatActivity receives the
     * MediaStore URI only after the complete file has been published.
     */
    @JavascriptInterface
    public boolean downloadRemoteVideo(String videoUrl, String userAgent, String pageUrl) {
        if (videoUrl == null || videoUrl.length() > 8192) return false;
        final Uri parsed;
        try {
            parsed = Uri.parse(videoUrl);
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null)
                return false;
        } catch (Exception e) {
            return false;
        }

        if (!remoteDownloads.add(videoUrl)) return true;

        final String ua = userAgent == null ? ""
                : userAgent.substring(0, Math.min(userAgent.length(), 512));
        final String referer = pageUrl != null && pageUrl.startsWith("https://zsky.ai")
                ? pageUrl : "https://zsky.ai/create";
        try {
            downloadExecutor.execute(() -> downloadRemote(videoUrl, ua, referer));
            ZSkyTaskState.update(activity, "downloading", "Скачиваю готовое видео");
            Log.i(TAG, "HTTPS MP4 queued on native worker");
            return true;
        } catch (Exception e) {
            remoteDownloads.remove(videoUrl);
            Log.e(TAG, "Could not queue HTTPS video", e);
            return false;
        }
    }

    private void downloadRemote(String videoUrl, String userAgent, String referer) {
        HttpURLConnection connection = null;
        Uri saved = null;
        boolean published = false;
        try {
            URL url = new URL(videoUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty(
                    "Accept", "video/mp4,video/*,application/octet-stream,*/*");
            if (!userAgent.isEmpty()) connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Referer", referer);

            String cookie = CookieManager.getInstance().getCookie(videoUrl);
            if (cookie != null && !cookie.isEmpty())
                connection.setRequestProperty("Cookie", cookie);

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL)
                throw new java.io.IOException("ZSky media HTTP " + code);

            String mime = connection.getContentType();
            if (mime != null)
                mime = mime.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
            boolean mp4Path = Uri.parse(videoUrl).getPath() != null
                    && Uri.parse(videoUrl).getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".mp4");
            if (!(mime != null && (mime.startsWith("video/")
                    || mime.equals("application/octet-stream"))) && !mp4Path)
                throw new java.io.IOException("Unexpected media type: " + mime);

            long expected = connection.getContentLengthLong();
            if (expected > MAX_VIDEO_BYTES)
                throw new java.io.IOException("Video exceeds 250 MB");

            String name = safeFileName(Uri.parse(videoUrl).getLastPathSegment());
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE,
                    mime != null && mime.startsWith("video/") ? mime : "video/mp4");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/ASTRA/ZSky");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }

            saved = activity.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (saved == null) throw new java.io.IOException("MediaStore insert failed");

            long total = 0L;
            try (InputStream in = connection.getInputStream();
                 OutputStream out = activity.getContentResolver().openOutputStream(saved, "w")) {
                if (out == null) throw new java.io.IOException("MediaStore output unavailable");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_VIDEO_BYTES)
                        throw new java.io.IOException("Video exceeds 250 MB");
                    out.write(buffer, 0, count);
                }
                if (total == 0L) throw new java.io.IOException("Empty video response");
                out.flush();
            }

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                if (activity.getContentResolver().update(saved, ready, null, null) <= 0)
                    throw new java.io.IOException("MediaStore publish failed");
            }

            published = true;
            Log.i(TAG, "HTTPS MP4 saved uri=" + saved + " bytes=" + total
                    + " expected=" + expected);
            ChatActivity.onZSkyVideoReady(activity.getApplicationContext(), saved);
        } catch (Exception e) {
            Log.e(TAG, "HTTPS video download failed", e);
            ZSkyTaskState.update(activity, "failed", "Ошибка скачивания: " + e.getClass().getSimpleName());
        } finally {
            if (!published) {
                if (saved != null) {
                    try { activity.getContentResolver().delete(saved, null, null); }
                    catch (Exception ignored) {}
                }
                // Permit a later retry when the server returned an expired URL or
                // a transient network error before a MediaStore row was created.
                remoteDownloads.remove(videoUrl);
            }
            if (connection != null) connection.disconnect();
        }
    }

    private static String safeFileName(String raw) {
        String name = raw == null ? "" : raw;
        try { name = URLDecoder.decode(name, "UTF-8"); }
        catch (Exception ignored) {}
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > 100) name = name.substring(name.length() - 100);
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"))
            name = "zsky_" + System.currentTimeMillis() + ".mp4";
        if (name.length() < 5)
            name = "zsky_" + System.currentTimeMillis() + ".mp4";
        return name;
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
            Log.i(TAG, "Blob capture started");
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
            Uri savedUri = target;
            Log.i(TAG, "Blob video saved uri=" + savedUri + " bytes=" + written);
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
