package com.astra.loremotionbridge;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Independent Leonardo web session. Does not share or modify Flow/LoreMotion state. */
public final class LeonardoWebViewActivity extends Activity {
    public static final String DEFAULT_URL =
        "https://app.leonardo.ai/generate?model=auto-preset-video&seedEnabled=false"
        + "&negativePromptEnabled=false&aspectRatio=9:16&duration=5&quantity=1";
    private static volatile LeonardoWebViewActivity instance;
    private WebView webView;
    private TextView status;
    private volatile String lifecycle = "created";
    private volatile String lastDownload = "";

    public static LeonardoWebViewActivity getInstance() { return instance; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setText("ASTRA Leonardo: opening website…");
        status.setPadding(20, 14, 20, 14);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        webView = new WebView(this);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                lifecycle = "pageStarted";
                status.setText("ASTRA Leonardo: loading " + compactUrl(url));
            }
            @Override public void onPageFinished(WebView view, String url) {
                lifecycle = "pageFinished";
                status.setText("ASTRA Leonardo: " + compactUrl(url));
                Log.i("ASTRA-LEONARDO", "Page finished: " + url);
            }
        });
        webView.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                                  String mimetype, long contentLength) {
                startDownload(url, userAgent, contentDisposition, mimetype);
            }
        });
        String url = getIntent().getStringExtra("url");
        if (url == null || url.trim().isEmpty()) url = DEFAULT_URL;
        webView.loadUrl(url);
    }

    // Keep long OAuth URLs from expanding the activity header.
    private static String compactUrl(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return value.length() > 100 ? value.substring(0, 100) + "…" : value;
        }
        String path = uri.getPath();
        String label = host + ((path == null || path.isEmpty() || "/".equals(path)) ? "" : path);
        return label.length() > 100 ? label.substring(0, 97) + "…" : label;
    }

    private void startDownload(String url, String userAgent, String disposition, String mime) {
        try {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
                lastDownload = "unsupported_download_url";
                Log.w("ASTRA-LEONARDO", "Download URL is not HTTP(S): " + url);
                return;
            }
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) { lastDownload = "download_manager_unavailable"; return; }
            Uri uri = Uri.parse(url);
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "ASTRA_Leonardo_" + System.currentTimeMillis() + extensionFor(mime, url));
            request.setTitle("ASTRA Leonardo video");
            request.setDescription("Downloading generated Leonardo result");
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            if (mime != null && !mime.isEmpty()) request.setMimeType(mime);
            if (userAgent != null && !userAgent.isEmpty()) request.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
            long id = manager.enqueue(request);
            lastDownload = "queued:" + id;
            Log.i("ASTRA-LEONARDO", "Download queued id=" + id + " url=" + url);
        } catch (Exception e) {
            lastDownload = "error:" + e.getClass().getSimpleName();
            Log.e("ASTRA-LEONARDO", "Could not enqueue download", e);
        }
    }

    private static String extensionFor(String mime, String url) {
        String lower = (url == null ? "" : url).toLowerCase();
        if (lower.contains(".mp4") || (mime != null && mime.toLowerCase().contains("mp4"))) return ".mp4";
        if (lower.contains(".webm") || (mime != null && mime.toLowerCase().contains("webm"))) return ".webm";
        return ".mp4";
    }

    public void openUrl(String url) {
        runOnUiThread(() -> { if (webView != null && url != null && !url.isEmpty()) webView.loadUrl(url); });
    }
    public void evaluate(String script, android.webkit.ValueCallback<String> callback) {
        runOnUiThread(() -> {
            if (webView == null) callback.onReceiveValue(null);
            else webView.evaluateJavascript(script == null ? "document.title" : script, callback);
        });
    }
    public String statusJson() {
        String url = webView == null ? "" : webView.getUrl();
        String title = webView == null ? "" : webView.getTitle();
        return "{\"ok\":true,\"ready\":" + (url != null && !url.isEmpty() && !"about:blank".equals(url))
            + ",\"state\":\"" + esc(lifecycle) + "\",\"url\":\"" + esc(url)
            + "\",\"title\":\"" + esc(title) + "\",\"lastDownload\":\"" + esc(lastDownload) + "\"}";
    }


    public String armOriginalVideoWatch() {
        final Object lock = new Object();
        final String[] result = new String[1];
        final boolean[] done = new boolean[1];

        final String script =
            "(function(){"
            + "var urls=[].slice.call(document.querySelectorAll('video'))"
            + ".map(function(v){return v.currentSrc||v.src||'';})"
            + ".filter(Boolean);"
            + "window.__astraLeonardoKnownVideoUrls=urls;"
            + "return JSON.stringify({ok:true,count:urls.length});"
            + "})()";

        evaluate(script, value -> {
            synchronized (lock) {
                result[0] = value;
                done[0] = true;
                lock.notifyAll();
            }
        });

        synchronized (lock) {
            try {
                final long waitDeadline = System.currentTimeMillis() + 5000L;
                while (!done[0]) {
                    long remaining = waitDeadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    lock.wait(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{\"ok\":false,\"error\":\"interrupted\"}";
            }
        }

        if (!done[0] || result[0] == null) {
            return "{\"ok\":false,\"error\":\"video_baseline_timeout\"}";
        }

        try {
            Object decoded = new org.json.JSONTokener(result[0]).nextValue();
            if (!(decoded instanceof String)) {
                return "{\"ok\":false,\"error\":\"video_baseline_invalid\"}";
            }
            Object payload = new org.json.JSONTokener((String) decoded).nextValue();
            return payload instanceof org.json.JSONObject
                ? payload.toString()
                : "{\"ok\":false,\"error\":\"video_baseline_invalid\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"video_baseline_decode_failed\"}";
        }
    }

    public String downloadOriginalVideoFromPage() {
        final String script =
            "(function(){"
            + "var known=window.__astraLeonardoKnownVideoUrls;"
            + "if(!Array.isArray(known))return 'ASTRA_WATCH_NOT_ARMED';"
            + "var vs=[].slice.call(document.querySelectorAll('video'));"
            + "for(var i=vs.length-1;i>=0;i--){"
            + "var u=vs[i].currentSrc||vs[i].src||'';"
            + "if(u&&!known.includes(u)"
            + "&&/cdn\\.leonardo\\.ai/i.test(u)"
            + "&&/generations/i.test(u)"
            + "&&/\\.mp4(?:$|[?#])/i.test(u))return u;"
            + "}"
            + "return '';"
            + "})()";

        final long deadline = System.currentTimeMillis() + 20000L;

        while (System.currentTimeMillis() < deadline) {
            final Object lock = new Object();
            final String[] result = new String[1];
            final boolean[] done = new boolean[1];

            evaluate(script, value -> {
                synchronized (lock) {
                    result[0] = value;
                    done[0] = true;
                    lock.notifyAll();
                }
            });

            synchronized (lock) {
                try {
                    long remaining = deadline - System.currentTimeMillis();
                    if (!done[0] && remaining > 0) {
                        lock.wait(Math.min(1000L, remaining));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "{\"ok\":false,\"error\":\"interrupted\"}";
                }
            }

            if (!done[0] || result[0] == null) {
                if (System.currentTimeMillis() >= deadline) break;
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "{\"ok\":false,\"error\":\"interrupted\"}";
                }
                continue;
            }

            try {
                Object decoded = new org.json.JSONTokener(result[0]).nextValue();
                String url = decoded instanceof String ? (String) decoded : "";

                if ("ASTRA_WATCH_NOT_ARMED".equals(url)) {
                    return "{\"ok\":false,\"error\":\"watch_not_armed\"}";
                }
                if (!url.isEmpty()) {
                    return downloadOriginalVideo(url);
                }
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"video_url_decode_failed\"}";
            }

            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0) {
                try {
                    Thread.sleep(Math.min(1000L, remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "{\"ok\":false,\"error\":\"interrupted\"}";
                }
            }
        }

        return "{\"ok\":false,\"error\":\"new_video_not_found\",\"waitSeconds\":20}";
    }

    public String downloadOriginalVideo(String candidateUrl) {
        try {
            if (candidateUrl == null || candidateUrl.trim().isEmpty()) {
                return "{\"ok\":false,\"error\":\"original_video_url_missing\"}";
            }
            String url = candidateUrl.trim();

            // Unwrap the known Cloudflare media proxy form, if present.
            String marker = "/https://cdn.leonardo.ai/";
            int markerAt = url.indexOf(marker);
            if (url.startsWith("https://cdn.leonardo.ai/cdn-cgi/media/") && markerAt >= 0) {
                url = url.substring(markerAt + 1);
            }

            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"cdn.leonardo.ai".equalsIgnoreCase(host)
                    || path == null
                    || !path.contains("/generations/")
                    || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
                return "{\"ok\":false,\"error\":\"invalid_original_video_url\"}";
            }

            startDownload(url, "", "attachment", "video/mp4");
            String state = lastDownload;
            if (state != null && state.startsWith("queued:")) {
                return "{\"ok\":true,\"action\":\"original_download_queued\",\"download\":\""
                    + esc(state) + "\"}";
            }
            return "{\"ok\":false,\"error\":\"original_download_not_queued\",\"detail\":\""
                + esc(state) + "\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"original_download_error\",\"detail\":\""
                + esc(e.getClass().getSimpleName()) + "\"}";
        }
    }

    public String clickDownloadButton() {
        final String script = "(function(){" +
            "var all=[].slice.call(document.querySelectorAll('button,[role=button],a'));" +
            "var matches=all.filter(function(e){var s=((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.innerText||'')).toLowerCase();return /download|скачать/.test(s);});" +
            "if(!matches.length)return JSON.stringify({ok:false,error:'download_button_not_found',url:location.href});" +
            "var e=matches[0];e.click();return JSON.stringify({ok:true,action:'download_button_clicked',count:matches.length,label:(e.getAttribute('aria-label')||e.getAttribute('title')||e.innerText||'').trim().slice(0,120)});" +
            "})()";
        final Object lock = new Object(); final String[] result = new String[1]; final boolean[] done = new boolean[1];
        evaluate(script, value -> { synchronized(lock) { result[0]=value; done[0]=true; lock.notifyAll(); } });
        synchronized(lock) { try { if(!done[0]) lock.wait(8000L); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
        return done[0] && result[0] != null ? "{\"ok\":true,\"value\":" + result[0] + "}" : "{\"ok\":false,\"error\":\"leonardo_download_eval_timeout\"}";
    }
    public void minimize() { runOnUiThread(() -> moveTaskToBack(true)); }
    public void closeLeonardo() { runOnUiThread(() -> { if (android.os.Build.VERSION.SDK_INT >= 21) finishAndRemoveTask(); else finish(); }); }
    public void bringToFront() {
        runOnUiThread(() -> { if (webView != null) { webView.onResume(); webView.resumeTimers(); } });
    }
    @Override protected void onResume() { super.onResume(); lifecycle="onResume"; if(webView!=null){webView.onResume();webView.resumeTimers();} }
    @Override protected void onPause() { lifecycle="onPause"; super.onPause(); }
    @Override protected void onDestroy() {
        lifecycle="onDestroy"; if(instance==this) instance=null;
        if(webView!=null){webView.stopLoading();webView.destroy();webView=null;} super.onDestroy();
    }
    private static String esc(String value) {
        if(value==null)return "";
        return value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
    }
}
