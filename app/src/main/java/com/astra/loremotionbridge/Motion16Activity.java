package com.astra.loremotionbridge;

import android.app.Activity;
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
    private final Handler main = new Handler(Looper.getMainLooper());

    public static Motion16Activity getInstance() { return instance; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;

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

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                updateStatus();
            }
        });

        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) url = PROJECT_URL;
        webView.loadUrl(url);
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

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra("url");
        if (url != null && !url.isEmpty() && webView != null) {
            webView.loadUrl(url);
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
