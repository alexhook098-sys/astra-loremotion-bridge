package com.astra.loremotionbridge;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public class Motion16Activity extends Activity {
    public static final String PROJECT_URL =
        "https://motion16.com/dashboard/p/ew4AMl3PrSuuuXj_gzrgH?media=media-ew4AMl3PrSuuuXj_gzrgH-workspace-result-RuRJR5ub2Gc1y1OU74jJR-4b6a5f9d-27b3-463a-ac16-9cab2b12b17b";

    private static volatile Motion16Activity instance;
    private WebView webView;
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

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                updateStatus();
            }
        });

        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) url = PROJECT_URL;
        webView.loadUrl(url);
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra("url");
        if (url != null && !url.isEmpty() && webView != null) {
            webView.loadUrl(url);
        }
    }

    public void openUrl(String url) {
        main.post(() -> {
            if (webView != null) webView.loadUrl(url);
        });
    }

    public String statusJson() {
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
