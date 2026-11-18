package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Dedicated ZSky studio WebView. Kept separate from Google Flow so either
 * generator can stay alive in its own task.
 */
public final class ZSkyWebViewActivity extends Activity {
    public static final String ZSKY_URL = "https://zsky.ai/create";
    private static volatile ZSkyWebViewActivity instance;

    private WebView webView;
    private TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean backgroundRequested;
    private volatile boolean pageLoaded;
    private volatile String currentUrl = "";
    private String lastPageDiagnostic = "{}";
    private String lastLifecycleEvent = "created";
    private PowerManager.WakeLock zskyWakeLock;
    private String pendingPrompt;

    public static ZSkyWebViewActivity getInstance() { return instance; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            zskyWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ASTRA:ZSkyGeneration");
            zskyWakeLock.setReferenceCounted(false);
            zskyWakeLock.acquire();
            Log.i("ASTRA-ZSKY", "Partial WakeLock acquired");
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF080B18);
        status = new TextView(this);
        status.setTextColor(0xFFE5C7FF);
        status.setTextSize(12);
        status.setPadding(18, 12, 18, 12);
        status.setText("ASTRA ZSky: открываю студию…");
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageLoaded = false;
                currentUrl = url == null ? "" : url;
                lastLifecycleEvent = "pageStarted";
                status.setText("ASTRA ZSky: загружаю " + url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                currentUrl = url == null ? "" : url;
                lastLifecycleEvent = "pageFinished";
                status.setText("ASTRA ZSky: студия загружена");
                main.postDelayed(ZSkyWebViewActivity.this::inspectPage, 900L);
                if (pendingPrompt != null) main.postDelayed(ZSkyWebViewActivity.this::runPendingPrompt, 1200L);
                if (pendingPrompt != null) main.postDelayed(ZSkyWebViewActivity.this::runPendingPrompt, 1200L);
                if (backgroundRequested) {
                    main.postDelayed(() -> {
                        if (!isFinishing() && !isDestroyed()) moveTaskToBack(true);
                    }, 250L);
                }
                Log.i("ASTRA-ZSKY", "Page finished: " + url);
            }
        });

        backgroundRequested = getIntent().getBooleanExtra("background", false);
        String initialPrompt = getIntent().getStringExtra("prompt");
        if (initialPrompt != null && !initialPrompt.trim().isEmpty()) pendingPrompt = initialPrompt.trim();
        String url = getIntent().getStringExtra("url");
        webView.loadUrl(url == null || url.trim().isEmpty() ? ZSKY_URL : url);
        if (backgroundRequested) {
            main.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) moveTaskToBack(true);
            }, 120L);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        backgroundRequested = intent.getBooleanExtra("background", backgroundRequested);
        String prompt = intent.getStringExtra("prompt");
        if (prompt != null && !prompt.trim().isEmpty()) pendingPrompt = prompt.trim();
        String url = intent.getStringExtra("url");
        if (url != null && !url.trim().isEmpty() && webView != null
                && !url.equals(webView.getUrl())) {
            webView.loadUrl(url);
        } else if (backgroundRequested && pageLoaded) {
            main.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) moveTaskToBack(true);
            }, 150L);
        }
    }

    private void runPendingPrompt() {
        if (webView == null || pendingPrompt == null || !pageLoaded || isFinishing() || isDestroyed()) return;
        String p = pendingPrompt;
        String script = "(function(p){try{const e=document.querySelector('input[placeholder=\\\"Describe the scene...\\\"]');if(!e)return 'FIELD_NOT_FOUND';if(e.disabled)return 'FIELD_DISABLED';e.focus();Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,p);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));if(e.value!==p)return 'FIELD_VERIFY_FAILED';const b=Array.from(document.querySelectorAll('button,[role=button]')).find(x=>(x.getAttribute('aria-label')||'').toLowerCase()==='generate'||(x.innerText||'').trim().toLowerCase()==='generate');if(!b)return 'BUTTON_NOT_FOUND';if(b.disabled||b.getAttribute('aria-disabled')==='true')return 'BUTTON_DISABLED';b.click();return 'GENERATE_CLICKED';}catch(x){return 'ERROR:'+x.message;}})(" + org.json.JSONObject.quote(p) + ")";
        webView.evaluateJavascript(script, value -> {
            String result = value == null ? "" : value;
            Log.i("ASTRA-ZSKY-AUTO", "automation result=" + result);
            if (result.contains("GENERATE_CLICKED")) pendingPrompt = null;
            else if (result.contains("FIELD_NOT_FOUND") || result.contains("BUTTON_NOT_FOUND") || result.contains("BUTTON_DISABLED"))
                main.postDelayed(this::runPendingPrompt, 1500L);
        });
    }
    private void inspectPage() {
        if (webView == null || isFinishing() || isDestroyed()) return;
        String js = "(function(){try{const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'};"
            + "const fields=[...document.querySelectorAll('textarea,input,[contenteditable=true],[role=textbox]')].filter(visible).slice(0,40).map(e=>({tag:e.tagName,type:e.type||'',placeholder:e.getAttribute('placeholder')||'',aria:e.getAttribute('aria-label')||'',role:e.getAttribute('role')||'',disabled:!!e.disabled}));"
            + "const buttons=[...document.querySelectorAll('button,[role=button],[role=tab]')].filter(visible).slice(0,80).map(e=>({text:(e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim().slice(0,100),aria:e.getAttribute('aria-label')||'',disabled:!!e.disabled}));"
            + "return JSON.stringify({url:location.href,title:document.title,ready:document.readyState,fields,buttons,videoCount:document.querySelectorAll('video').length,images:document.querySelectorAll('img').length,tail:(document.body?.innerText||'').slice(-1200)});"
            + "}catch(e){return JSON.stringify({error:String(e),url:location.href})}})()";
        webView.evaluateJavascript(js, value -> {
            lastPageDiagnostic = value == null ? "{}" : value;
            Log.i("ASTRA-ZSKY-DIAG", "PAGE " + lastPageDiagnostic);
        });
    }

    public void evaluate(String script, android.webkit.ValueCallback<String> callback) {
        if (webView == null || isFinishing() || isDestroyed()) {
            if (callback != null) callback.onReceiveValue(null);
            return;
        }
        runOnUiThread(() -> webView.evaluateJavascript(
                script == null || script.trim().isEmpty() ? "document.title" : script,
                callback));
    }

    // Called by the local HTTP worker: read cached state only, never touch WebView here.
    public String statusJson() {
        String url = currentUrl;
        return "{\"ok\":true,\"ready\":" + pageLoaded
                + ",\"state\":\"" + (pageLoaded ? "loaded" : "loading")
                + "\",\"background\":" + backgroundRequested
                + ",\"lifecycle\":\"" + lastLifecycleEvent
                + "\",\"url\":\"" + esc(url) + "\"}";
    }

    public String diagnosticJson() { return lastPageDiagnostic; }

    public void openUrl(String url) {
        if (webView != null && url != null && url.startsWith("https://")) {
            runOnUiThread(() -> webView.loadUrl(url));
        }
    }

    public void minimizeStudio() {
        runOnUiThread(() -> moveTaskToBack(true));
    }

    public void closeStudio() {
        runOnUiThread(this::finish);
    }

    public static String start(Context context, String url, boolean background) {
        return start(context, url, null, background);
    }

    public static String start(Context context, String url, String prompt, boolean background) {
        try {
            Intent intent = new Intent(context, ZSkyWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra("url", url == null || url.isEmpty() ? ZSKY_URL : url);
            intent.putExtra("background", background);
            if (prompt != null && !prompt.trim().isEmpty()) intent.putExtra("prompt", prompt.trim());
            context.startActivity(intent);
            return "{\"ok\":true,\"action\":\"zsky-start-requested\",\"background\":"
                    + background + "}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    public static String status() {
        ZSkyWebViewActivity a = instance;
        return a == null ? "{\"ok\":true,\"ready\":false,\"state\":\"stopped\"}" : a.statusJson();
    }

    public static String evaluateScript(String script) {
        ZSkyWebViewActivity a = instance;
        if (a == null) return "{\"ok\":false,\"error\":\"zsky_not_started\"}";
        final Object lock = new Object();
        final String[] result = new String[1];
        final boolean[] done = new boolean[1];
        a.evaluate(script, value -> {
            synchronized (lock) { result[0] = value; done[0] = true; lock.notifyAll(); }
        });
        synchronized (lock) {
            try { if (!done[0]) lock.wait(15000L); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{\"ok\":false,\"error\":\"interrupted\"}";
            }
        }
        return done[0] ? "{\"ok\":true,\"value\":" + (result[0] == null ? "null" : result[0]) + "}"
                : "{\"ok\":false,\"error\":\"zsky_eval_timeout\"}";
    }

    public static String inspect() {
        ZSkyWebViewActivity a = instance;
        return a == null ? "{\"ok\":false,\"error\":\"zsky_not_started\"}"
                : a.diagnosticJson();
    }

    public static String open(Context context, String url) {
        ZSkyWebViewActivity a = instance;
        if (a == null) return start(context, url, false);
        a.openUrl(url == null || url.isEmpty() ? ZSKY_URL : url);
        return "{\"ok\":true,\"action\":\"zsky-open-requested\"}";
    }

    public static String minimize() {
        ZSkyWebViewActivity a = instance;
        if (a == null) return "{\"ok\":false,\"error\":\"zsky_not_started\"}";
        a.minimizeStudio();
        return "{\"ok\":true,\"action\":\"zsky-minimize-requested\"}";
    }

    public static String close() {
        ZSkyWebViewActivity a = instance;
        if (a == null) return "{\"ok\":true,\"action\":\"zsky-already-closed\"}";
        a.closeStudio();
        return "{\"ok\":true,\"action\":\"zsky-close-requested\"}";
    }

    public static String bringToFront(Context context) {
        if (instance == null) return "{\"ok\":false,\"error\":\"zsky_not_started\"}";
        Intent intent = new Intent(context, ZSkyWebViewActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.startActivity(intent);
        return "{\"ok\":true,\"action\":\"zsky-brought-to-front\"}";
    }

    @Override protected void onDestroy() {
        lastLifecycleEvent = "destroyed";
        if (zskyWakeLock != null && zskyWakeLock.isHeld()) zskyWakeLock.release();
        zskyWakeLock = null;
        if (instance == this) instance = null;
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
