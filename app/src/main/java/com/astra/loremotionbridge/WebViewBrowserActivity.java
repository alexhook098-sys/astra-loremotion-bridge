package com.astra.loremotionbridge;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
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

import java.util.Locale;

public class WebViewBrowserActivity extends Activity {
    private static volatile WebViewBrowserActivity instance;
    private WebView webView;
    private TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static WebViewBrowserActivity getInstance() { return instance; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setText("ASTRA WebView: starting...");
        status.setPadding(24, 18, 24, 18);
        root.addView(status);

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        configure(webView);

        String initial = getIntent().getStringExtra("url");
        if (initial == null || initial.isEmpty()) initial = "about:blank";
        webView.loadUrl(initial);
        if (getIntent().getBooleanExtra("background", false)
                && !getIntent().getBooleanExtra("show", false)) sendToBackSoon();
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra("url");
        if (url != null && !url.isEmpty() && webView != null && !"about:blank".equals(url)) openUrl(url);
        if (intent.getBooleanExtra("background", false) && !intent.getBooleanExtra("show", false)) sendToBackSoon();
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

        wv.setWebChromeClient(new WebChromeClient());
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return false;
            }

            @Override public void onPageFinished(WebView view, String url) {
                updateStatus();
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
            webView.evaluateJavascript(
                    script, value -> callback.onResult(value, null));
        });
    }

    public String statusJson() {
        if (webView == null)
            return "{\"ok\":false,\"ready\":false,\"state\":\"stopped\"}";
        return String.format(Locale.US,
                "{\"ok\":true,\"ready\":true,\"url\":\"%s\",\"title\":\"%s\"}",
                esc(webView.getUrl()), esc(webView.getTitle()));
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
