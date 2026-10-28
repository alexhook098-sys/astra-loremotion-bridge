package com.astra.loremotionbridge;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
    private PowerManager.WakeLock flowWakeLock;
    private TextView status;
    private boolean hideAfterPageLoad;
    private boolean reloadMoveToBackAfterLoad;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static FlowWebViewActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            flowWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ASTRA:FlowGeneration"
            );
            flowWakeLock.setReferenceCounted(false);
            flowWakeLock.acquire();
            Log.i("ASTRA-FLOW", "Partial WakeLock acquired");
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setText("ASTRA Flow: starting...");
        status.setPadding(24, 18, 24, 18);
        root.addView(status);

        webView = new WebView(this);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT, false
            );
            Log.i("ASTRA-FLOW", "Renderer priority set to IMPORTANT; waivedWhenNotVisible=false");
        }
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
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
                if (reloadMoveToBackAfterLoad
                        && url != null
                        && url.equals(view.getUrl())) {
                    reloadMoveToBackAfterLoad = false;
                    main.postDelayed(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            moveTaskToBack(true);
                            Log.i("ASTRA-FLOW",
                                    "Background mode restored after reload page finished");
                        }
                    }, 250L);
                }
                if (hideAfterPageLoad) {
                    hideAfterPageLoad = false;
                    main.postDelayed(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            moveTaskToBack(true);
                            Log.i("ASTRA-FLOW", "Background mode: moved task to back after page load");
                        }
                    }, 250L);
                }
            }
        });

        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) {
            url = "https://flow.google.com/u/1/project/54203513-938c-412e-9974-9ddf842bd7d5";
        }
        hideAfterPageLoad = getIntent().getBooleanExtra("background", false);
        webView.loadUrl(url);
        if (hideAfterPageLoad) {
            hideAfterPageLoad = false;
            main.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    moveTaskToBack(true);
                    Log.i("ASTRA-FLOW", "Background mode: moved task to back after load start");
                }
            }, 250L);
        }
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

    public void openUrlInBackground(String url) {
        main.post(() -> {
            if (webView != null && url != null && !url.isEmpty()) {
                hideAfterPageLoad = false;
                webView.loadUrl(url);
                main.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        moveTaskToBack(true);
                        Log.i("ASTRA-FLOW", "Background mode: moved task to back after load start");
                    }
                }, 250L);
            }
        });
    }

    public void reloadInBackground() {
        main.post(() -> {
            if (webView == null || isFinishing() || isDestroyed()) {
                Log.w("ASTRA-FLOW", "reloadInBackground: WebView unavailable");
                return;
            }

            reloadMoveToBackAfterLoad = true;
            Log.i("ASTRA-FLOW", "Background reload requested: url=" + webView.getUrl());
            webView.reload();
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
        main.post(() -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finish();
            }
        });
    }

    @Override
    protected void onPause() {
        Log.i("ASTRA-FLOW", "Lifecycle onPause: url="
                + (webView != null ? webView.getUrl() : "null")
                + ", isFinishing=" + isFinishing());
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.i("ASTRA-FLOW", "Lifecycle onStop: url="
                + (webView != null ? webView.getUrl() : "null")
                + ", isFinishing=" + isFinishing());
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            Log.i("ASTRA-FLOW", "WebView resumed");
        }
    }

    @Override
    protected void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        if (flowWakeLock != null && flowWakeLock.isHeld()) {
            flowWakeLock.release();
            Log.i("ASTRA-FLOW", "Partial WakeLock released");
        }
        flowWakeLock = null;
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
