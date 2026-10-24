package com.astra.loremotionbridge;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class FlowWebViewActivity extends Activity {
    private static volatile FlowWebViewActivity instance;
    private WebView webView;
    private TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static FlowWebViewActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setText("ASTRA Flow: starting...");
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
        settings.setMediaPlaybackRequiresUserGesture(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                status.setText("ASTRA Flow: " + url);
                Log.i("ASTRA-FLOW", "Page finished: " + url);
            }
        });

        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) {
            url = "https://flow.google.com/project/f40f4fe3-b63d-4e8a-82cd-4afb2e30558d";
        }
        webView.loadUrl(url);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra("url");
        if (url != null && !url.isEmpty() && webView != null) {
            openUrl(url);
        }
    }

    public void openUrl(String url) {
        main.post(() -> {
            if (webView != null && url != null && !url.isEmpty()) {
                webView.loadUrl(url);
            }
        });
    }

    public void evaluate(String script, android.webkit.ValueCallback<String> callback) {
        main.post(() -> {
            if (webView == null) {
                callback.onReceiveValue(null);
                return;
            }
            webView.evaluateJavascript(script, callback);
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
            String json = statusJsonOnMain();
            synchronized (lock) {
                result[0] = json;
                done[0] = true;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            try {
                if (!done[0]) lock.wait(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{\"ok\":false,\"error\":\"interrupted\"}";
            }
        }
        return done[0] ? result[0]
                : "{\"ok\":false,\"error\":\"flow_status_timeout\"}";
    }

    private String statusJsonOnMain() {
        if (webView == null) {
            return "{\"ok\":true,\"ready\":false,\"state\":\"stopped\"}";
        }
        String url = webView.getUrl();
        String title = webView.getTitle();
        return "{\"ok\":true,\"ready\":" + (url != null && !url.isEmpty() && !"about:blank".equals(url))
                + ",\"url\":\"" + esc(url) + "\",\"title\":\"" + esc(title) + "\"}";
    }

    public void minimizeFlow() {
        main.post(() -> moveTaskToBack(true));
    }

    public void closeFlow() {
        main.post(this::finish);
    }

    @Override
    protected void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
