package com.astra.loremotionbridge;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persistent Alice Media WebView. Back detaches the view without destroying its session. */
public final class AliceMediaActivity extends Activity {
    private static final String TAG = "ASTRA-ALICE-MIN";
    private static final String HOME = "https://alice.yandex.ru/media";
    private static WebView sharedWebView;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor();
    private static final Set<String> BASELINE_IDS = new HashSet<>();
    private static final Set<String> DOWNLOADED_IDS = new HashSet<>();
    private static boolean monitorStarted;
    private WebView webView;

    private static final Runnable MONITOR = new Runnable() {
        @Override public void run() {
            WebView w = sharedWebView;
            if (w == null) { MAIN.postDelayed(this, 5000L); return; }
            String js = "(function(){var u=[];document.querySelectorAll('video,video source,a[href]').forEach(e=>{var x=e.currentSrc||e.src||e.href||'';if(x)u.push(x)});"
                    + "performance.getEntriesByType('resource').forEach(e=>{if(/webalice|yaart-images|video/i.test(e.name))u.push(e.name)});"
                    + "var ids=new Set();u.forEach(x=>{var m=String(x).match(/(?:[?&]video=|\\/webalice\\/)([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase())});"
                    + "var m=location.href.match(/[?&]video=([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase());return JSON.stringify([...ids])})()";
            try {
                w.evaluateJavascript(js, raw -> {
                    try {
                        Object parsed = new org.json.JSONTokener(raw).nextValue();
                        JSONArray ids = parsed instanceof String ? new JSONArray((String) parsed) : new JSONArray();
                        for (int i=0;i<ids.length();i++) {
                            String id = ids.optString(i).toLowerCase(Locale.US);
                            if (id.matches("[0-9a-f]{32}") && !BASELINE_IDS.contains(id) && DOWNLOADED_IDS.add(id)) {
                                DOWNLOADS.execute(() -> downloadAndDeliver(id));
                            }
                        }
                    } catch (Exception e) { Log.w(TAG, "Alice media scan parse failed", e); }
                });
            } catch (Exception e) { Log.w(TAG, "Alice media scan unavailable", e); }
            MAIN.postDelayed(this, 5000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8,11,24));
        getWindow().setNavigationBarColor(Color.rgb(8,11,24));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8,11,24));
        root.setPadding(dp(12),dp(10),dp(12),dp(8));
        TextView title = new TextView(this);
        title.setText("✦  ASTRA · ALICE MEDIA");
        title.setTextColor(Color.rgb(229,199,255));
        title.setTextSize(18);
        root.addView(title,new LinearLayout.LayoutParams(-1,dp(42)));
        TextView hint = new TextView(this);
        hint.setText("Вернуться в чат можно кнопкой «Назад». Страница и вход сохраняются.");
        hint.setTextColor(Color.rgb(145,160,188));
        hint.setTextSize(11);
        root.addView(hint,new LinearLayout.LayoutParams(-1,dp(34)));
        if (sharedWebView == null) {
            sharedWebView = new WebView(getApplicationContext());
            WebSettings settings = sharedWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setLoadsImagesAutomatically(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);
            settings.setSupportMultipleWindows(false);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(sharedWebView,true);
            sharedWebView.setWebChromeClient(new WebChromeClient());
            sharedWebView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view,String url) {
                    super.onPageFinished(view,url);
                    if (!monitorStarted) {
                        // Snapshot IDs already present before the user starts a new animation.
                        String baselineScript = "(function(){var u=[];document.querySelectorAll('video,video source,a[href]').forEach(e=>{var x=e.currentSrc||e.src||e.href||'';if(x)u.push(x)});"
                                + "performance.getEntriesByType('resource').forEach(e=>{if(/webalice|yaart-images|video/i.test(e.name))u.push(e.name)});"
                                + "var ids=new Set();u.forEach(x=>{var m=String(x).match(/(?:[?&]video=|\\\\/webalice\\\\/)([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase())});"
                                + "var m=location.href.match(/[?&]video=([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase());return JSON.stringify([...ids])})()";
                        view.evaluateJavascript(baselineScript, raw -> {
                            try {
                                Object parsed = new org.json.JSONTokener(raw).nextValue();
                                JSONArray ids = parsed instanceof String ? new JSONArray((String) parsed) : new JSONArray();
                                for (int i=0;i<ids.length();i++) BASELINE_IDS.add(ids.optString(i).toLowerCase(Locale.US));
                            } catch (Exception e) { Log.w(TAG, "Alice baseline scan failed", e); }
                            monitorStarted = true;
                            MAIN.post(MONITOR);
                        });
                    }
                }
            });
            sharedWebView.loadUrl(HOME);
        }
        webView = sharedWebView;
        if (webView.getParent() instanceof ViewGroup) ((ViewGroup)webView.getParent()).removeView(webView);
        root.addView(webView,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        webView.onResume();
        webView.resumeTimers();
    }

    private static void downloadAndDeliver(String id) {
        try {
            JSONObject result = new JSONObject(AliceMediaAgent.download(MainApplication.context(),id));
            if (result.optBoolean("ok")) {
                Uri uri = Uri.parse(result.getString("uri"));
                ChatActivity.onAliceVideoReady(MainApplication.context(),uri);
                Log.i(TAG,"Downloaded and delivered MP4 id="+id);
            } else {
                DOWNLOADED_IDS.remove(id);
                Log.w(TAG,"Download failed id="+id+" error="+result.optString("error"));
            }
        } catch (Exception e) {
            DOWNLOADED_IDS.remove(id);
            Log.e(TAG,"Download failed",e);
        }
    }

    private int dp(int v) { return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

    @Override protected void onDestroy() {
        if (webView != null) {
            if (webView.getParent() instanceof ViewGroup) ((ViewGroup)webView.getParent()).removeView(webView);
            // Deliberately retain the WebView and monitor, matching RadioActivity's persistent player pattern.
            webView = null;
        }
        super.onDestroy();
    }
}