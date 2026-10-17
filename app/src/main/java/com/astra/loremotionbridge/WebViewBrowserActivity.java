package com.astra.loremotionbridge;

import android.util.Log;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.os.Bundle;
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
import java.io.IOException;
import java.util.Locale;

public class WebViewBrowserActivity extends Activity {
    private static volatile WebViewBrowserActivity instance;
    private WebView webView;
    private boolean hideAfterPageLoad;
    private TextView status;
    private ValueCallback<Uri[]> pendingFileCallback;
    private String pendingUploadPath;
    private final Handler main = new Handler(Looper.getMainLooper());

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
        Log.i("ASTRA-WV-DIAG", "Activity loading URL=" + initial + " background=" + hideAfterPageLoad);
        webView.loadUrl(initial);
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra("url");
        if (url != null && !url.isEmpty() && webView != null && !"about:blank".equals(url)) openUrl(url);
    }

    private void sendToBackSoon() {
        main.postDelayed(() -> { if (!isFinishing()) { try { moveTaskToBack(true); } catch (Exception ignored) {} } }, 250L);
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

                if (pendingUploadPath != null && !pendingUploadPath.isEmpty()) {
                    deliverPendingUpload();
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

                File target = new File(
                        uploadDir,
                        "upload-" + System.currentTimeMillis() + ".jpg");

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
                setStatus("Upload prepared: " + target.getName());

            } catch (Exception e) {
                setStatus("Upload error: " + e.getMessage());
                callback.onReceiveValue(null);
            }
        });
    }

    public void setPendingUpload(final String path) {
        main.post(() -> {
            pendingUploadPath = path;
            setStatus("Upload waiting for file chooser");
        });
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

    public void openUrlInBackground(final String url) {
        main.post(() -> {
            if (webView != null) {
                hideAfterPageLoad = true;
                webView.loadUrl(url);
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
                "images:Array.from(document.images).filter(e=>e.getClientRects().length).slice(0,40).map(e=>({" +
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

    private void updateStatus() {
        if (status != null && webView != null)
            status.setText("ASTRA WebView\n\n" +
                    webView.getTitle() + "\n" + webView.getUrl());
    }

    private void setStatus(String text) {
        main.post(() -> {
            if (status != null) status.setText("ASTRA WebView\n\n" + text);
        });
    }

    @Override protected void onDestroy() {
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
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public interface EvalCallback {
        void onResult(String value, String error);
    }
}
