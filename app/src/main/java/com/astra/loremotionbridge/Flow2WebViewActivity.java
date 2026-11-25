package com.astra.loremotionbridge;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Independent Google Flow browser profile reserved for image generation. */
public final class Flow2WebViewActivity extends Activity {
    public static final String FLOW2_URL = "https://flow.google.com/";
    private WebView webView;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        // Must run before the first WebView is created in this process.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("flow2");
        }
        super.onCreate(state);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this,
                    "Google Flow 2 требует Android 9 или новее для отдельного профиля",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setText("ASTRA · GOOGLE FLOW 2 · отдельный профиль");
        status.setPadding(24, 18, 24, 18);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
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
            @Override public void onPageStarted(WebView view, String url,
                    android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                status.setText("ASTRA · GOOGLE FLOW 2 · открываю сервис…");
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                status.setText("ASTRA · GOOGLE FLOW 2 · " + url);
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        String url = getIntent() == null ? null : getIntent().getStringExtra("url");
        if (url == null || url.trim().isEmpty()) url = FLOW2_URL;
        webView.loadUrl(url);
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent == null ? null : intent.getStringExtra("url");
        if (webView != null && url != null && !url.trim().isEmpty()) webView.loadUrl(url);
    }
    @Override protected void onResume() {
        super.onResume();
        if (webView != null) { webView.onResume(); webView.resumeTimers(); }
    }
    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
