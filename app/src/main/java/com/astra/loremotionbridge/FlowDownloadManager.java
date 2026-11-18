package com.astra.loremotionbridge;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Downloads the actual media asset from a Google Flow project.
 *
 * Flow's /asb/ URL exposed by the HTMLMediaElement is a page/preview rendition,
 * not the downloadable MP4. Resolve the media ID from projectInitialData, ask
 * media.getMediaUrlRedirect for a fresh signed CDN URL, then stream that URL
 * directly into MediaStore. The Google session cookie is used only for the
 * labs.google API request; it is never sent to the signed CDN URL.
 */
public final class FlowDownloadManager {
    private static final String FLOW_API = "https://labs.google/fx/api/trpc/";
    private static final String FLOW_CDN_HOST = "flow-content.google";
    private static final long MAX_VIDEO_BYTES = 250L * 1024L * 1024L;
    private static final int MAX_PROJECT_JSON_BYTES = 16 * 1024 * 1024;

    public interface ProgressListener {
        void onProgress(String message);
    }

    private static final class MediaCandidate {
        final String id;
        final String name;
        final long timestamp;
        final int order;

        MediaCandidate(String id, String name, long timestamp, int order) {
            this.id = id;
            this.name = name;
            this.timestamp = timestamp;
            this.order = order;
        }
    }

    private FlowDownloadManager() {}

    public static Uri downloadLatestProjectVideo(
            Context context,
            String projectId,
            String pageUrl,
            String userAgent,
            ProgressListener progress) throws Exception {
        if (context == null) throw new IOException("Контекст ASTRA недоступен");
        if (projectId == null || !projectId.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("Не удалось определить ID проекта Google Flow");
        }

        report(progress, "Ищу готовое видео в проекте Flow…");
        CookieManager cookieManager = CookieManager.getInstance();
        String cookies = cookieManager.getCookie("https://labs.google");
        if (cookies == null || cookies.trim().isEmpty()) {
            throw new IOException("Нет сессии labs.google. Открой Flow в ASTRA и войди в тот же Google-аккаунт");
        }

        JSONObject projectData = fetchProjectInitialData(projectId, pageUrl, userAgent, cookies);
        List<MediaCandidate> candidates = collectCandidates(projectData, projectId);
        if (candidates.isEmpty()) {
            throw new IOException("Flow API вернул проект без media ID. Не найдено, что скачивать");
        }

        report(progress, "Найдено материалов: " + candidates.size() + ". Определяю MP4…");
        int checked = 0;
        for (MediaCandidate candidate : candidates) {
            checked++;
            report(progress, "Проверяю материал " + checked + "/" + candidates.size() + "…");
            String signedVideoUrl = resolveVideoUrl(candidate.id, pageUrl, userAgent, cookies);
            if (signedVideoUrl == null) continue;

            report(progress, "Ссылка на MP4 получена. Начинаю скачивание…");
            return streamVideoToMediaStore(context, signedVideoUrl, candidate.name, userAgent, progress);
        }

        throw new IOException("В проекте найдено " + candidates.size()
                + " материалов, но Flow не вернул ни одного видео MP4. Возможно, открыт не тот проект");
    }

