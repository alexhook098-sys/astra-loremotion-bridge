package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
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
    private ValueCallback<Uri[]> pendingFileCallback;
    private static final int ALICE_FILE_CHOOSER = 4817;

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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                sharedWebView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            }
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setLoadsImagesAutomatically(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);
            settings.setSupportMultipleWindows(false);
            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setAcceptThirdPartyCookies(sharedWebView,true);
            sharedWebView.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                        FileChooserParams params) {
                    if (pendingFileCallback != null) {
                        try { pendingFileCallback.onReceiveValue(null); } catch (Exception ignored) {}
                    }
                    pendingFileCallback = callback;
                    Intent pick;
                    try {
                        pick = params != null ? params.createIntent() : new Intent(Intent.ACTION_GET_CONTENT);
                        if (pick.getType() == null) pick.setType("image/*");
                        pick.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(Intent.createChooser(pick, "Выбрать фото для Alice"), ALICE_FILE_CHOOSER);
                        Log.i(TAG, "Alice file chooser opened");
                        return true;
                    } catch (ActivityNotFoundException e) {
                        Log.e(TAG, "No file picker available", e);
                    } catch (Exception e) {
                        Log.e(TAG, "Could not open Alice file picker", e);
                    }
                    pendingFileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
            });
            sharedWebView.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    Log.i(TAG, "Navigation started: " + safeUrl(url));
                    super.onPageStarted(view, url, favicon);
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    if (request != null && request.isForMainFrame()) {
                        Log.e(TAG, "Main-frame load error: url=" + safeUrl(String.valueOf(request.getUrl()))
                                + " code=" + (error == null ? -1 : error.getErrorCode())
                                + " description=" + (error == null ? "unknown" : error.getDescription()));
                    }
                }
                @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                    super.onReceivedHttpError(view, request, response);
                    if (request != null && request.isForMainFrame()) {
                        Log.e(TAG, "Main-frame HTTP error: url=" + safeUrl(String.valueOf(request.getUrl()))
                                + " status=" + (response == null ? -1 : response.getStatusCode()));
                    }
                }
                @Override public void onPageFinished(WebView view,String url) {
                    Log.i(TAG, "Navigation finished: " + safeUrl(url));
                    CookieManager.getInstance().flush();
                    super.onPageFinished(view,url);
                    if (!monitorStarted) {
                        // Snapshot IDs already present before the user starts a new animation.
                        String baselineScript = "(function(){var u=[];document.querySelectorAll('video,video source,a[href]').forEach(e=>{var x=e.currentSrc||e.src||e.href||'';if(x)u.push(x)});"
                                + "performance.getEntriesByType('resource').forEach(e=>{if(/webalice|yaart-images|video/i.test(e.name))u.push(e.name)});"
                                + "var ids=new Set();u.forEach(x=>{var p=String(x).split('webalice/')[1]||'';var m=p.match(/^([0-9a-f]{32})/i)||String(x).match(/[?&]video=([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase())});"
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
        try {
            Intent service = new Intent(this, BrowserBridgeService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            BrowserBridgeService.setAliceTaskActive(true);
        } catch (Exception e) {
            Log.w(TAG, "Background helper service unavailable", e);
        }
        if (webView.getParent() instanceof ViewGroup) ((ViewGroup)webView.getParent()).removeView(webView);
        root.addView(webView,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        webView.onResume();
        webView.resumeTimers();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != ALICE_FILE_CHOOSER) return;
        ValueCallback<Uri[]> callback = pendingFileCallback;
        pendingFileCallback = null;
        if (callback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) results = new Uri[]{uri};
            else if (data.getClipData() != null) {
                android.content.ClipData clip = data.getClipData();
                results = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) results[i] = clip.getItemAt(i).getUri();
            }
        }
        callback.onReceiveValue(results);
        Log.i(TAG, "Alice file chooser result: " + (results == null ? "cancelled" : results.length + " URI(s)"));
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

    private static String safeUrl(String raw) {
        if (raw == null) return "";
        try {
            Uri u = Uri.parse(raw);
            return u.getScheme() + "://" + u.getHost() + (u.getPath() == null ? "" : u.getPath());
        } catch (Exception ignored) { return "[invalid_url]"; }
    }

    private int dp(int v) { return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

    @Override public void onBackPressed() {
        // Keep Alice in its own task, like Perchance: returning to ASTRA must not destroy the page.
        moveTaskToBack(true);
    }

    @Override protected void onDestroy() {
        if (pendingFileCallback != null) {
            try { pendingFileCallback.onReceiveValue(null); } catch (Exception ignored) {}
            pendingFileCallback = null;
        }
        if (webView != null) {
            if (webView.getParent() instanceof ViewGroup) ((ViewGroup)webView.getParent()).removeView(webView);
            // Deliberately retain the WebView and monitor, matching RadioActivity's persistent player pattern.
            webView = null;
        }
        super.onDestroy();
    }
}