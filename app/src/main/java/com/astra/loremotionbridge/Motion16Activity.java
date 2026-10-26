package com.astra.loremotionbridge;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.os.Build;
import java.io.OutputStream;
import java.io.IOException;
import android.os.Environment;
import android.webkit.DownloadListener;
import android.content.Intent;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.List;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public class Motion16Activity extends Activity {
    public static final String PROJECT_URL =
        "https://motion16.com/dashboard/p/ew4AMl3PrSuuuXj_gzrgH?media=media-ew4AMl3PrSuuuXj_gzrgH-workspace-result-RuRJR5ub2Gc1y1OU74jJR-4b6a5f9d-27b3-463a-ac16-9cab2b12b17b";

    private static volatile Motion16Activity instance;
    private WebView webView;
    private ValueCallback<Uri[]> pendingFileCallback;
    private TextView status;
    private boolean hideAfterPageLoad;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static Motion16Activity getInstance() { return instance; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        hideAfterPageLoad = getIntent().getBooleanExtra("background", false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setText("ASTRA Motion16: starting...");
        status.setPadding(24, 18, 24, 18);
        root.addView(status);

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = filePathCallback;

                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, 16016);
                } catch (Exception e) {
                    ValueCallback<Uri[]> callback = pendingFileCallback;
                    pendingFileCallback = null;
                    if (callback != null) callback.onReceiveValue(null);
                    if (status != null) status.setText("Motion16: cannot open image picker");
                }
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                android.util.Log.i("ASTRA-MOTION16-DL",
                    "Download callback: url=" + url + ", mime=" + mimeType
                    + ", size=" + contentLength);

                if (url == null) {
                    if (status != null) status.setText("Motion16: empty download URL");
                    return;
                }
                if (url.startsWith("blob:")) {
                    startMotion16BlobDownload(url, mimeType);
                    return;
                }

                if (!url.startsWith("https://")) {
                    if (status != null) status.setText("Motion16: unsupported download URL");
                    return;
                }

                try {
                    DownloadManager.Request request =
                        new DownloadManager.Request(Uri.parse(url));
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (userAgent != null && !userAgent.isEmpty()) {
                        request.addRequestHeader("User-Agent", userAgent);
                    }
                    if (cookie != null && !cookie.isEmpty()) {
                        request.addRequestHeader("Cookie", cookie);
                    }
                    request.setMimeType(mimeType != null ? mimeType : "video/mp4");
                    request.setTitle("ASTRA Motion16 video");
                    request.setDescription("Downloading generated video");
                    request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setAllowedOverMetered(true);
                    request.setAllowedOverRoaming(true);
                    request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        "ASTRA_Motion16_" + System.currentTimeMillis() + ".mp4");

                    DownloadManager manager =
                        (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
                    long id = manager.enqueue(request);
                    if (status != null) status.setText(
                        "Motion16 download queued (id " + id + "). Check Android Downloads.");
                    android.util.Log.i("ASTRA-MOTION16-DL", "Download queued id=" + id);
                } catch (Exception e) {
                    android.util.Log.e("ASTRA-MOTION16-DL", "Download enqueue failed", e);
                    if (status != null) status.setText("Motion16 download error: " + e.getMessage());
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                updateStatus();
                if (hideAfterPageLoad) {
                    hideAfterPageLoad = false;
                    main.postDelayed(() -> {
                        if (!isFinishing()) moveTaskToBack(true);
                    }, 700);
                }
            }
        });

        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) url = PROJECT_URL;
        webView.loadUrl(url);
    }

    // Motion16-only blob exporter: bounded chunks, written directly to Downloads.
    private void startMotion16BlobDownload(String blobUrl, String mimeType) {
        if (webView == null || blobUrl == null ||
                !(blobUrl.startsWith("blob:https://motion16.com/") ||
                  blobUrl.startsWith("blob:https://www.motion16.com/"))) {
            if (status != null) status.setText("Motion16: blocked blob URL origin");
            android.util.Log.w("ASTRA-MOTION16-DL", "Blocked blob URL origin");
            return;
        }

        String init = "window.__astraMotion16BlobInitResult='';" +
            "(async()=>{try{" +
            "const u=" + org.json.JSONObject.quote(blobUrl) + ";" +
            "const r=await fetch(u);if(!r.ok)throw new Error('blob fetch failed');" +
            "const b=await r.blob();if(!b.size)throw new Error('empty blob');" +
            "if(b.size>1073741824)throw new Error('video exceeds 1 GiB safety limit');" +
            "window.__astraMotion16Blob={blob:b,size:b.size,type:b.type||'video/mp4'};" +
            "window.__astraMotion16BlobInitResult='OK|'+b.size+'|'+encodeURIComponent(b.type||'video/mp4');" +
            "}catch(e){window.__astraMotion16BlobInitResult='ERR|'+String(e&&e.message||e)}}" +
            ")();'STARTED'";

        main.post(() -> {
            if (webView == null) return;
            long deadline = System.currentTimeMillis() + 30000L;
            webView.evaluateJavascript(init, value ->
                pollMotion16BlobInit(mimeType, deadline));
        });
    }

    private void pollMotion16BlobInit(String mimeType, long deadline) {
        if (webView == null) {
            if (status != null) status.setText("Motion16 blob error: WebView closed");
            return;
        }
        webView.evaluateJavascript(
            "window.__astraMotion16BlobInitResult || ''", value -> {
                String result = jsString(value);
                if (result == null || result.isEmpty()) {
                    if (System.currentTimeMillis() >= deadline) {
                        if (status != null) status.setText("Motion16 blob error: preparation timed out");
                        android.util.Log.e("ASTRA-MOTION16-DL", "Blob init timed out");
                    } else {
                        main.postDelayed(() -> pollMotion16BlobInit(mimeType, deadline), 100);
                    }
                    return;
                }
                if (!result.startsWith("OK|")) {
                    String reason = result.startsWith("ERR|")
                        ? result.substring(4) : result;
                    if (status != null) status.setText("Motion16 blob error: " + reason);
                    android.util.Log.e("ASTRA-MOTION16-DL", "Blob init failed: " + reason);
                    return;
                }
                try {
                    String[] parts = result.split("\\|", 3);
                    long size = Long.parseLong(parts[1]);
                    String type = parts.length > 2
                        ? java.net.URLDecoder.decode(parts[2], "UTF-8") : mimeType;
                    beginMotion16BlobWrite(size, type);
                } catch (Exception e) {
                    if (status != null) status.setText("Motion16 blob metadata error");
                    android.util.Log.e("ASTRA-MOTION16-DL", "Bad blob metadata", e);
                }
            });
    }

    private void beginMotion16BlobWrite(long size, String mimeType) {
        if (Build.VERSION.SDK_INT < 29) {
            if (status != null) status.setText("Motion16 blob export requires Android 10+");
            return;
        }
        Uri target = null;
        OutputStream out = null;
        try {
            String type = mimeType != null && mimeType.startsWith("video/")
                ? mimeType : "video/mp4";
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                "ASTRA_Motion16_" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.MediaColumns.MIME_TYPE, type);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            target = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new IOException("MediaStore insert returned null");
            out = getContentResolver().openOutputStream(target, "w");
            if (out == null) throw new IOException("Cannot open output stream");
            if (status != null) status.setText("Motion16: saving video (" + size + " bytes)...");
            pumpMotion16BlobChunk(target, out, size, 0L);
        } catch (Exception e) {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            if (target != null) try { getContentResolver().delete(target, null, null); } catch (Exception ignored) {}
            if (status != null) status.setText("Motion16 blob save error: " + e.getMessage());
            android.util.Log.e("ASTRA-MOTION16-DL", "Cannot create output", e);
        }
    }

    private void pumpMotion16BlobChunk(Uri target, OutputStream out, long size, long offset) {
        final int chunkSize = 24576;
        if (offset >= size) {
            try {
                out.close();
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(target, done, null, null);
                if (webView != null) webView.evaluateJavascript(
                    "delete window.__astraMotion16Blob;" +
                    "delete window.__astraMotion16BlobInitResult;" +
                    "delete window.__astraMotion16ChunkResult", null);
                if (status != null) status.setText("Motion16 video saved to Downloads.");
                android.util.Log.i("ASTRA-MOTION16-DL", "Blob saved, bytes=" + size);
            } catch (Exception e) {
                if (status != null) status.setText("Motion16 finalize error: " + e.getMessage());
                android.util.Log.e("ASTRA-MOTION16-DL", "Finalize failed", e);
            }
            return;
        }

        String js = "window.__astraMotion16ChunkResult='';" +
            "(async()=>{try{const x=window.__astraMotion16Blob;" +
            "if(!x)throw new Error('blob state missing');" +
            "const a=await x.blob.slice(" + offset + "," +
            Math.min(size, offset + chunkSize) + ").arrayBuffer();" +
            "let s='';const u=new Uint8Array(a);" +
            "for(let i=0;i<u.length;i++)s+=String.fromCharCode(u[i]);" +
            "window.__astraMotion16ChunkResult='CHUNK|'+btoa(s)" +
            "}catch(e){window.__astraMotion16ChunkResult='ERR|'+String(e&&e.message||e)}}" +
            ")();'STARTED'";

        main.post(() -> {
            if (webView == null) {
                failMotion16Blob(target, out, "WebView closed during blob export");
                return;
            }
            long deadline = System.currentTimeMillis() + 15000L;
            webView.evaluateJavascript(js, value ->
                pollMotion16BlobChunk(target, out, size, offset, deadline));
        });
    }

    private void pollMotion16BlobChunk(
            Uri target, OutputStream out, long size, long offset, long deadline) {
        if (webView == null) {
            failMotion16Blob(target, out, "WebView closed during blob export");
            return;
        }
        webView.evaluateJavascript(
            "window.__astraMotion16ChunkResult || ''", value -> {
                String chunk = jsString(value);
                if (chunk == null || chunk.isEmpty()) {
                    if (System.currentTimeMillis() >= deadline) {
                        failMotion16Blob(target, out, "chunk read timed out");
                    } else {
                        main.postDelayed(() ->
                            pollMotion16BlobChunk(target, out, size, offset, deadline), 100);
                    }
                    return;
                }
                if (!chunk.startsWith("CHUNK|")) {
                    String reason = chunk.startsWith("ERR|")
                        ? chunk.substring(4) : chunk;
                    failMotion16Blob(target, out, reason);
                    return;
                }
                try {
                    byte[] bytes = android.util.Base64.decode(
                        chunk.substring(6), android.util.Base64.DEFAULT);
                    if (bytes.length == 0) throw new IOException("zero-length chunk");
                    out.write(bytes);
                    pumpMotion16BlobChunk(target, out, size, offset + bytes.length);
                } catch (Exception e) {
                    failMotion16Blob(target, out, "write failed: " + e.getMessage());
                }
            });
    }

    private void failMotion16Blob(Uri target, OutputStream out, String reason) {
        try { out.close(); } catch (Exception ignored) {}
        try { getContentResolver().delete(target, null, null); } catch (Exception ignored) {}
        if (webView != null) webView.evaluateJavascript("delete window.__astraMotion16Blob", null);
        if (status != null) status.setText("Motion16 blob export failed: " + reason);
        android.util.Log.e("ASTRA-MOTION16-DL", "Blob export failed: " + reason);
    }

    private static String jsString(String value) {
        if (value == null || "null".equals(value)) return null;
        try {
            Object parsed = new org.json.JSONTokener(value).nextValue();
            return parsed instanceof String ? (String) parsed : String.valueOf(parsed);
        } catch (Exception e) {
            return value;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 16016) return;

        ValueCallback<Uri[]> callback = pendingFileCallback;
        pendingFileCallback = null;
        if (callback == null) return;

        Uri[] result = null;
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            result = new Uri[]{data.getData()};
        }
        callback.onReceiveValue(result);
    }

    public void downloadReadyVideo() {
        main.post(() -> {
            if (webView == null) {
                if (status != null) status.setText("Motion16: WebView not ready");
                return;
            }

            String script = "(function(){"
                + "const out=[];const seen=new Set();"
                + "function add(u,t){if(u&&!seen.has(u)){seen.add(u);out.push({url:u,type:t||'video/mp4'});}}"
                + "document.querySelectorAll('video').forEach(v=>{"
                + "add(v.currentSrc,v.getAttribute('type'));add(v.src,v.getAttribute('type'));"
                + "v.querySelectorAll('source').forEach(x=>add(x.src,x.type));});"
                + "document.querySelectorAll('a[href]').forEach(a=>{"
                + "const u=a.href;if(u.startsWith('blob:')||/\\.mp4(?:[?#]|$)/i.test(u))add(u,a.type);});"
                + "return JSON.stringify(out);"
                + "})()";

            webView.evaluateJavascript(script, value -> {
                String json = jsString(value);
                try {
                    org.json.JSONArray urls = new org.json.JSONArray(json == null ? "[]" : json);
                    for (int i = 0; i < urls.length(); i++) {
                        org.json.JSONObject item = urls.getJSONObject(i);
                        String url = item.optString("url", "");
                        String mime = item.optString("type", "video/mp4");

                        if (url.startsWith("blob:https://motion16.com/")
                                || url.startsWith("blob:https://www.motion16.com/")) {
                            startMotion16BlobDownload(url, mime);
                            return;
                        }

                        if (url.startsWith("https://")) {
                            try {
                                DownloadManager.Request request =
                                    new DownloadManager.Request(Uri.parse(url));
                                String cookie = CookieManager.getInstance().getCookie(url);
                                String ua = webView.getSettings().getUserAgentString();

                                if (ua != null && !ua.isEmpty()) request.addRequestHeader("User-Agent", ua);
                                if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);

                                request.setMimeType(mime.isEmpty() ? "video/mp4" : mime);
                                request.setTitle("ASTRA Motion16 video");
                                request.setDescription("Downloading generated video");
                                request.setNotificationVisibility(
                                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                request.setAllowedOverMetered(true);
                                request.setAllowedOverRoaming(true);
                                request.setDestinationInExternalPublicDir(
                                    Environment.DIRECTORY_DOWNLOADS,
                                    "ASTRA_Motion16_" + System.currentTimeMillis() + ".mp4");

                                DownloadManager manager =
                                    (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                                if (manager == null) throw new IllegalStateException("DownloadManager unavailable");

                                long id = manager.enqueue(request);
                                if (status != null) status.setText(
                                    "Motion16 download queued (id " + id + "). Check Android Downloads.");
                                android.util.Log.i("ASTRA-MOTION16-DL", "Ready video queued id=" + id);
                                return;
                            } catch (Exception e) {
                                android.util.Log.e("ASTRA-MOTION16-DL", "Ready video enqueue failed", e);
                                if (status != null) status.setText("Motion16 download error: " + e.getMessage());
                                return;
                            }
                        }
                    }

                    if (status != null) status.setText(
                        "Motion16: no downloadable video URL found in page DOM");
                    android.util.Log.w("ASTRA-MOTION16-DL", "No downloadable video URL found in DOM");
                } catch (Exception e) {
                    android.util.Log.e("ASTRA-MOTION16-DL", "Could not inspect page video URLs", e);
                    if (status != null) status.setText("Motion16: could not inspect video URLs");
                }
            });
        });
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        hideAfterPageLoad = intent.getBooleanExtra("background", false);
        String url = intent.getStringExtra("url");
        if (url != null && !url.isEmpty() && webView != null) {
            webView.loadUrl(url);
        } else if (hideAfterPageLoad && webView != null) {
            hideAfterPageLoad = false;
            main.postDelayed(() -> {
                if (!isFinishing()) moveTaskToBack(true);
            }, 700);
        }
    }

    public void evaluateScript(String script, android.webkit.ValueCallback<String> callback) {
        main.post(() -> {
            if (webView == null) {
                if (callback != null) callback.onReceiveValue("\"WEBVIEW_NOT_READY\"");
                return;
            }
            webView.evaluateJavascript(script, callback);
        });
    }

    public void openUrl(String url) {
        main.post(() -> {
            if (webView != null) webView.loadUrl(url);
        });
    }

    public void minimizeMotion16() {
        main.post(() -> {
            if (!isFinishing()) moveTaskToBack(true);
        });
    }

    public String statusJson() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return statusJsonOnMain();
        }

        final Object lock = new Object();
        final String[] result = new String[1];
        final boolean[] done = new boolean[1];

        main.post(() -> {
            String value = statusJsonOnMain();
            synchronized (lock) {
                result[0] = value;
                done[0] = true;
                lock.notifyAll();
            }
        });

        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 3000L;
            while (!done[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) break;
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "{\"ok\":false,\"error\":\"status_interrupted\"}";
                }
            }
        }

        return done[0] ? result[0]
            : "{\"ok\":false,\"error\":\"status_timeout\"}";
    }

    private String statusJsonOnMain() {
        if (webView == null)
            return "{\"ok\":true,\"ready\":false,\"state\":\"stopped\"}";

        return String.format(Locale.US,
            "{\"ok\":true,\"ready\":true,\"url\":\"%s\",\"title\":\"%s\"}",
            esc(webView.getUrl()), esc(webView.getTitle()));
    }

    private void updateStatus() {
        if (status != null && webView != null) {
            status.setText("ASTRA Motion16\n\n" +
                webView.getTitle() + "\n" + webView.getUrl());
        }
    }

    @Override protected void onPause() {
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
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
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