    private static JSONObject fetchProjectInitialData(
            String projectId, String pageUrl, String userAgent, String cookies) throws Exception {
        String input = URLEncoder.encode("{\"json\":{\"projectId\":\"" + projectId + "\"}}", "UTF-8");
        URL url = new URL(FLOW_API + "flow.projectInitialData?input=" + input);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json,*/*");
            connection.setRequestProperty("Cookie", cookies);
            connection.setRequestProperty("Referer", safeReferer(projectId, pageUrl));
            if (userAgent != null && !userAgent.isEmpty()) {
                connection.setRequestProperty("User-Agent", userAgent);
            }
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) {
                throw new IOException("Google Flow отклонил сессию (HTTP " + code + "). Перезайди в Google-аккаунт в Flow");
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Flow projectInitialData: HTTP " + code);
            }
            String body = readLimited(connection.getInputStream(), MAX_PROJECT_JSON_BYTES);
            return new JSONObject(body);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static List<MediaCandidate> collectCandidates(JSONObject root, String projectId) {
        Map<String, MediaCandidate> byId = new HashMap<>();
        int[] order = {0};
        walk(root, projectId, byId, order);
        List<MediaCandidate> result = new ArrayList<>(byId.values());
        Collections.sort(result, new Comparator<MediaCandidate>() {
            @Override public int compare(MediaCandidate a, MediaCandidate b) {
                int byTime = Long.compare(b.timestamp, a.timestamp);
                return byTime != 0 ? byTime : Integer.compare(b.order, a.order);
            }
        });
        if (result.size() > 100) return new ArrayList<>(result.subList(0, 100));
        return result;
    }

    private static void walk(Object value, String projectId,
                             Map<String, MediaCandidate> byId, int[] order) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) walk(array.opt(i), projectId, byId, order);
            return;
        }
        if (!(value instanceof JSONObject)) return;

        JSONObject object = (JSONObject) value;
        JSONObject metadata = object.optJSONObject("metadata");
        if (metadata != null) {
            addCandidate(metadata.optString("primaryMediaId", ""),
                    metadata.optString("displayName", ""),
                    firstTimestamp(metadata, object), projectId, byId, order);
        } else {
            addCandidate(object.optString("name", ""),
                    object.optString("displayName", ""),
                    timestamp(object), projectId, byId, order);
        }

        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            walk(object.opt(key), projectId, byId, order);
        }
    }

    private static void addCandidate(String rawId, String rawName, long timestamp,
                                     String projectId, Map<String, MediaCandidate> byId, int[] order) {
        if (rawId == null || !rawId.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || rawId.equalsIgnoreCase(projectId)) return;
        int position = ++order[0];
        String name = rawName == null || rawName.trim().isEmpty() ? rawId : rawName.trim();
        MediaCandidate old = byId.get(rawId);
        if (old == null || timestamp >= old.timestamp) {
            byId.put(rawId, new MediaCandidate(rawId, name, timestamp, position));
        }
    }

    private static long firstTimestamp(JSONObject primary, JSONObject fallback) {
        long value = timestamp(primary);
        return value != 0L ? value : timestamp(fallback);
    }

    private static long timestamp(JSONObject object) {
        if (object == null) return 0L;
        String[] fields = {"updateTime", "updatedAt", "createTime", "createdAt", "timestamp"};
        for (String field : fields) {
            Object raw = object.opt(field);
            if (raw instanceof Number) {
                long n = ((Number) raw).longValue();
                return n > 0L && n < 100000000000L ? n * 1000L : n;
            }
            if (raw instanceof String) {
                String text = ((String) raw).trim();
                if (text.isEmpty()) continue;
                try {
                    long n = Long.parseLong(text);
                    return n > 0L && n < 100000000000L ? n * 1000L : n;
                } catch (Exception ignored) {}
                String[] formats = {
                    "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                    "yyyy-MM-dd'T'HH:mm:ssX",
                    "yyyy-MM-dd HH:mm:ss"
                };
                for (String format : formats) {
                    try {
                        SimpleDateFormat parser = new SimpleDateFormat(format, Locale.US);
                        parser.setTimeZone(TimeZone.getTimeZone("UTC"));
                        Date parsed = parser.parse(text);
                        if (parsed != null) return parsed.getTime();
                    } catch (Exception ignored) {}
                }
            }
        }
        return 0L;
    }

    /**
     * Resolve an ID through Flow's authenticated redirect endpoint. The returned
     * Location is signed and short-lived, so it is consumed immediately.
     */
    private static String resolveVideoUrl(
            String mediaId, String pageUrl, String userAgent, String cookies) throws Exception {
        String name = URLEncoder.encode(mediaId, "UTF-8");
        URL endpoint = new URL(FLOW_API + "media.getMediaUrlRedirect?name=" + name);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "video/mp4,*/*");
            connection.setRequestProperty("Cookie", cookies);
            connection.setRequestProperty("Referer", safeRefererFromUrl(pageUrl));
            if (userAgent != null && !userAgent.isEmpty()) {
                connection.setRequestProperty("User-Agent", userAgent);
            }
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) {
                throw new IOException("Flow не разрешил получить ссылку на файл (HTTP " + code + "). Проверь вход в Google");
            }
            if (code == 404 || code == 400) return null;
            if (code < 300 || code >= 400) {
                if (code == 200) {
                    String mime = connection.getContentType();
                    if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/")) {
                        return endpoint.toString();
                    }
                    return null;
                }
                throw new IOException("media.getMediaUrlRedirect: HTTP " + code);
            }
            String location = connection.getHeaderField("Location");
            if (location == null || location.trim().isEmpty()) {
                throw new IOException("Flow вернул редирект без Location");
            }
            URL target = new URL(endpoint, location);
            String host = target.getHost();
            String path = target.getPath();
            if (!"https".equalsIgnoreCase(target.getProtocol())
                    || !FLOW_CDN_HOST.equalsIgnoreCase(host)
                    || path == null) {
                throw new IOException("Flow вернул неожиданный адрес CDN: " + host);
            }
            if (!path.startsWith("/video/")) return null;
            return target.toString();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Uri streamVideoToMediaStore(
            Context context, String signedUrl, String displayName,
            String userAgent, ProgressListener progress) throws Exception {
        HttpURLConnection connection = null;
        Uri target = null;
        boolean published = false;
        try {
            URL url = new URL(signedUrl);
            if (!"https".equalsIgnoreCase(url.getProtocol())
                    || !FLOW_CDN_HOST.equalsIgnoreCase(url.getHost())
                    || url.getPath() == null || !url.getPath().startsWith("/video/")) {
                throw new IOException("Получена недопустимая ссылка на видео");
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(90000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "video/mp4,*/*");
            if (userAgent != null && !userAgent.isEmpty()) {
                connection.setRequestProperty("User-Agent", userAgent);
            }
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("CDN отклонил подписанную ссылку (HTTP " + code + ")");
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("CDN не отдал видео: HTTP " + code);
            }
            String contentType = connection.getContentType();
            String normalizedType = contentType == null ? "" :
                    contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!normalizedType.startsWith("video/")
                    && !"application/octet-stream".equals(normalizedType)
                    && !"binary/octet-stream".equals(normalizedType)) {
                throw new IOException("Вместо MP4 Flow вернул " + (contentType == null ? "неизвестный тип" : contentType));
            }
            long expected = connection.getContentLengthLong();
            if (expected > MAX_VIDEO_BYTES) throw new IOException("Видео больше допустимых 250 МБ");

            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, safeFileName(displayName));
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/ASTRA/Flow");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }
            target = context.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new IOException("Android не создал файл в Movies/ASTRA/Flow");

            long written = 0L;
            long lastUiAt = 0L;
            int lastPercent = -1;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = context.getContentResolver().openOutputStream(target, "w")) {
                if (output == null) throw new IOException("Android не открыл файл для записи");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    written += count;
                    if (written > MAX_VIDEO_BYTES) throw new IOException("Видео превысило лимит 250 МБ");
                    output.write(buffer, 0, count);

                    long now = android.os.SystemClock.elapsedRealtime();
                    int percent = expected > 0L ? (int) Math.min(100L, (written * 100L) / expected) : -1;
                    if (now - lastUiAt >= 450L || (percent >= 0 && percent != lastPercent)) {
                        lastUiAt = now;
                        lastPercent = percent;
                        String downloaded = formatMb(written);
                        String total = expected > 0L ? " / " + formatMb(expected) : "";
                        String percentage = percent >= 0 ? " · " + percent + "%" : "";
                        report(progress, "Скачиваю: " + downloaded + total + percentage);
                    }
                }
                output.flush();
            }

            if (written == 0L) throw new IOException("Flow вернул пустой MP4-файл");
            if (expected > 0L && written != expected) {
                throw new IOException("Загрузка оборвалась: получено " + formatMb(written)
                        + " из " + formatMb(expected));
            }

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                if (context.getContentResolver().update(target, ready, null, null) <= 0) {
                    throw new IOException("Файл скачан, но Android не смог опубликовать его в галерее");
                }
            }
            published = true;
            report(progress, "Проверяю файл… " + formatMb(written));
            return target;
        } finally {
            if (!published && target != null) {
                try { context.getContentResolver().delete(target, null, null); }
                catch (Exception ignored) {}
            }
            if (connection != null) connection.disconnect();
        }
    }

    private static String safeFileName(String raw) {
        String base = raw == null ? "" : raw.trim();
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ");
        if (base.length() > 70) base = base.substring(0, 70).trim();
        if (base.isEmpty()) base = "flow_" + System.currentTimeMillis();
        if (!base.toLowerCase(Locale.ROOT).endsWith(".mp4")) base += ".mp4";
        return base;
    }

    private static String safeReferer(String projectId, String pageUrl) {
        if (pageUrl != null && pageUrl.startsWith("https://")) return pageUrl;
        return "https://labs.google/fx/tools/flow/project/" + projectId;
    }

    private static String safeRefererFromUrl(String pageUrl) {
        if (pageUrl != null && pageUrl.startsWith("https://")) return pageUrl;
        return "https://labs.google/fx/tools/flow/";
    }

    private static String readLimited(InputStream input, int limit) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int count;
            int total = 0;
            while ((count = reader.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IOException("Ответ Flow projectInitialData слишком большой");
                out.append(buffer, 0, count);
            }
        }
        return out.toString();
    }

    private static String formatMb(long bytes) {
        return String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0));
    }

    private static void report(ProgressListener progress, String message) {
        if (progress != null) progress.onProgress(message);
    }
}
