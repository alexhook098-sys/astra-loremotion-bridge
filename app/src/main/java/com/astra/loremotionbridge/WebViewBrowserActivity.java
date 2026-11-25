package com.astra.loremotionbridge;

import android.util.Log;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebViewBrowserActivity extends Activity {
    private static volatile WebViewBrowserActivity instance;
    private WebView webView;
    private boolean hideAfterPageLoad;
    private TextView status;
    private static final int FILE_CHOOSER_REQUEST_CODE = 7401;
    private ValueCallback<Uri[]> pendingFileCallback;
    private String pendingUploadPath;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService perchanceDownloads = Executors.newSingleThreadExecutor();
    private final Set<String> seenPerchanceImages = new HashSet<>();
    private boolean aliceAutomationStarted;
    private boolean aliceUploadTriggered;
    private boolean aliceWarmupDelayScheduled;
    private boolean aliceResultObserverRunning;
    private boolean aliceDownloadStarted;
    private boolean aliceFileChooserRequested;
    private boolean aliceFileDeliveryCompleted;
    private String aliceUploadError = "";
    private final ExecutorService aliceDownloads = Executors.newSingleThreadExecutor();
    private String aliceAutomationImagePath;
    private String aliceAutomationPrompt;
    private boolean perchanceObserverRunning;
    private final Runnable perchanceObserver = new Runnable() {
        @Override public void run() {
            if (webView == null || isFinishing()) { perchanceObserverRunning = false; return; }
            String url = webView.getUrl();
            if (isPerchanceUrl(url)) scanPerchanceImages();
            else { perchanceObserverRunning = false; return; }
            main.postDelayed(this, 2500L);
        }
    };

    public static WebViewBrowserActivity getInstance() { return instance; }

    @Override protected void onCreate(Bundle state) {
        Log.i("ASTRA-WV-DIAG", "Activity onCreate entered");
        super.onCreate(state);
        instance = this;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setText("ASTRA WebView: starting...");
        status.setPadding(24, 18, 24, 18);
        root.addView(status);

        webView = new WebView(this);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        configure(webView);

        String initial = getIntent().getStringExtra("url");
        if (initial == null || initial.isEmpty()) initial = "about:blank";
        hideAfterPageLoad = getIntent().getBooleanExtra("background", false);
        if (getIntent().getBooleanExtra("alice_automate", false)) {
            aliceAutomationImagePath = getIntent().getStringExtra("alice_image_path");
            aliceAutomationPrompt = getIntent().getStringExtra("alice_prompt");
            setStatus("Alice Motion: открываю студию…");
        }
        Log.i("ASTRA-WV-DIAG", "Activity loading URL=" + initial + " background=" + hideAfterPageLoad);
        webView.loadUrl(initial);
        if (hideAfterPageLoad) {
            hideAfterPageLoad = false;
            sendToBackSoon();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST_CODE) return;

        if (pendingFileCallback == null) return;

        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[] { data.getData() };
            }
        }

        pendingFileCallback.onReceiveValue(results);
        pendingFileCallback = null;
        Log.i("ASTRA-WV", results == null
                ? "File picker cancelled"
                : "File picker returned " + results.length + " URI(s)");
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("alice_automate", false)) {
            // singleTask activities receive subsequent requests here, not in onCreate().
            aliceAutomationImagePath = intent.getStringExtra("alice_image_path");
            aliceAutomationPrompt = intent.getStringExtra("alice_prompt");
            aliceAutomationStarted = false;
            aliceUploadTriggered = false;
            aliceWarmupDelayScheduled = false;
            aliceResultObserverRunning = false;
            aliceDownloadStarted = false;
            aliceFileChooserRequested = false;
            aliceFileDeliveryCompleted = false;
            aliceUploadError = "";
            pendingUploadPath = null;
            if (pendingFileCallback != null) {
                try { pendingFileCallback.onReceiveValue(null); } catch (Exception ignored) {}
                pendingFileCallback = null;
            }
            hideAfterPageLoad = false;
            setStatus("Alice Motion: новое задание принято, перезапускаю последовательность…");
        }
        String url = intent.getStringExtra("url");
        if (url != null && !url.isEmpty() && webView != null && !"about:blank".equals(url)) {
            webView.stopLoading();
            webView.loadUrl(url);
        }
    }

    private void sendToBackSoon() {
        main.postDelayed(() -> { if (!isFinishing()) { try { moveTaskToBack(true); } catch (Exception ignored) {} } }, 250L);
    }

    @Override public void onBackPressed() {
        String current = webView == null ? "" : webView.getUrl();
        boolean perchance = false;
        try {
            perchance = current != null && "perchance.org".equalsIgnoreCase(
                    android.net.Uri.parse(current).getHost());
        } catch (Exception ignored) {}
        if (perchance) {
            try {
                moveTaskToBack(true);
                Log.i("ASTRA-WV-DIAG", "Perchance kept alive in background; URL=" + current);
            } catch (Exception e) {
                Log.e("ASTRA-WV-DIAG", "Could not background Perchance task", e);
                super.onBackPressed();
            }
            return;
        }
        super.onBackPressed();
    }

    private boolean isPerchanceUrl(String value) {
        try {
            String host = Uri.parse(value == null ? "" : value).getHost();
            return host != null && (host.equalsIgnoreCase("perchance.org")
                    || host.toLowerCase(Locale.US).endsWith(".perchance.org"));
        } catch (Exception ignored) { return false; }
    }

    private void startPerchanceObserver() {
        if (perchanceObserverRunning) return;
        perchanceObserverRunning = true;
        main.removeCallbacks(perchanceObserver);
        main.post(perchanceObserver);
        Log.i("ASTRA-PERCHANCE", "Result observer started");
    }

    private void stopPerchanceObserver() {
        perchanceObserverRunning = false;
        main.removeCallbacks(perchanceObserver);
    }

    // Observe rendered result images without interacting with the generator UI.
    private void scanPerchanceImages() {
        if (webView == null) return;
        String script = "(function(){var a=[...document.images].filter(function(i){"
                + "return i.complete&&i.naturalWidth>=256&&i.naturalHeight>=256&&i.src&&"
                + "i.src.indexOf('https://')===0;}).map(function(i){return i.currentSrc||i.src;});"
                + "return JSON.stringify(Array.from(new Set(a)));})()";
        webView.evaluateJavascript(script, raw -> {
            if (raw == null || "null".equals(raw)) return;
            try {
                Object decoded = new org.json.JSONTokener(raw).nextValue();
                if (!(decoded instanceof String)) return;
                org.json.JSONArray urls = new org.json.JSONArray((String) decoded);
                for (int i = 0; i < urls.length(); i++) {
                    String imageUrl = urls.optString(i, "");
                    if (imageUrl.isEmpty() || !seenPerchanceImages.add(imageUrl)) continue;
                    perchanceDownloads.execute(() -> downloadPerchanceImage(imageUrl));
                }
            } catch (Exception e) {
                Log.w("ASTRA-PERCHANCE", "Could not parse generated image list", e);
            }
        });
    }

    private void downloadPerchanceImage(String imageUrl) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(imageUrl);
            if (!"https".equalsIgnoreCase(url.getProtocol())) return;
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            String cookie = CookieManager.getInstance().getCookie(imageUrl);
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            int code = connection.getResponseCode();
            String mime = connection.getContentType();
            if (code < 200 || code >= 300 || mime == null || !mime.toLowerCase(Locale.US).startsWith("image/")) return;
            int length = connection.getContentLength();
            if (length > 20 * 1024 * 1024) return;
            ContentValues values = new ContentValues();
            String ext = mime.toLowerCase(Locale.US).contains("png") ? ".png"
                    : mime.toLowerCase(Locale.US).contains("webp") ? ".webp" : ".jpg";
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    "ASTRA_PERCHANCE_" + System.currentTimeMillis() + ext);
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime.split(";")[0].trim());
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/ASTRA/Perchance");
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri target = getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (target == null) return;
            boolean complete = false;
            try (InputStream in = connection.getInputStream();
                 OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                if (out == null) throw new IOException("gallery_stream_unavailable");
                byte[] buffer = new byte[16384];
                int total = 0, count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > 20 * 1024 * 1024) throw new IOException("image_too_large");
                    out.write(buffer, 0, count);
                }
                complete = total > 0;
            } finally {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues done = new ContentValues();
                    done.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                    if (complete) getContentResolver().update(target, done, null, null);
                    else getContentResolver().delete(target, null, null);
                } else if (!complete) getContentResolver().delete(target, null, null);
            }
            if (complete) {
                Log.i("ASTRA-PERCHANCE", "Generated image saved: " + target);
                ChatActivity.onPerchanceImageReady(target);
            }
        } catch (Exception e) {
            Log.w("ASTRA-PERCHANCE", "Image capture failed: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void configure(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(wv, true);

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {

                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }

                pendingFileCallback = filePathCallback;
                if (getIntent().getBooleanExtra("alice_automate", false)) {
                    aliceFileChooserRequested = true;
                    setStatus("Alice Motion: Алиса открыла системный выбор файла WebView.");
                }

                if (pendingUploadPath != null && !pendingUploadPath.isEmpty()) {
                    deliverPendingUpload();
                    return true;
                }

                // Launch Android's document picker and return the selected URI to WebView.
                try {
                    Intent chooserIntent = fileChooserParams.createIntent();
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception firstError) {
                    try {
                        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                        fallback.addCategory(Intent.CATEGORY_OPENABLE);
                        fallback.setType("*/*");
                        startActivityForResult(
                                Intent.createChooser(fallback, "Выберите файл"),
                                FILE_CHOOSER_REQUEST_CODE);
                    } catch (Exception secondError) {
                        Log.e("ASTRA-WV", "Unable to open file picker", secondError);
                        if (pendingFileCallback != null) {
                            pendingFileCallback.onReceiveValue(null);
                            pendingFileCallback = null;
                        }
                        setStatus("Не удалось открыть выбор файла");
                    }
                }

                return true;
            }
        });
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return false;
            }

            @Override public void onPageFinished(WebView view, String url) {
                Log.i("ASTRA-WV-DIAG", "onPageFinished url=" + url);
                updateStatus();
                if (getIntent().getBooleanExtra("alice_automate", false)) scheduleAliceAutomation(0);
                if (hideAfterPageLoad) {
                    hideAfterPageLoad = false;
                    sendToBackSoon();
                }
            }
        });

        wv.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(
                    String url, String userAgent, String contentDisposition,
                    String mimeType, long contentLength) {
                // Alice Media's no-watermark download action exposes a page-owned blob URL.
                // Fetch it inside the same WebView origin; Android DownloadManager cannot fetch blob: URLs.
                if (url != null && url.startsWith("blob:https://alice.yandex.ru/")) {
                    setStatus("Сохраняю оригинальное видео Алисы...");
                    saveAliceVideoBlob(url, (savedName, error) -> {
                        if (error != null) {
                            setStatus("Ошибка сохранения видео Алисы: " + error);
                        } else {
                            setStatus("Видео без водяного знака сохранено: " + savedName);
                        }
                    });
                    return;
                }

                // ASTRA_BING_DOWNLOAD_LISTENER_V1: handle Bing blob and HTTPS image URLs.
                if (url != null && (url.startsWith("blob:https://www.bing.com/") || url.startsWith("https://th.bing.com/th/id/"))) {
                    setStatus("Saving Bing image...");
                    saveBingImage(url, (value, error) -> {
                        if (error != null) {
                            setStatus("Bing image save error: " + error);
                            return;
                        }
                        try {
                            org.json.JSONObject info = new org.json.JSONObject(value);
                            String name = info.getString("name");
                            String mime = info.getString("type");
                            if (!name.matches("[0-9a-f]{32}\\.(png|jpg|webp)")) {
                                throw new IOException("invalid_bing_cache_name");
                            }
                            File cacheDir = new File(getCacheDir(), "bing_images");
                            File source = new File(cacheDir, name);
                            String saved = publishBingImageToDownloads(source, mime);
                            setStatus("Bing image saved to Downloads: " + new File(saved).getName());
                        } catch (Exception e) {
                            setStatus("Bing image download error: " + e.getMessage());
                        }
                    });
                    return;
                }
                // Existing non-Bing downloads, including LoreMotion, remain unchanged.
                try {
                    String cookie = CookieManager.getInstance().getCookie(url);
                    DownloadManager.Request req =
                            new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimeType != null ? mimeType : "video/mp4");
                    req.setTitle("ASTRA LoreMotion video");
                    req.setDescription("Downloading generated video");
                    req.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setAllowedOverMetered(true);
                    req.setAllowedOverRoaming(true);
                    if (userAgent != null)
                        req.addRequestHeader("User-Agent", userAgent);
                    if (cookie != null)
                        req.addRequestHeader("Cookie", cookie);
                    req.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            "loremotion-" + System.currentTimeMillis() + ".mp4");

                    DownloadManager dm =
                            (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) dm.enqueue(req);
                    setStatus("Download queued");
                } catch (Exception e) {
                    setStatus("Download error: " + e.getMessage());
                }
            }
        });
    }

    // Saves Alice Media's own video blob without routing it through the watermarked UI download URL.
    public void saveAliceVideoBlob(final String url, final EvalCallback callback) {
        main.post(() -> {
            if (webView == null) {
                callback.onResult(null, "webview_not_ready");
                return;
            }
            if (url == null || !url.startsWith("blob:https://alice.yandex.ru/")) {
                callback.onResult(null, "untrusted_alice_blob_url");
                return;
            }
            new AliceVideoBlobSaveJob(url, callback).start();
        });
    }

    private String publishAliceVideoToDownloads(File source, String mime) throws IOException {
        if (source == null || !source.isFile() || source.length() < 1) {
            throw new IOException("alice_video_file_missing");
        }
        if (!"video/mp4".equals(mime)) throw new IOException("unsupported_alice_video_type:" + mime);
        String displayName = "alice-motion-" + System.currentTimeMillis() + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
            values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
            Uri destination = getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IOException("downloads_insert_failed");
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new IOException("downloads_output_stream_unavailable");
                byte[] buffer = new byte[32768];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            } catch (IOException e) {
                getContentResolver().delete(destination, null, null);
                throw e;
            }
            ContentValues ready = new ContentValues();
            ready.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
            if (getContentResolver().update(destination, ready, null, null) <= 0) {
                getContentResolver().delete(destination, null, null);
                throw new IOException("downloads_publish_failed");
            }
            return displayName;
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("downloads_dir_failed");
        File target = new File(dir, displayName);
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        } catch (IOException e) {
            target.delete();
            throw e;
        }
        return target.getAbsolutePath();
    }

    private final class AliceVideoBlobSaveJob {
        private static final long MAX_SIZE = 157286400L; // 150 MiB
        private final String url;
        private final EvalCallback callback;
        private final String id = java.util.UUID.randomUUID().toString().replace("-", "");
        private final String blobKey = "__astraAliceBlob_" + id;
        private final String metaKey = "__astraAliceMeta_" + id;
        private final String chunkKey = "__astraAliceChunk_" + id;
        private File file;
        private long size;
        private boolean finished;

        AliceVideoBlobSaveJob(String url, EvalCallback callback) {
            this.url = url;
            this.callback = callback;
        }

        void start() {
            String js = "(function(){var u=" + org.json.JSONObject.quote(url)
                    + ",b=" + org.json.JSONObject.quote(blobKey)
                    + ",m=" + org.json.JSONObject.quote(metaKey)
                    + ";try{fetch(u).then(function(r){if(!r.ok)throw Error('fetch_'+r.status);return r.blob();})"
                    + ".then(function(x){if(x.type!=='video/mp4')throw Error('unexpected_mime:'+x.type);"
                    + "if(x.size<1||x.size>" + MAX_SIZE + ")throw Error('invalid_size:'+x.size);"
                    + "window[b]=x;window[m]=JSON.stringify({size:x.size,type:x.type});})"
                    + ".catch(function(e){window[m]='!'+String(e);});}"
                    + "catch(e){window[m]='!'+String(e);}return true;})()";
            webView.evaluateJavascript(js, ignored -> pollMeta(0));
        }

        private Object decode(String raw) {
            try { return raw == null ? null : new org.json.JSONTokener(raw).nextValue(); }
            catch (Exception e) { return null; }
        }

        private void pollMeta(int tries) {
            if (finished) return;
            if (webView == null) { fail("webview_not_ready"); return; }
            String check = "(function(){var v=window[" + org.json.JSONObject.quote(metaKey)
                    + "];return v===undefined?null:v;})()";
            webView.evaluateJavascript(check, raw -> {
                Object decoded = decode(raw);
                if (decoded instanceof String) {
                    String value = (String) decoded;
                    if (value.startsWith("!")) { fail(value.substring(1)); return; }
                    try {
                        org.json.JSONObject meta = new org.json.JSONObject(value);
                        size = meta.getLong("size");
                        if (!"video/mp4".equals(meta.getString("type")) || size < 1 || size > MAX_SIZE) {
                            fail("invalid_video_blob_metadata"); return;
                        }
                        File dir = new File(getCacheDir(), "alice_media");
                        if (!dir.exists() && !dir.mkdirs()) { fail("cache_dir_failed"); return; }
                        file = new File(dir, id + ".mp4");
                        writeNext(0L);
                    } catch (Exception e) { fail("invalid_blob_metadata"); }
                    return;
                }
                if (tries >= 600) { fail("blob_fetch_timeout"); return; }
                main.postDelayed(() -> pollMeta(tries + 1), 50L);
            });
        }

        private void writeNext(long offset) {
            if (finished) return;
            if (webView == null) { fail("webview_not_ready"); return; }
            if (offset >= size) { finishSuccess(); return; }
            String js = "(function(){var b=window[" + org.json.JSONObject.quote(blobKey)
                    + "],k=" + org.json.JSONObject.quote(chunkKey) + ";delete window[k];"
                    + "if(!b){window[k]='!blob_missing';return true;}"
                    + "b.slice(" + offset + "," + Math.min(offset + 32768L, size)
                    + ").arrayBuffer().then(function(buf){var a=new Uint8Array(buf),s='';"
                    + "for(var i=0;i<a.length;i++)s+=String.fromCharCode(a[i]);window[k]=btoa(s);"
                    + "}).catch(function(e){window[k]='!'+String(e);});return true;})()";
            webView.evaluateJavascript(js, ignored -> pollChunk(offset, 0));
        }

        private void pollChunk(long offset, int tries) {
            if (finished) return;
            if (webView == null) { fail("webview_not_ready"); return; }
            String check = "(function(){var v=window[" + org.json.JSONObject.quote(chunkKey)
                    + "];if(v!==undefined){delete window[" + org.json.JSONObject.quote(chunkKey)
                    + "];return v;}return null;})()";
            webView.evaluateJavascript(check, raw -> {
                Object decoded = decode(raw);
                if (decoded instanceof String) {
                    String encoded = (String) decoded;
                    if (encoded.startsWith("!")) { fail(encoded.substring(1)); return; }
                    try {
                        byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
                        if (bytes.length == 0 || offset + bytes.length > size) { fail("invalid_chunk_length"); return; }
                        try (FileOutputStream out = new FileOutputStream(file, offset > 0)) { out.write(bytes); }
                        writeNext(offset + bytes.length);
                    } catch (Exception e) { fail("chunk_write_failed"); }
                    return;
                }
                if (tries >= 120) { fail("blob_chunk_timeout"); return; }
                main.postDelayed(() -> pollChunk(offset, tries + 1), 50L);
            });
        }

        private void clearJs() {
            if (webView == null) return;
            webView.evaluateJavascript("(function(){delete window[" + org.json.JSONObject.quote(blobKey)
                    + "];delete window[" + org.json.JSONObject.quote(metaKey)
                    + "];delete window[" + org.json.JSONObject.quote(chunkKey) + "];return true;})()", null);
        }

        private void finishSuccess() {
            if (finished) return;
            finished = true;
            clearJs();
            try {
                String saved = publishAliceVideoToDownloads(file, "video/mp4");
                if (file != null && file.exists()) file.delete();
                callback.onResult(saved, null);
            } catch (Exception e) { failAfterFinish(e.getMessage()); }
        }

        private void failAfterFinish(String error) {
            if (file != null && file.exists()) file.delete();
            callback.onResult(null, error == null ? "alice_video_publish_failed" : error);
        }

        private void fail(String error) {
            if (finished) return;
            finished = true;
            clearJs();
            if (file != null && file.exists()) file.delete();
            callback.onResult(null, error == null ? "alice_blob_save_failed" : error);
        }
    }

    // Publishes a validated Bing image from the private cache into public Downloads.
    private String publishBingImageToDownloads(File source, String mime) throws IOException {
        if (source == null || !source.isFile() || source.length() < 1) {
            throw new IOException("bing_image_file_missing");
        }

        final String ext;
        if ("image/png".equals(mime)) ext = ".png";
        else if ("image/jpeg".equals(mime)) ext = ".jpg";
        else if ("image/webp".equals(mime)) ext = ".webp";
        else throw new IOException("unsupported_bing_image_type");

        String displayName = "bing-image-" + System.currentTimeMillis() + ext;

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
            values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS);
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);

            Uri destination = getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IOException("downloads_insert_failed");

            try (FileInputStream in = new FileInputStream(source);
                 java.io.OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new IOException("downloads_output_stream_unavailable");
                byte[] buffer = new byte[16384];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            } catch (IOException e) {
                getContentResolver().delete(destination, null, null);
                throw e;
            }

            android.content.ContentValues ready = new android.content.ContentValues();
            ready.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
            if (getContentResolver().update(destination, ready, null, null) <= 0) {
                getContentResolver().delete(destination, null, null);
                throw new IOException("downloads_publish_failed");
            }
            return displayName;
        }

        File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("downloads_dir_failed");
        File target = new File(dir, displayName);
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        } catch (IOException e) {
            target.delete();
            throw e;
        }
        return target.getAbsolutePath();
    }

    private void deliverPendingUpload() {
        if (pendingFileCallback == null) return;

        final String sourcePath = pendingUploadPath;
        final ValueCallback<Uri[]> callback = pendingFileCallback;

        pendingFileCallback = null;
        pendingUploadPath = null;

        if (sourcePath == null || sourcePath.isEmpty()) {
            callback.onReceiveValue(null);
            return;
        }

        main.post(() -> {
            try {
                File source = new File(sourcePath);
                if (!source.isFile()) {
                    setStatus("Upload error: file not found");
                    callback.onReceiveValue(null);
                    return;
                }

                File uploadDir = new File(getCacheDir(), "uploads");
                if (!uploadDir.exists() && !uploadDir.mkdirs()) {
                    setStatus("Upload error: cannot create cache directory");
                    callback.onReceiveValue(null);
                    return;
                }

                String sourceName = source.getName().toLowerCase(Locale.US);
                int dot = sourceName.lastIndexOf('.');
                String ext = dot >= 0 ? sourceName.substring(dot + 1) : "jpg";
                if (!ext.matches("jpg|jpeg|png|webp|heic")) ext = "jpg";
                File target = new File(
                        uploadDir,
                        "upload-" + System.currentTimeMillis() + "." + ext);

                try (FileInputStream in = new FileInputStream(source);
                     FileOutputStream out = new FileOutputStream(target)) {

                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                    }
                }

                Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".fileprovider",
                        target);

                callback.onReceiveValue(new Uri[]{uri});
                if (getIntent().getBooleanExtra("alice_automate", false)) {
                    aliceFileDeliveryCompleted = true;
                    aliceUploadError = "";
                    setStatus("Alice Motion: файл передан WebView (" + target.getName() + "). Проверяю превью…");
                } else {
                    setStatus("Upload prepared: " + target.getName());
                }

            } catch (Exception e) {
                if (getIntent().getBooleanExtra("alice_automate", false)) {
                    aliceUploadError = e.getClass().getSimpleName() + ":" + String.valueOf(e.getMessage());
                    setStatus("Alice Motion: ошибка передачи файла — " + aliceUploadError);
                } else {
                    setStatus("Upload error: " + e.getMessage());
                }
                callback.onReceiveValue(null);
            }
        });
    }

    public void setPendingUpload(final String path) {
        Runnable set = () -> {
            pendingUploadPath = path;
            aliceFileChooserRequested = false;
            aliceFileDeliveryCompleted = false;
            aliceUploadError = "";
            setStatus("Upload waiting for file chooser");
        };
        if (Looper.myLooper() == Looper.getMainLooper()) set.run();
        else main.post(set);
    }

    public void injectUploadFile(final String path) {
        main.post(() -> {
            try {
                File source = new File(path);
                if (!source.isFile()) {
                    setStatus("Upload inject error: file not found");
                    return;
                }

                FileInputStream in = new FileInputStream(source);
                byte[] bytes = new byte[(int) source.length()];
                int offset = 0;
                int count;
                while (offset < bytes.length &&
                        (count = in.read(bytes, offset, bytes.length - offset)) != -1) {
                    offset += count;
                }
                in.close();

                String base64 = android.util.Base64.encodeToString(
                        bytes, android.util.Base64.NO_WRAP);

                String safeBase64 = org.json.JSONObject.quote(base64);
                String safeName = org.json.JSONObject.quote(source.getName());

                String js =
                        "(()=>{" +
                        "const i=document.querySelector('input[type=file]');" +
                        "if(!i)return 'NO_FILE_INPUT';" +
                        "const b=atob(" + safeBase64 + ");" +
                        "const a=new Uint8Array(b.length);" +
                        "for(let n=0;n<b.length;n++)a[n]=b.charCodeAt(n);" +
                        "const f=new File([a]," + safeName + ",{type:'image/jpeg'});" +
                        "const d=new DataTransfer();" +
                        "d.items.add(f);" +
                        "i.files=d.files;" +
                        "i.dispatchEvent(new Event('input',{bubbles:true}));" +
                        "i.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "return JSON.stringify({count:i.files.length,name:i.files[0]?.name||'',size:i.files[0]?.size||0});" +
                        "})()";
                webView.evaluateJavascript(js, value ->
                        setStatus("Upload inject result: " + value));

            } catch (Exception e) {
                setStatus("Upload inject error: " + e.getMessage());
            }
        });
    }

    public void removeBingTask() {
        main.post(() -> {
            Log.i("ASTRA-WV-DIAG", "Bing task cleanup requested");
            try {
                if (webView != null) {
                    webView.stopLoading();
                }
            } catch (Exception e) {
                Log.w("ASTRA-WV-DIAG", "Bing WebView stop failed", e);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finish();
            }
        });
    }

    public void openUrlInBackground(final String url) {
        main.post(() -> {
            if (webView != null) {
                hideAfterPageLoad = false;
                webView.loadUrl(url);
                sendToBackSoon();
            }
        });
    }

    public void openUrl(final String url) {
        main.post(() -> {
            if (webView != null) webView.loadUrl(url);
        });
    }

    public void evaluate(final String script, final EvalCallback callback) {
        main.post(() -> {
            if (webView == null) {
                callback.onResult(null, "webview_not_ready");
                return;
            }

            final String key = "__astraEval_" + System.nanoTime();
            final String safeKey = org.json.JSONObject.quote(key);
            final String safeScript = org.json.JSONObject.quote(script == null ? "" : script);
            final String start = "(function(){var k=" + safeKey
                    + ";try{Promise.resolve((0,eval)(" + safeScript
                    + ")).then(function(v){try{var j=JSON.stringify(v===undefined?null:v);"
                    + "window[k]=j===undefined?'null':j;}catch(e){window[k]='!'+String(e);}},"
                    + "function(e){window[k]='!'+String(e);});}"
                    + "catch(e){window[k]='!'+String(e);}return true;})()";

            final int[] waited = {0};
            final Runnable[] poll = new Runnable[1];

            poll[0] = () -> {
                if (webView == null) {
                    callback.onResult(null, "webview_not_ready");
                    return;
                }

                String check = "(function(){var k=" + safeKey
                        + ";var v=window[k];if(v!==undefined){delete window[k];return v;}"
                        + "return null;})()";

                webView.evaluateJavascript(check, raw -> {
                    Object decoded = null;
                    try {
                        if (raw != null)
                            decoded = new org.json.JSONTokener(raw).nextValue();
                    } catch (Exception ignored) {}

                    if (decoded instanceof String) {
                        String value = (String) decoded;
                        if (value.startsWith("!"))
                            callback.onResult(null, value.substring(1));
                        else
                            callback.onResult(value, null);
                        return;
                    }

                    if (waited[0] >= 12000) {
                        webView.evaluateJavascript(
                                "(function(){delete window[" + safeKey + "];return true;})()",
                                null);
                        callback.onResult(null, "promise_timeout");
                        return;
                    }

                    waited[0] += 100;
                    main.postDelayed(poll[0], 100L);
                });
            };

            webView.evaluateJavascript(start, ignored -> poll[0].run());
        });
    }



    // ASTRA_BING_EVAL_V3: await direct expressions without eval(), for Bing CSP.
    public void evaluateBing(final String script, final EvalCallback callback) {
        main.post(() -> {
            if (webView == null) {
                callback.onResult(null, "webview_not_ready");
                return;
            }

            final String key = "__astraBingEval_" + System.nanoTime();
            final String safeKey = org.json.JSONObject.quote(key);
            final String expression = (script == null || script.trim().isEmpty())
                    ? "null" : script;

            final String start =
                    "(function(){var k=" + safeKey + ";var s=window[k]={done:false,value:null,error:null};" +
                    "function finish(v,e){s.value=v;s.error=e===null?null:String(e);s.done=true;}" +
                    "try{Promise.resolve((" + expression + ")).then(function(v){" +
                    "try{var j=JSON.stringify(v===undefined?null:v);" +
                    "finish(j===undefined?'null':j,null);" +
                    "}catch(e){finish(null,e);}" +
                    "},function(e){finish(null,e);});" +
                    "}catch(e){finish(null,e);}return true;})()";

            final int[] waited = {0};
            final Runnable[] poll = new Runnable[1];

            poll[0] = () -> {
                if (webView == null) {
                    callback.onResult(null, "webview_not_ready");
                    return;
                }

                String check = "(function(){var k=" + safeKey +
                        ";var s=window[k];if(s&&s.done){delete window[k];" +
                        "return JSON.stringify({done:true,value:s.value,error:s.error});}" +
                        "return null;})()";

                webView.evaluateJavascript(check, raw -> {
                    Object decoded = null;
                    try {
                        if (raw != null) {
                            decoded = new org.json.JSONTokener(raw).nextValue();
                        }
                    } catch (Exception ignored) {}

                    if (decoded instanceof String) {
                        try {
                            org.json.JSONObject result =
                                    new org.json.JSONObject((String) decoded);
                            String error = result.isNull("error") ? "" : result.optString("error", "");
                            if (!error.isEmpty()) {
                                callback.onResult(null, error);
                            } else {
                                callback.onResult(result.optString("value", "null"), null);
                            }
                        } catch (Exception e) {
                            callback.onResult(null, "bing_eval_decode_error");
                        }
                        return;
                    }

                    if (waited[0] >= 25000) {
                        webView.evaluateJavascript(
                                "(function(){delete window[" + safeKey + "];return true;})()",
                                null);
                        callback.onResult(null, "promise_timeout");
                        return;
                    }

                    waited[0] += 100;
                    main.postDelayed(poll[0], 100L);
                });
            };

            webView.evaluateJavascript(start, ignored -> poll[0].run());
        });
    }

    // ASTRA_BING_BLOB_SAVE_V1: fetches a Bing-owned blob in WebView context and streams it to app cache.
    public void saveBingImage(final String url, final EvalCallback callback) {
        main.post(() -> {
            if (webView == null) {
                callback.onResult(null, "webview_not_ready");
                return;
            }
            boolean bingBlob = url != null && url.startsWith("blob:https://www.bing.com/");
            boolean bingHttps = false;
            try {
                java.net.URI uri = url == null ? null : java.net.URI.create(url);
                bingHttps = uri != null
                        && "https".equalsIgnoreCase(uri.getScheme())
                        && "th.bing.com".equalsIgnoreCase(uri.getHost())
                        && uri.getPath() != null
                        && uri.getPath().startsWith("/th/id/");
            } catch (Exception ignored) {
                bingHttps = false;
            }
            if (!bingBlob && !bingHttps) {
                callback.onResult(null, "unsupported_bing_image_url");
                return;
            }
            new BingSaveJob(url, callback).start();
        });
    }

    private final class BingSaveJob {
        private final String url;
        private final EvalCallback callback;
        private final String id = java.util.UUID.randomUUID().toString().replace("-", "");
        private final String blobKey = "__astraBingBlob_" + id;
        private final String metaKey = "__astraBingMeta_" + id;
        private final String chunkKey = "__astraBingChunk_" + id;
        private File file;
        private String mime;
        private long size;
        private boolean finished;

        BingSaveJob(String url, EvalCallback callback) {
            this.url = url;
            this.callback = callback;
        }

        void start() {
            if (url != null && url.startsWith("https://th.bing.com/th/id/")) {
                startHttpsDownload();
                return;
            }
            String js = "(function(){var u=" + org.json.JSONObject.quote(url)
                    + ",b=" + org.json.JSONObject.quote(blobKey)
                    + ",m=" + org.json.JSONObject.quote(metaKey)
                    + ";try{fetch(u).then(function(r){if(!r.ok)throw Error('fetch_'+r.status);return r.blob();})"
                    + ".then(function(x){if(!/^image\\/(png|jpeg|webp)$/.test(x.type))throw Error('unsupported_mime:'+x.type);"
                    + "if(x.size<1||x.size>20971520)throw Error('invalid_size:'+x.size);"
                    + "window[b]=x;window[m]=JSON.stringify({size:x.size,type:x.type});})"
                    + ".catch(function(e){window[m]='!'+String(e);});}"
                    + "catch(e){window[m]='!'+String(e);}return true;})()";
            webView.evaluateJavascript(js, ignored -> pollMeta(0));
        }


        private boolean isTrustedBingHttpsUrl(String value) {
            try {
                java.net.URI uri = java.net.URI.create(value);
                return "https".equalsIgnoreCase(uri.getScheme())
                    && "th.bing.com".equalsIgnoreCase(uri.getHost());
            } catch (Exception e) {
                return false;
            }
        }

        private void startHttpsDownload() {
            new Thread(() -> {
                java.net.HttpURLConnection conn = null;
                try {
                    String current = url;
                    for (int redirects = 0; redirects <= 5; redirects++) {
                        if (!isTrustedBingHttpsUrl(current)) {
                            throw new java.io.IOException("bing_redirect_host_rejected");
                        }

                        java.net.URL requestUrl = new java.net.URL(current);
                        conn = (java.net.HttpURLConnection) requestUrl.openConnection();
                        conn.setInstanceFollowRedirects(false);
                        conn.setConnectTimeout(15000);
                        conn.setReadTimeout(30000);
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
                        conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
                        conn.setRequestProperty("Referer", "https://www.bing.com/");

                        int code = conn.getResponseCode();
                        if (code == 301 || code == 302 || code == 303
                                || code == 307 || code == 308) {
                            String location = conn.getHeaderField("Location");
                            conn.disconnect();
                            conn = null;
                            if (location == null || redirects == 5) {
                                throw new java.io.IOException("bing_redirect_limit_or_missing_location");
                            }
                            current = new java.net.URL(requestUrl, location).toString();
                            continue;
                        }
                        if (code < 200 || code >= 300) {
                            throw new java.io.IOException("bing_http_status_" + code);
                        }

                        String responseMime = conn.getContentType();
                        if (responseMime != null && responseMime.contains(";")) {
                            responseMime = responseMime.substring(0, responseMime.indexOf(';')).trim();
                        }
                        if (responseMime != null) responseMime = responseMime.toLowerCase(java.util.Locale.ROOT);

                        final String ext;
                        if ("image/png".equals(responseMime)) ext = ".png";
                        else if ("image/jpeg".equals(responseMime)) ext = ".jpg";
                        else if ("image/webp".equals(responseMime)) ext = ".webp";
                        else throw new java.io.IOException("unsupported_mime:" + responseMime);

                        int declaredLength = conn.getContentLength();
                        if (declaredLength > 20971520) {
                            throw new java.io.IOException("bing_image_too_large");
                        }

                        File dir = new File(getCacheDir(), "bing_images");
                        if (!dir.exists() && !dir.mkdirs()) {
                            throw new java.io.IOException("cache_dir_failed");
                        }
                        file = new File(dir, id + ext);
                        long total = 0;
                        try (java.io.InputStream in = new java.io.BufferedInputStream(conn.getInputStream());
                             java.io.OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(file))) {
                            byte[] buffer = new byte[16384];
                            int count;
                            while ((count = in.read(buffer)) != -1) {
                                total += count;
                                if (total > 20971520L) {
                                    throw new java.io.IOException("bing_image_too_large");
                                }
                                out.write(buffer, 0, count);
                            }
                        }

                        if (total < 1) throw new java.io.IOException("bing_image_empty");
                        size = total;
                        mime = responseMime;
                        main.post(() -> finishSuccess());
                        return;
                    }
                    throw new java.io.IOException("bing_redirect_limit");
                } catch (Exception e) {
                    final String message = (e.getMessage() == null || e.getMessage().isEmpty())
                        ? "bing_https_download_failed" : e.getMessage();
                    main.post(() -> fail(message));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }, "ASTRA-Bing-HTTPS-download").start();
        }
        private Object decode(String raw) {
            try {
                return raw == null ? null : new org.json.JSONTokener(raw).nextValue();
            } catch (Exception e) {
                return null;
            }
        }

        private void pollMeta(int tries) {
            if (finished) return;
            if (webView == null) { fail("webview_not_ready"); return; }
            String check = "(function(){var v=window[" + org.json.JSONObject.quote(metaKey)
                    + "];return v===undefined?null:v;})()";
            webView.evaluateJavascript(check, raw -> {
                Object decoded = decode(raw);
                if (decoded instanceof String) {
                    String value = (String) decoded;
                    if (value.startsWith("!")) { fail(value.substring(1)); return; }
                    try {
                        org.json.JSONObject meta = new org.json.JSONObject(value);
                        size = meta.getLong("size");
                        mime = meta.getString("type");
                        if (size < 1 || size > 20971520L) { fail("invalid_size"); return; }
                        String ext = "image/png".equals(mime) ? ".png"
                                : "image/jpeg".equals(mime) ? ".jpg"
                                : "image/webp".equals(mime) ? ".webp" : null;
                        if (ext == null) { fail("unsupported_mime"); return; }
                        File dir = new File(getCacheDir(), "bing_images");
                        if (!dir.exists() && !dir.mkdirs()) { fail("cache_dir_failed"); return; }
                        file = new File(dir, id + ext);
                        if (file.exists() && !file.delete()) { fail("cache_file_reset_failed"); return; }
                        writeNext(0L);
                    } catch (Exception e) {
                        fail("invalid_blob_metadata");
                    }
                    return;
                }
                if (tries >= 300) { fail("blob_fetch_timeout"); return; }
                main.postDelayed(() -> pollMeta(tries + 1), 50L);
            });
        }

        private void writeNext(long offset) {
            if (finished) return;
            if (webView == null) { fail("webview_not_ready"); return; }
            if (offset >= size) {
                finishSuccess();
                return;
            }
            String js = "(function(){var b=window[" + org.json.JSONObject.quote(blobKey)
                    + "],k=" + org.json.JSONObject.quote(chunkKey) + ";delete window[k];"
                    + "if(!b){window[k]='!blob_missing';return true;}"
                    + "b.slice(" + offset + "," + Math.min(offset + 32768L, size)
                    + ").arrayBuffer().then(function(buf){var a=new Uint8Array(buf),s='';"
                    + "for(var i=0;i<a.length;i++)s+=String.fromCharCode(a[i]);window[k]=btoa(s);"
                    + "}).catch(function(e){window[k]='!'+String(e);});return true;})()";
            webView.evaluateJavascript(js, ignored -> pollChunk(offset, 0));
        }

        private void pollChunk(long offset, int tries) {
            if (finished) return;
            if (webView == null) { fail("webview_not_ready"); return; }
            String check = "(function(){var v=window[" + org.json.JSONObject.quote(chunkKey)
                    + "];if(v!==undefined){delete window[" + org.json.JSONObject.quote(chunkKey)
                    + "];return v;}return null;})()";
            webView.evaluateJavascript(check, raw -> {
                Object decoded = decode(raw);
                if (decoded instanceof String) {
                    String encoded = (String) decoded;
                    if (encoded.startsWith("!")) { fail(encoded.substring(1)); return; }
                    try {
                        byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
                        if (bytes.length == 0 || offset + bytes.length > size) {
                            fail("invalid_chunk_length");
                            return;
                        }
                        try (FileOutputStream out = new FileOutputStream(file, offset > 0)) {
                            out.write(bytes);
                        }
                        writeNext(offset + bytes.length);
                    } catch (Exception e) {
                        fail("chunk_write_failed");
                    }
                    return;
                }
                if (tries >= 100) { fail("blob_chunk_timeout"); return; }
                main.postDelayed(() -> pollChunk(offset, tries + 1), 50L);
            });
        }

        private void clearJs() {
            if (webView == null) return;
            String js = "(function(){delete window[" + org.json.JSONObject.quote(blobKey)
                    + "];delete window[" + org.json.JSONObject.quote(metaKey)
                    + "];delete window[" + org.json.JSONObject.quote(chunkKey) + "];return true;})()";
            webView.evaluateJavascript(js, null);
        }

        private void finishSuccess() {
            if (finished) return;
            finished = true;
            clearJs();
            try {
                org.json.JSONObject result = new org.json.JSONObject();
                result.put("id", id);
                result.put("size", size);
                result.put("type", mime);
                result.put("name", file.getName());
                callback.onResult(result.toString(), null);
            } catch (Exception e) {
                if (file != null && file.exists()) file.delete();
                callback.onResult(null, "result_json_failed");
            }
        }

        private void fail(String error) {
            if (finished) return;
            finished = true;
            clearJs();
            if (file != null && file.exists()) file.delete();
            callback.onResult(null, error == null ? "bing_blob_save_failed" : error);
        }
    }

    // Reads only files created by BingSaveJob; id and chunk size are strictly bounded.
    public static String readBingImageChunk(String id, int offset, int length) {
        if (id == null || !id.matches("[0-9a-f]{32}") || offset < 0
                || length < 1 || length > 32768) {
            return "{\"ok\":false,\"error\":\"invalid_chunk_request\"}";
        }
        Context context = MainApplication.context();
        if (context == null) return "{\"ok\":false,\"error\":\"context_unavailable\"}";
        File dir = new File(context.getCacheDir(), "bing_images");
        File file = null;
        for (String ext : new String[]{".png", ".jpg", ".webp"}) {
            File candidate = new File(dir, id + ext);
            if (candidate.isFile()) { file = candidate; break; }
        }
        if (file == null) return "{\"ok\":false,\"error\":\"image_not_found\"}";
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long size = raf.length();
            if (offset > size) return "{\"ok\":false,\"error\":\"offset_out_of_range\"}";
            int count = (int) Math.min((long) length, size - offset);
            byte[] bytes = new byte[count];
            raf.seek(offset);
            raf.readFully(bytes);
            String data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            boolean eof = offset + count >= size;
            return "{\"ok\":true,\"id\":\"" + id + "\",\"offset\":" + offset
                    + ",\"size\":" + size + ",\"data\":\"" + data + "\",\"eof\":" + eof + "}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"chunk_read_failed\"}";
        }
    }

    public void inspectBing(final EvalCallback callback) {
        main.post(() -> {
            if (webView == null) {
                callback.onResult(null, "webview_not_ready");
                return;
            }
            final String js =
                "(()=>({" +
                "title:document.title," +
                "url:location.href," +
                "fields:Array.from(document.querySelectorAll('input:not([type=hidden]),textarea,[contenteditable=true]')).slice(0,12).map(e=>({" +
                "tag:e.tagName," +
                "type:e.getAttribute('type')||''," +
                "placeholder:e.getAttribute('placeholder')||''," +
                "ariaLabel:e.getAttribute('aria-label')||''," +
                "role:e.getAttribute('role')||''," +
                "name:e.getAttribute('name')||''," +
                "visible:!!(e.getClientRects().length)" +
                "})), " +
                "buttons:Array.from(document.querySelectorAll('button,[role=button]')).slice(0,30).map(e=>({" +
                "text:(e.innerText||e.getAttribute('aria-label')||'').trim().slice(0,100)," +
                "ariaLabel:e.getAttribute('aria-label')||''," +
                "disabled:!!e.disabled," +
                "visible:!!(e.getClientRects().length)" +
                "})), " +
                "imageCount:Array.from(document.images).filter(e=>e.getClientRects().length).length," +
                "images:Array.from(document.images).filter(e=>e.getClientRects().length).map(e=>({" +
                "src:e.currentSrc||e.src||''," +
                "protocol:(()=>{try{return new URL(e.currentSrc||e.src,location.href).protocol}catch(_){return 'unknown'}})()," +
                "alt:e.alt||''," +
                "width:e.naturalWidth||e.width||0," +
                "height:e.naturalHeight||e.height||0," +
                "complete:!!e.complete," +
                "visible:!!e.getClientRects().length" +
                "})), " +
                "imageLinks:Array.from(document.querySelectorAll('a[href]')).filter(a=>a.getClientRects().length && (/images|create|download/i.test(a.href)||a.querySelector('img'))).slice(0,30).map(a=>({" +
                "href:a.href," +
                "text:(a.innerText||a.getAttribute('aria-label')||'').trim().slice(0,80)," +
                "hasImage:!!a.querySelector('img')" +
                "}))" +
                "}))()";
            webView.evaluateJavascript(js, value -> {
                if (value == null || "null".equals(value)) {
                    callback.onResult(null, "empty_inspection_result");
                } else {
                    callback.onResult(value, null);
                }
            });
        });
    }

    // Read-only Alice Media diagnostics. Never collects cookies, form values, or account data.
    public void inspectAlice(final EvalCallback callback) {
        main.post(() -> {
            if (webView == null) {
                callback.onResult(null, "webview_not_ready");
                return;
            }
            String currentUrl = webView.getUrl();
            boolean trusted = false;
            try {
                String host = Uri.parse(currentUrl == null ? "" : currentUrl).getHost();
                trusted = host != null && (host.equalsIgnoreCase("alice.yandex.ru")
                        || host.toLowerCase(Locale.US).endsWith(".alice.yandex.ru"));
            } catch (Exception ignored) {}
            if (!trusted) {
                callback.onResult(null, "not_alice_media_origin");
                return;
            }
            final String js =
                "(()=>{const visible=e=>!!(e&&e.getClientRects().length);" +
                "const clean=u=>{try{const x=new URL(u,location.href);return x.protocol==='blob:'?'blob:'+x.origin+'/[opaque]':x.origin+x.pathname}catch(_){return String(u||'').slice(0,180)}};" +
                "const media=[...document.querySelectorAll('video,audio,source')].slice(0,30).map(e=>({" +
                "tag:e.tagName,src:clean(e.currentSrc||e.src||e.getAttribute('src')||'')," +
                "type:e.getAttribute('type')||'',readyState:e.readyState||0,networkState:e.networkState||0," +
                "duration:Number.isFinite(e.duration)?e.duration:null,videoWidth:e.videoWidth||0,videoHeight:e.videoHeight||0," +
                "paused:typeof e.paused==='boolean'?e.paused:null,visible:visible(e)}));" +
                "const files=[...document.querySelectorAll('input[type=file]')].slice(0,10).map(e=>({" +
                "accept:e.accept||'',multiple:!!e.multiple,visible:visible(e),disabled:!!e.disabled}));" +
                "const fields=[...document.querySelectorAll('textarea,input:not([type=hidden]),[contenteditable=true]')].filter(visible).slice(0,15).map(e=>({" +
                "tag:e.tagName,type:e.getAttribute('type')||'',placeholder:e.getAttribute('placeholder')||'',"+
                "ariaLabel:e.getAttribute('aria-label')||'',role:e.getAttribute('role')||''}));" +
                "const buttons=[...document.querySelectorAll('button,[role=button]')].filter(visible).slice(0,40).map(e=>({" +
                "text:(e.innerText||e.getAttribute('aria-label')||'').trim().slice(0,90),disabled:!!e.disabled}));" +
                "const resources=performance.getEntriesByType('resource').filter(e=>/alice\\.yandex\\.ru|yandex\\.net|video|media|blob/i.test(e.name)).slice(-60).map(e=>({" +
                "url:clean(e.name),initiator:e.initiatorType||'',size:e.transferSize||0,duration:Math.round(e.duration||0)}));" +
                "return JSON.stringify({kind:'ASTRA_ALICE_INSPECTOR_V1',time:new Date().toISOString()," +
                "title:document.title,url:location.origin+location.pathname,readyState:document.readyState," +
                "headings:[...document.querySelectorAll('h1,h2,h3')].filter(visible).slice(0,12).map(e=>(e.innerText||'').trim().slice(0,100))," +
                "status:[...document.querySelectorAll('[role=status],[aria-live]')].filter(visible).slice(0,12).map(e=>(e.innerText||'').trim().slice(0,140)),files,fields,buttons,media,resources});})()";
            webView.evaluateJavascript(js, value -> {
                if (value == null || "null".equals(value)) callback.onResult(null, "empty_inspection_result");
                else callback.onResult(value, null);
            });
        });
    }

    public String statusJson() {
        if (webView == null)
            return "{\"ok\":false,\"ready\":false,\"state\":\"stopped\"}";

        final Object lock = new Object();
        final String[] result = new String[1];

        main.post(() -> {
            try {
                if (webView == null) {
                    result[0] = "{\"ok\":false,\"ready\":false,\"state\":\"stopped\"}";
                } else {
                    result[0] = String.format(Locale.US,
                            "{\"ok\":true,\"ready\":true,\"url\":\"%s\",\"title\":\"%s\"}",
                            esc(webView.getUrl()), esc(webView.getTitle()));
                }
            } finally {
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });

        synchronized (lock) {
            try {
                if (result[0] == null) lock.wait(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{\"ok\":false,\"error\":\"interrupted\"}";
            }
        }

        return result[0] != null
                ? result[0]
                : "{\"ok\":false,\"error\":\"status_timeout\"}";
    }

    private void scheduleAliceAutomation(final int attempt) {
        if (aliceAutomationStarted || aliceUploadTriggered || isFinishing() || webView == null) return;
        if (attempt > 90) {
            setStatus("Alice Motion: не найдена кнопка «Загрузить фото».");
            return;
        }
        String js = "(function(){var es=[...document.querySelectorAll('button,[role=button],label')];"
                + "var e=es.find(x=>x.getClientRects().length&&!x.disabled&&/загрузить фото|добавить фото|выбрать фото/i.test((x.innerText||x.textContent||'').trim()));"
                + "return JSON.stringify({url:location.href,upload:!!e,text:e?(e.innerText||e.textContent||'').trim():''});})()";
        webView.evaluateJavascript(js, raw -> {
            boolean ready = false;
            try {
                Object decoded = new org.json.JSONTokener(raw).nextValue();
                if (decoded instanceof String) {
                    org.json.JSONObject info = new org.json.JSONObject((String) decoded);
                    ready = info.optString("url", "").startsWith("https://alice.yandex.ru/")
                            && info.optBoolean("upload", false);
                }
            } catch (Exception ignored) {}
            if (!ready) {
                main.postDelayed(() -> scheduleAliceAutomation(attempt + 1), 1800L);
                return;
            }
            if (!aliceWarmupDelayScheduled) {
                aliceWarmupDelayScheduled = true;
                setStatus("Alice Motion: студия загрузилась. Жду 10 секунд, пока интерфейс станет готов к загрузке фото…");
                main.postDelayed(() -> scheduleAliceAutomation(attempt), 10000L);
                return;
            }
            runAliceAutomation();
        });
    }

    private void runAliceAutomation() {
        if (aliceAutomationStarted || aliceUploadTriggered || webView == null) return;
        if (aliceAutomationImagePath == null || aliceAutomationPrompt == null
                || !new File(aliceAutomationImagePath).isFile()) {
            setStatus("Alice Motion: отсутствует изображение или промпт.");
            return;
        }
        File source = new File(aliceAutomationImagePath);
        if (source.length() < 1 || source.length() > 20L * 1024L * 1024L) {
            setStatus("Alice Motion: размер изображения недопустим.");
            return;
        }

        // Use WebView's native file chooser callback. Alice's upload sheet is a custom UI;
        // assigning input.files from JavaScript does not reliably trigger its upload pipeline.
        setPendingUpload(source.getAbsolutePath());
        String script = "(function(){var es=[...document.querySelectorAll('button,[role=button],label')];"
                + "var e=es.find(x=>x.getClientRects().length&&!x.disabled&&/загрузить фото|добавить фото|выбрать фото/i.test((x.innerText||x.textContent||'').trim()));"
                + "if(!e)return 'UPLOAD_BUTTON_NOT_FOUND';"
                + "window.__astraAliceBaselineImages=[...document.images].map(i=>i.currentSrc||i.src||'');"
                + "e.click();return 'UPLOAD_BUTTON_CLICKED';})()";
        webView.evaluateJavascript(script, result -> {
            if (result != null && result.contains("UPLOAD_BUTTON_CLICKED")) {
                aliceUploadTriggered = true;
                setStatus("Alice Motion: нажала «Загрузить фото», передаю картину через системный механизм WebView…");
                waitForAliceFileDelivery(0);
            } else {
                setStatus("Alice Motion: не удалось нажать «Загрузить фото» (" + result + ").");
            }
        });
    }

    private void waitForAliceFileDelivery(final int attempt) {
        if (webView == null || isFinishing()) return;
        if (aliceFileDeliveryCompleted) {
            setStatus("Alice Motion: файл передан WebView. Проверяю появление загруженной картины…");
            waitForAliceImageReady(0);
            return;
        }
        if (!aliceUploadError.isEmpty()) {
            setStatus("Alice Motion: передача изображения не удалась — " + aliceUploadError);
            return;
        }
        if (attempt >= 100) {
            setStatus("Alice Motion: сайт не вызвал штатный выбор файла WebView. Промпт не отправляю.");
            return;
        }
        main.postDelayed(() -> waitForAliceFileDelivery(attempt + 1), 300L);
    }

    private void waitForAliceImageReady(final int attempt) {
        if (webView == null || isFinishing() || aliceAutomationStarted) return;
        if (attempt >= 90) {
            setStatus("Alice Motion: файл передан, но превью картины не появилось. Генерацию не запускаю.");
            return;
        }
        String js = "(function(){try{"
                + "var files=[...document.querySelectorAll('input[type=file]')].reduce((n,e)=>n+((e.files&&e.files.length)||0),0);"
                + "var base=window.__astraAliceBaselineImages||[];var known=new Set(base);"
                + "var added=[...document.images].filter(i=>i.getClientRects().length&&(i.naturalWidth||0)>96&&(i.naturalHeight||0)>96&&!known.has(i.currentSrc||i.src||''));"
                + "var body=(document.body&&document.body.innerText||'').toLowerCase();"
                + "var cue=/изменить фото|заменить фото|удалить фото|сбросить фото/.test(body);"
                + "return JSON.stringify({files:files,previews:added.length,cue:cue});"
                + "}catch(e){return '{}';}})()";
        webView.evaluateJavascript(js, raw -> {
            boolean ready = false;
            try {
                Object decoded = new org.json.JSONTokener(raw).nextValue();
                if (decoded instanceof String) {
                    org.json.JSONObject info = new org.json.JSONObject((String) decoded);
                    ready = info.optInt("files", 0) > 0 || info.optInt("previews", 0) > 0 || info.optBoolean("cue", false);
                }
            } catch (Exception ignored) {}
            if (ready) {
                setStatus("Alice Motion: загруженная картина обнаружена. Ввожу промпт…");
                main.postDelayed(() -> submitAlicePromptAndClick(
                        org.json.JSONObject.quote(aliceAutomationPrompt), 0), 1200L);
            } else {
                main.postDelayed(() -> waitForAliceImageReady(attempt + 1), 1000L);
            }
        });
    }

    private void submitAlicePromptAndClick(final String safePrompt, final int attempt) {
        if (webView == null || isFinishing()) return;
        if (attempt > 30) { setStatus("Alice Motion: поле промпта или кнопка отправки не найдены. Нужна DOM-диагностика."); return; }
        String script = "(function(){try{"
                + "var fields=[...document.querySelectorAll('textarea,input:not([type=hidden]),[contenteditable=true]')].filter(e=>e.getClientRects().length&&!e.disabled);"
                + "var field=fields.find(e=>/напиш|опиш|промпт|движен|message/i.test((e.getAttribute('placeholder')||'')+' '+(e.getAttribute('aria-label')||'')))||fields.find(e=>e.tagName==='TEXTAREA'||e.isContentEditable);"
                + "if(!field)return 'WAIT_FIELD';var value=" + safePrompt + ";"
                + "field.focus();if(field.isContentEditable){field.textContent=value;}else{var proto=field.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var setter=Object.getOwnPropertyDescriptor(proto,'value').set;setter.call(field,value);}"
                + "field.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:value}));field.dispatchEvent(new Event('change',{bubbles:true}));"
                + "var scope=field.parentElement;for(var n=0;n<5&&scope;n++,scope=scope.parentElement){var bs=[...scope.querySelectorAll('button,[role=button]')].filter(b=>b.getClientRects().length&&!b.disabled);"
                + "var send=bs.filter(b=>{var t=((b.innerText||'')+' '+(b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'')).toLowerCase();return !/оживить|что сделать|режим/.test(t)&&!!b.querySelector('svg')&&(t.trim()===''||/отправ|созда|send|submit|arrow|стрел/.test(t));});"
                + "if(send.length===1){var ids=new Set();function scan(u){var m=String(u||'').match(/(?:[?&]video=|\\/webalice\\/)([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase());}[...document.querySelectorAll('video,video source,a[href]')].forEach(e=>scan(e.currentSrc||e.src||e.href||''));performance.getEntriesByType('resource').forEach(e=>scan(e.name));scan(location.href);window.__astraAliceBaselineIds=[...ids];send[0].click();return 'PROMPT_SENT_ARROW_CLICKED';}}"
                + "return 'WAIT_ARROW';}catch(e){return 'AUTOMATION_ERROR:'+String(e);}})()";
        webView.evaluateJavascript(script, result -> {
            if (result != null && result.contains("PROMPT_SENT_ARROW_CLICKED")) {
                aliceAutomationStarted = true;
                setStatus("Alice Motion: промпт отправлен фиолетовой кнопкой-стрелкой. Отслеживаю готовое видео…");
                startAliceResultObserver(0);
                main.postDelayed(() -> {
                    if (!isFinishing()) {
                        try {
                            moveTaskToBack(true);
                            setStatus("Alice Motion: генерация продолжается в фоне. ASTRA ждёт MP4…");
                        } catch (Exception e) {
                            setStatus("Alice Motion: не удалось свернуть WebView — " + e.getMessage());
                        }
                    }
                }, 1600L);
            } else if (result != null && (result.contains("WAIT_FIELD") || result.contains("WAIT_ARROW"))) {
                main.postDelayed(() -> submitAlicePromptAndClick(safePrompt, attempt + 1), 1200L);
            } else {
                setStatus("Alice Motion: не удалось безопасно определить кнопку отправки (" + result + ").");
            }
        });
    }

    private void startAliceResultObserver(final int attempt) {
        if (aliceResultObserverRunning || aliceDownloadStarted || webView == null) return;
        aliceResultObserverRunning = true;
        pollAliceResult(attempt);
    }

    private void pollAliceResult(final int attempt) {
        if (webView == null || isFinishing() || aliceDownloadStarted) {
            aliceResultObserverRunning = false;
            return;
        }
        if (attempt > 360) {
            aliceResultObserverRunning = false;
            setStatus("Alice Motion: время ожидания результата истекло. Генерация могла продолжиться на странице Алисы.");
            return;
        }
        String js = "(function(){try{var urls=[];"
                + "document.querySelectorAll('video,video source,a[href]').forEach(function(e){var u=e.currentSrc||e.src||e.href||'';if(u)urls.push(u);});"
                + "performance.getEntriesByType('resource').forEach(function(e){if(/webalice|yaart-images|video/i.test(e.name))urls.push(e.name);});"
                + "var ids=new Set();urls.forEach(function(u){var m=String(u).match(/(?:[?&]video=|\\/webalice\\/)([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase());});"
                + "var m=location.href.match(/[?&]video=([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase());"
                + "return JSON.stringify({ids:[...ids],baseline:window.__astraAliceBaselineIds||[],urls:urls.slice(-60),url:location.href});"
                + "}catch(e){return '{}';}})()";
        webView.evaluateJavascript(js, raw -> {
            String found = null;
            try {
                Object decoded = new org.json.JSONTokener(raw).nextValue();
                org.json.JSONObject data = new org.json.JSONObject(String.valueOf(decoded));
                org.json.JSONArray ids = data.optJSONArray("ids");
                java.util.HashSet<String> baseline = new java.util.HashSet<>();
                org.json.JSONArray oldIds = data.optJSONArray("baseline");
                if (oldIds != null) for (int i=0;i<oldIds.length();i++) baseline.add(oldIds.optString(i, "").toLowerCase(Locale.US));
                if (ids != null) for (int i=0;i<ids.length();i++) {
                    String candidate = ids.optString(i, "").toLowerCase(Locale.US);
                    if (candidate.matches("[0-9a-f]{32}") && !baseline.contains(candidate)) {
                        found = candidate;
                        break;
                    }
                }
            } catch (Exception ignored) {}
            if (found != null) {
                final String id = found;
                aliceDownloadStarted = true;
                aliceResultObserverRunning = false;
                setStatus("Alice Motion: найден результат " + id + ". Скачиваю MP4…");
                aliceDownloads.execute(() -> {
                    String result = AliceMediaAgent.download(getApplicationContext(), id);
                    try {
                        org.json.JSONObject json = new org.json.JSONObject(result);
                        if (json.optBoolean("ok")) {
                            Uri video = Uri.parse(json.getString("uri"));
                            ChatActivity.onAliceVideoReady(getApplicationContext(), video);
                            setStatus("Alice Motion: MP4 скачан и передан в чат ASTRA.");
                        } else {
                            setStatus("Alice Motion: ошибка скачивания — " + json.optString("error", "unknown"));
                        }
                    } catch (Exception e) {
                        setStatus("Alice Motion: ошибка обработки результата — " + e.getMessage());
                    }
                });
                return;
            }
            main.postDelayed(() -> pollAliceResult(attempt + 1), 5000L);
        });
    }

    private void updateStatus() {
        if (status != null && webView != null)
            status.setText("ASTRA WebView\\n\\n" +
                    webView.getTitle() + "\\n" + webView.getUrl());
    }

    private void setStatus(String text) {
        main.post(() -> {
            if (status != null) status.setText("ASTRA WebView\\n\\n" + text);
        });
    }

    @Override protected void onDestroy() {
        stopPerchanceObserver();
        aliceDownloads.shutdown();
        Log.i("ASTRA-WV-DIAG", "Activity onDestroy entered");
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\\n", "\\\\n")
                .replace("\r", "\\r");
    }

    public interface EvalCallback {
        void onResult(String value, String error);
    }
}
