package com.astra.loremotionbridge;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** Minimal verified MP4 downloader ported from the previous Alice integration. */
public final class AliceMediaAgent {
    private static final long MAX_BYTES = 100L * 1024L * 1024L;
    private AliceMediaAgent() {}

    public static String download(Context context, String rawId) {
        if (context == null) return error("context_unavailable");
        String id = rawId == null ? "" : rawId.trim();
        if (!id.matches("[0-9a-fA-F]{32}")) return error("invalid_video_id");
        HttpURLConnection connection = null;
        File temp = null;
        Uri published = null;
        String stage = "initialize";
        try {
            URL url = new URL("https://yaart-images.s3.yandex.net/webalice/" + id.toLowerCase(Locale.US));
            if (!"https".equals(url.getProtocol()) || !"yaart-images.s3.yandex.net".equalsIgnoreCase(url.getHost()))
                return error("untrusted_source");
            stage = "open_remote_connection";
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            stage = "read_remote_response";
            int code = connection.getResponseCode();
            String mime = connection.getContentType();
            long expected = connection.getContentLengthLong();
            if (code != HttpURLConnection.HTTP_OK) return error("http_" + code);
            if (mime == null || !mime.toLowerCase(Locale.US).startsWith("video/mp4")) return error("unexpected_content_type");
            if (expected <= 0 || expected > MAX_BYTES) return error("invalid_content_length");
            stage = "create_cache_file";
            temp = File.createTempFile("alice_" + id + "_", ".mp4", context.getCacheDir());
            long total = 0;
            stage = "download_remote_mp4";
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[16384]; int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES || total > expected) throw new java.io.IOException("download_size_limit");
                    out.write(buffer, 0, count);
                }
                out.flush();
            }
            stage = "verify_download";
            if (total != expected || total < 12) return error("incomplete_download");
            byte[] header = new byte[12];
            try (FileInputStream in = new FileInputStream(temp)) {
                if (in.read(header) != header.length) return error("invalid_mp4_header");
            }
            if (header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p')
                return error("invalid_mp4_signature");
            stage = "prepare_media_store";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "ASTRA_ALICE_" + id + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ASTRA/Alice");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }
            stage = "insert_media_store_record";
            published = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (published == null) return error("media_store_insert_failed");
            stage = "open_media_store_output";
            try (InputStream in = new FileInputStream(temp);
                 OutputStream out = context.getContentResolver().openOutputStream(published, "w")) {
                if (out == null) throw new java.io.IOException("media_store_stream_unavailable");
                byte[] buffer = new byte[16384]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
            }
            stage = "publish_media_store_item";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                context.getContentResolver().update(published, ready, null, null);
            }
            return new JSONObject().put("ok", true).put("id", id.toLowerCase(Locale.US))
                    .put("uri", published.toString()).put("mime", "video/mp4")
                    .put("bytes", total).put("path", "Movies/ASTRA/Alice").toString();
        } catch (Exception e) {
            if (published != null) try { context.getContentResolver().delete(published, null, null); } catch (Exception ignored) {}
            String detail = e.getMessage();
            if (detail != null) detail = detail.replaceAll("[\\r\\n\\t]", " ");
            return error("download_failed:" + stage + ":" + e.getClass().getSimpleName() + (detail == null ? "" : ":" + detail));
        } finally {
            if (connection != null) connection.disconnect();
            if (temp != null && temp.exists()) temp.delete();
        }
    }
    private static String error(String value) {
        try { return new JSONObject().put("ok", false).put("error", value).toString(); }
        catch (Exception ignored) { return "{\"ok\":false,\"error\":\"unknown\"}"; }
    }
}