package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Alice Media worker hosted inside ChatActivity.workerHost.
 * The WebView remains alive while the visible chat is in the foreground.
 */
public final class AliceMediaBrowserPanel extends LinearLayout {
    public static final int REQUEST_PICK_MEDIA = 18781;
    private static final String TAG = "ASTRA-ALICE-MEDIA";
    private static final String HOME = "https://alice.yandex.ru/media";

    private final Activity host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, FileOutputStream> streams = new HashMap<>();
    private final Map<String, File> tempFiles = new HashMap<>();
    private WebView webView;
    private TextView status;
    private ValueCallback<Uri[]> fileChooserCallback;
    private boolean destroyed;
    private boolean observerStarted;

    public AliceMediaBrowserPanel(Activity host) {
        super(host);
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(0xff080b18);

        LinearLayout bar = new LinearLayout(host);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(12, 5, 12, 5);
        Button back = new Button(host);
        back.setText("‹ ASTRA");
        back.setOnClickListener(v -> setVisibility(View.GONE));
        bar.addView(back, new LayoutParams(-2, -2));
        status = new TextView(host);
        status.setTextColor(0xffeaf1ff);
        status.setText("АЛИСА AI · подключение к Медиа");
        status.setPadding(12, 0, 4, 0);
        bar.addView(status, new LayoutParams(0, -2, 1));
        addView(bar, new LayoutParams(-1, -2));

        webView = new WebView(host);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        android.webkit.CookieManager cookies = android.webkit.CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new AliceCaptureBridge(), "ASTRA_ALICE_CAPTURE");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                try {
                    host.startActivityForResult(Intent.createChooser(intent, "Выбрать изображение"), REQUEST_PICK_MEDIA);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    callback.onReceiveValue(null);
                    setStatus("Не удалось открыть выбор изображения");
                    return false;
                }
            }
        });
        webView.setDownloadListener((url, userAgent, disposition, mimeType, length) -> {
            if (url != null && url.startsWith("blob:https://alice.yandex.ru/")) {
                captureBlob(url, "video/mp4");
            } else {
                Log.i(TAG, "Download requested: " + safeOrigin(url));
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (isAliceOrigin(url)) {
                    setStatus("АЛИСА AI · сессия активна · сборщик готов");
                    startObserver();
                } else {
                    setStatus("АЛИСА AI · переход: " + safeOrigin(url));
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                return uri != null && !("https".equalsIgnoreCase(uri.getScheme())
                        && isAllowedHost(uri.getHost()));
            }
        });
        addView(webView, new LayoutParams(-1, 0, 1f));
        webView.loadUrl(HOME);
    }

    private boolean isAliceOrigin(String url) {
        try { return url != null && isAllowedHost(Uri.parse(url).getHost()); }
        catch (Exception e) { return false; }
    }

    private boolean isAllowedHost(String hostName) {
        return hostName != null && (hostName.equalsIgnoreCase("alice.yandex.ru")
                || hostName.toLowerCase(java.util.Locale.ROOT).endsWith(".alice.yandex.ru"));
    }

    private String safeOrigin(String value) {
        try { Uri u = Uri.parse(value); return u.getScheme() + "://" + u.getHost(); }
        catch (Exception e) { return "страница"; }
    }

    private void startObserver() {
        if (observerStarted || webView == null) return;
        observerStarted = true;
        main.postDelayed(observer, 2500L);
    }

    private final Runnable observer = new Runnable() {
        @Override public void run() {
            if (destroyed || webView == null || host.isFinishing() || host.isDestroyed()) return;
            String js = "(()=>{try{const v=[...document.querySelectorAll('video')].find(x=>"
                    + "x.getClientRects().length&&x.readyState>=2&&x.videoWidth>0&&"
                    + "!x.dataset.astraAliceCaptured);if(!v)return null;"
                    + "const u=v.currentSrc||v.src;if(!u)return null;"
                    + "v.dataset.astraAliceCaptured='1';return u;}catch(e){return null;}})()";
            webView.evaluateJavascript(js, raw -> {
                String source = decodeJsString(raw);
                if (source != null && !source.isEmpty() && !"null".equals(source)) {
                    captureBlob(source, "video/mp4");
                }
                if (!destroyed && webView != null) main.postDelayed(this, 3500L);
            });
        }
    };

    private String decodeJsString(String raw) {
        if (raw == null || "null".equals(raw)) return null;
        try { return new org.json.JSONArray("[" + raw + "]").optString(0, null); }
        catch (Exception e) { return null; }
    }

    private void captureBlob(String source, String mimeHint) {
        if (webView == null || !source.startsWith("blob:https://alice.yandex.ru/")) {
            setStatus("АЛИСА AI · ожидаю готовое видео");
            return;
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String js = "(async()=>{try{const u=" + org.json.JSONObject.quote(source) + ";"
                + "const r=await fetch(u);if(!r.ok)throw Error('fetch_'+r.status);"
                + "const b=await r.blob();if(!b.size||b.size>157286400)throw Error('invalid_size');"
                + "const id=" + org.json.JSONObject.quote(id) + ";"
                + "ASTRA_ALICE_CAPTURE.begin(id,b.type||'video/mp4',b.size);"
                + "const a=new Uint8Array(await b.arrayBuffer());const step=49152;"
                + "for(let i=0;i<a.length;i+=step){let s='';const p=a.subarray(i,Math.min(i+step,a.length));"
                + "for(let j=0;j<p.length;j++)s+=String.fromCharCode(p[j]);"
                + "ASTRA_ALICE_CAPTURE.chunk(id,btoa(s));}"
                + "ASTRA_ALICE_CAPTURE.finish(id);return 'captured';"
                + "}catch(e){ASTRA_ALICE_CAPTURE.fail(" + org.json.JSONObject.quote(id) + ",String(e));return 'error:'+String(e);}})()";
        setStatus("АЛИСА AI · получаю готовое видео...");
        webView.evaluateJavascript(js, value -> Log.i(TAG, "capture result=" + value));
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_MEDIA) return;
        if (fileChooserCallback == null) return;
        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                results = new Uri[]{data.getClipData().getItemAt(0).getUri()};
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        fileChooserCallback.onReceiveValue(results);
        fileChooserCallback = null;
    }

    public void onHostPause() {
        if (webView != null) webView.onPause();
    }

    public void onHostResume() {
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            if (isAliceOrigin(webView.getUrl())) startObserver();
        }
    }

    public void dispose() {
        destroyed = true;
        main.removeCallbacks(observer);
        if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
        fileChooserCallback = null;
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("ASTRA_ALICE_CAPTURE");
            webView.destroy();
            webView = null;
        }
        synchronized (streams) {
            for (FileOutputStream stream : streams.values()) {
                try { stream.close(); } catch (Exception ignored) {}
            }
            streams.clear();
            for (File file : tempFiles.values()) if (file != null) file.delete();
            tempFiles.clear();
        }
    }

    private void setStatus(String text) {
        if (status != null) main.post(() -> { if (status != null) status.setText(text); });
    }

    private final class AliceCaptureBridge {
        @JavascriptInterface public void begin(String id, String mime, long size) {
            try {
                if (!"video/mp4".equalsIgnoreCase(mime) && (mime == null || !mime.startsWith("video/")))
                    throw new IllegalArgumentException("unsupported_mime");
                File dir = new File(host.getCacheDir(), "alice_media");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("cache_dir_failed");
                File file = new File(dir, "alice_" + id + ".mp4");
                synchronized (streams) {
                    streams.put(id, new FileOutputStream(file));
                    tempFiles.put(id, file);
                }
                setStatus("АЛИСА AI · принимаю видео (" + (size / 1048576L) + " МБ)");
            } catch (Exception e) { fail(id, String.valueOf(e)); }
        }

        @JavascriptInterface public void chunk(String id, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                synchronized (streams) {
                    FileOutputStream out = streams.get(id);
                    if (out == null) throw new IllegalStateException("capture_not_started");
                    out.write(bytes);
                }
            } catch (Exception e) { fail(id, String.valueOf(e)); }
        }

        @JavascriptInterface public void finish(String id) {
            File source;
            try {
                synchronized (streams) {
                    FileOutputStream out = streams.remove(id);
                    source = tempFiles.get(id);
                    if (out == null || source == null) throw new IllegalStateException("capture_missing");
                    out.flush();
                    out.close();
                }
                Uri saved = publishVideo(source);
                source.delete();
                synchronized (streams) { tempFiles.remove(id); }
                setStatus("АЛИСА AI · видео сохранено · Movies/ASTRA/Alice");
                ChatActivity.onAliceVideoReady(host, saved);
            } catch (Exception e) {
                fail(id, String.valueOf(e));
            }
        }

        @JavascriptInterface public void fail(String id, String reason) {
            synchronized (streams) {
                FileOutputStream out = streams.remove(id);
                if (out != null) try { out.close(); } catch (Exception ignored) {}
                File file = tempFiles.remove(id);
                if (file != null) file.delete();
            }
            Log.e(TAG, "Video capture failed: " + reason);
            setStatus("АЛИСА AI · ошибка сохранения: " + reason);
        }
    }

    private Uri publishVideo(File source) throws Exception {
        if (source == null || !source.isFile() || source.length() < 1)
            throw new IllegalStateException("alice_video_file_missing");
        String name = "alice_" + System.currentTimeMillis() + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ASTRA/Alice");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = host.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("media_store_insert_failed");
            try (InputStream in = new java.io.FileInputStream(source);
                 OutputStream out = host.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("media_store_output_failed");
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            } catch (Exception e) {
                host.getContentResolver().delete(uri, null, null);
                throw e;
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Video.Media.IS_PENDING, 0);
            host.getContentResolver().update(uri, ready, null, null);
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ASTRA/Alice");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("movies_dir_failed");
        File target = new File(dir, name);
        try (InputStream in = new java.io.FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        return FileProvider.getUriForFile(host, host.getPackageName() + ".fileprovider", target);
    }
}
