package com.astra.loremotionbridge;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Environment;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
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
    private WebView zskyPopup;
    private long zskyDownloadId = -1L;
    private BroadcastReceiver zskyDownloadReceiver;
    private ZSkyVideoCaptureBridge videoCaptureBridge;
    private TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean backgroundRequested;
    private volatile boolean pageLoaded;
    private volatile String currentUrl = "";
    private String lastPageDiagnostic = "{}";
    private String lastLifecycleEvent = "created";
    private PowerManager.WakeLock zskyWakeLock;
    private String pendingPrompt;
    private boolean promptInjected;
    private int automationAttempts;
    private int observerPolls;
    private long generationStartedAt;
    private int baselineVideoCount = -1;
    private String generationState = "idle";
    private String generationEvidence = "";
    private int diagPollCount;
    private long lastDiagAt;

    // Native polling survives Activity backgrounding; the page heartbeat tells us
    // whether Chromium is still executing timers while the task is hidden.
    private final Runnable diagPoll = new Runnable() {
        @Override public void run() {
            if (webView == null || isFinishing() || isDestroyed()) return;
            webView.evaluateJavascript(
                    "(function(){const h=window.__astraZSkyDiag||{};return JSON.stringify({"
                    + "visibility:document.visibilityState,hidden:document.hidden,"
                    + "ready:document.readyState,tick:h.tick||0,heartbeatAt:h.at||0,"
                    + "url:location.href});})()",
                    value -> {
                        diagPollCount++;
                        lastDiagAt = android.os.SystemClock.elapsedRealtime();
                        Log.i("ASTRA-ZSKY-DIAG", "background poll=" + diagPollCount
                                + " lifecycle=" + lastLifecycleEvent + " page=" + value);
                        if (webView != null && !isFinishing() && !isDestroyed())
                            main.postDelayed(this, 5000L);
                    });
        }
    };

    private final Runnable generationObserver = new Runnable() {
        @Override public void run() {
            if (webView == null || generationStartedAt == 0L || isFinishing() || isDestroyed()) return;
            String js = "(function(){const t=(document.body&&document.body.innerText)||'';return JSON.stringify({tail:t.slice(-1400),videos:document.querySelectorAll('video').length,busy:document.querySelectorAll('[aria-busy=true],[role=progressbar]').length,visibility:document.visibilityState});})()";
            webView.evaluateJavascript(js, value -> {
                observerPolls++;
                if (webView != null) webView.evaluateJavascript(videoCaptureScanScript(), null);
                generationEvidence = value == null ? "" : value;
                try {
                    String payload = generationEvidence;
                    if (payload.startsWith("\"")) {
                        Object decoded = new org.json.JSONTokener(payload).nextValue();
                        if (decoded instanceof String) payload = (String) decoded;
                    }
                    org.json.JSONObject e = new org.json.JSONObject(payload);
                    String tail = e.optString("tail", "").toLowerCase(java.util.Locale.ROOT);
                    if (tail.contains("generation failed") || tail.contains("ошибка генерации")) generationState = "failed";
                    else if (e.optInt("busy", 0) > 0) generationState = "running";
                    else if (baselineVideoCount >= 0 && e.optInt("videos", 0) > baselineVideoCount) generationState = "completed_unconfirmed";
                } catch (Exception ex) { Log.w("ASTRA-ZSKY-OBSERVER", "Evidence parse failed", ex); }
                Log.i("ASTRA-ZSKY-OBSERVER", "poll=" + observerPolls + " state=" + generationState + " evidence=" + generationEvidence);
                if (webView != null && !isFinishing() && !isDestroyed()) main.postDelayed(this, 5000L);
            });
        }
    };
    private void downloadFinalZSkyMp4(String url, String source) { downloadFinalZSkyMp4(url, source, "video/mp4"); }

    private void downloadFinalZSkyMp4(String url, String source, String mimeType) {
        if (url == null || !url.startsWith("https://")) return;
        String lowerUrl = url.toLowerCase(java.util.Locale.ROOT);
        String lowerMime = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
        if (!(lowerUrl.contains(".mp4") || lowerUrl.contains("video") || lowerMime.startsWith("video/"))) return;
        if (zskyDownloadId != -1L) return;
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("DownloadManager unavailable");
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("ASTRA · ZSky video");
            req.setDescription("Сохранение готового видео ZSky");
            req.setMimeType(mimeType != null && mimeType.startsWith("video/") ? mimeType : "video/mp4");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES,
                    "ASTRA/ZSky/zsky_" + System.currentTimeMillis() + ".mp4");
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) req.addRequestHeader("Cookie", cookie);
            req.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
            String referer = webView.getUrl();
            if (referer != null) req.addRequestHeader("Referer", referer);
            zskyDownloadId = dm.enqueue(req);
            Log.i("ASTRA-ZSKY-DOWNLOAD", "FINAL mp4 queued id=" + zskyDownloadId + " source=" + source);
            status.setText("ASTRA ZSky: скачиваю финальное видео…");
            if (zskyDownloadReceiver == null) {
                zskyDownloadReceiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != zskyDownloadId) return;
                        Uri saved = dm.getUriForDownloadedFile(zskyDownloadId);
                        if (saved != null) {
                            ChatActivity.onZSkyVideoReady(getApplicationContext(), saved);
                            status.setText("ASTRA ZSky: готовое видео сохранено");
                            Log.i("ASTRA-ZSKY-DOWNLOAD", "FINAL mp4 saved=" + saved);
                        } else {
                            status.setText("ASTRA ZSky: ошибка загрузки финального видео");
                            Log.e("ASTRA-ZSKY-DOWNLOAD", "FINAL mp4 download failed");
                        }
                        zskyDownloadId = -1L;
                    }
                };
                IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(zskyDownloadReceiver, f, Context.RECEIVER_NOT_EXPORTED);
                else registerReceiver(zskyDownloadReceiver, f);
            }
        } catch (Exception e) {
            Log.e("ASTRA-ZSKY-DOWNLOAD", "final MP4 download failed", e);
        }
    }

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
        videoCaptureBridge = new ZSkyVideoCaptureBridge(this);
        webView.addJavascriptInterface(videoCaptureBridge, "ASTRAZSkyVideo");
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
        settings.setSupportMultipleWindows(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog,
                    boolean userGesture, android.os.Message resultMsg) {
                // ZSky's final MP4 may be opened with target=_blank. Do not discard that window.
                if (zskyPopup != null) {
                    try { zskyPopup.destroy(); } catch (Exception ignored) {}
                }
                zskyPopup = new WebView(ZSkyWebViewActivity.this);
                WebSettings ps = zskyPopup.getSettings();
                ps.setJavaScriptEnabled(true);
                ps.setDomStorageEnabled(true);
                ps.setUserAgentString(webView.getSettings().getUserAgentString());
                zskyPopup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        String url = request == null || request.getUrl() == null ? "" : request.getUrl().toString();
                        Log.i("ASTRA-ZSKY-POPUP", "new-window navigation=" + url);
                        if (url.matches("(?i)^https://.*\\.mp4([?#].*)?$")) {
                            Log.i("ASTRA-ZSKY-POPUP", "FINAL_MP4_TARGET=" + url);
                            webView.evaluateJavascript("window.__astraZSkyFinalResult=true", null);
                            downloadFinalZSkyMp4(url, "target-blank-navigation");
                            return true;
                        }
                        return false;
                    }
                });
                zskyPopup.setDownloadListener((url, ua, disposition, mime, length) -> {
                    Log.i("ASTRA-ZSKY-POPUP", "FINAL download callback url=" + url + " mime=" + mime);
                    downloadFinalZSkyMp4(url, "popup-download-listener", mime);
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(zskyPopup);
                resultMsg.sendToTarget();
                return true;
            }
        });
        webView.setDownloadListener((url, ua, disposition, mime, length) -> {
            Log.i("ASTRA-ZSKY-DOWNLOAD", "FINAL main download callback url=" + url + " mime=" + mime);
            downloadFinalZSkyMp4(url, "main-download-listener", mime);
        });
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
                main.postDelayed(ZSkyWebViewActivity.this::installVideoCapture, 1400L);
                if (pendingPrompt != null) main.postDelayed(ZSkyWebViewActivity.this::runPendingPrompt, 1200L);
                view.evaluateJavascript(
                        "(function(){if(window.__astraZSkyDiag)clearInterval(window.__astraZSkyDiag.timer);"
                        + "const h=window.__astraZSkyDiag={tick:0,at:Date.now(),timer:null};"
                        + "h.timer=setInterval(function(){h.tick++;h.at=Date.now();},2000);"
                        + "return 'heartbeat-installed';})()",
                        value -> Log.i("ASTRA-ZSKY-DIAG", "heartbeat setup=" + value));
                main.removeCallbacks(diagPoll);
                main.postDelayed(diagPoll, 2000L);
                // Do not hide the page here: SPA hydration and prompt submission
                // happen after onPageFinished. Minimize only after Generate is confirmed.
                Log.i("ASTRA-ZSKY", "Page finished: " + url);
            }
        });

        backgroundRequested = getIntent().getBooleanExtra("background", false);
        String initialPrompt = getIntent().getStringExtra("prompt");
        if (initialPrompt != null && !initialPrompt.trim().isEmpty()) pendingPrompt = initialPrompt.trim();
        String url = getIntent().getStringExtra("url");
        webView.loadUrl(url == null || url.trim().isEmpty() ? ZSKY_URL : url);
        // Do not minimize before the SPA has hydrated; Android may throttle hidden WebViews.

    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        backgroundRequested = intent.getBooleanExtra("background", backgroundRequested);
        String prompt = intent.getStringExtra("prompt");
        if (prompt != null && !prompt.trim().isEmpty()) {
            pendingPrompt = prompt.trim();
            promptInjected = false;
            automationAttempts = 0;
            generationState = "queued";
            if (pageLoaded) main.postDelayed(this::runPendingPrompt, 400L);
        }
        String url = intent.getStringExtra("url");
        if (url != null && !url.trim().isEmpty() && webView != null
                && !url.equals(webView.getUrl())) {
            webView.loadUrl(url);
        } // Minimize only after the Generate click is confirmed.
    }

    private void runPendingPrompt() {
        if (webView == null || pendingPrompt == null || !pageLoaded || isFinishing() || isDestroyed()) return;
        if (++automationAttempts > 45) {
            generationState = "automation_timeout";
            pendingPrompt = null;
            Log.w("ASTRA-ZSKY-AUTO", "Automation timed out");
            return;
        }
        String script;
        if (!promptInjected) {
            script = "(function(p){try{const e=document.querySelector('input[aria-label=\\\"Image or video prompt\\\"]')"
                    + "||document.querySelector('textarea[aria-label=\\\"Image or video prompt\\\"]')"
                    + "||document.querySelector('input[placeholder=\\\"Describe the scene...\\\"]')"
                    + "||document.querySelector('textarea[placeholder=\\\"Describe the scene...\\\"]');"
                    + "if(!e)return 'FIELD_NOT_FOUND';if(e.disabled)return 'FIELD_DISABLED';"
                    + "e.focus();const proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                    + "Object.getOwnPropertyDescriptor(proto,'value').set.call(e,p);"
                    + "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "return e.value===p?'PROMPT_SET':'PROMPT_VERIFY_FAILED';"
                    + "}catch(x){return 'ERROR:'+x.message;}})(" + org.json.JSONObject.quote(pendingPrompt) + ")";
        } else {
            script = "(function(){const b=Array.from(document.querySelectorAll('button,[role=button]'))"
                    + ".find(x=>x.matches('button[aria-label=\\\"Generate\\\"]')||(x.getAttribute('aria-label')||'').trim().toLowerCase()==='generate'"
                    + "||((x.innerText||x.textContent||'').trim().toLowerCase()==='generate'));"
                    + "return (!b?'BUTTON_NOT_FOUND':(b.disabled||b.getAttribute('aria-disabled')==='true'?'BUTTON_DISABLED':'BUTTON_READY'));})()";
        }
        webView.evaluateJavascript(script, value -> {
            String result = value == null ? "" : value;
            Log.i("ASTRA-ZSKY-AUTO", "attempt=" + automationAttempts + " result=" + result);
            if (result.contains("PROMPT_SET")) {
                promptInjected = true;
                generationState = "prompt_entered";
                main.postDelayed(this::runPendingPrompt, 700L);
            } else if (result.contains("BUTTON_READY")) {
                String click = "(function(){const b=Array.from(document.querySelectorAll('button,[role=button]'))"
                        + ".find(x=>(x.getAttribute('aria-label')||'').trim().toLowerCase()==='generate'"
                        + "||((x.innerText||x.textContent||'').trim().toLowerCase()==='generate'));"
                        + "if(!b||b.disabled||b.getAttribute('aria-disabled')==='true')return 'BUTTON_NOT_READY';"
                        + "const n=document.querySelectorAll('video').length;"
                        + "b.click();return 'GENERATE_CLICKED:'+n;})()";
                webView.evaluateJavascript(click, clicked -> {
                    String clickResult = clicked == null ? "" : clicked;
                    Log.i("ASTRA-ZSKY-AUTO", "generate result=" + clickResult);
                    if (clickResult.contains("GENERATE_CLICKED")) {
                        try { baselineVideoCount = Integer.parseInt(clickResult.replaceAll("[^0-9]", "")); }
                        catch (Exception ignored) { baselineVideoCount = -1; }
                        generationStartedAt = System.currentTimeMillis();
                        generationState = "submitted_unconfirmed";
                        pendingPrompt = null;
                        main.removeCallbacks(generationObserver);
                        main.postDelayed(generationObserver, 1500L);
                        if (backgroundRequested) main.postDelayed(() -> {
                            if (!isFinishing() && !isDestroyed()) moveTaskToBack(true);
                        }, 250L);
                    } else {
                        main.postDelayed(this::runPendingPrompt, 1000L);
                    }
                });
            } else {
                main.postDelayed(this::runPendingPrompt, 1000L);
            }
        });
    }


    private String videoCaptureScanScript() {
        return "(function(){try{window.__astraZSkyVideoScan&&window.__astraZSkyVideoScan();return 'scan';}catch(e){return String(e);}})()";
    }

    // Flow-style page-owned Blob capture, streamed in bounded chunks to native MediaStore.
    private void installVideoCapture() {
        if (webView == null || isFinishing() || isDestroyed()) return;
        String js = "(function(){"
            + "if(window.__astraZSkyCaptureInstalled){window.__astraZSkyVideoScan&&window.__astraZSkyVideoScan();return 'already-installed-rescanned';}"
            + "window.__astraZSkyCaptureInstalled=true;"
            + "window.__astraZSkyCaptureDone=window.__astraZSkyCaptureDone||new Set();"
            + "window.__astraZSkyCaptureBusy=window.__astraZSkyCaptureBusy||new Set();"
            + "const roots=new WeakSet(),videos=new WeakSet();"
            + "async function capture(v){if(!window.__astraZSkyFinalResult)return;let u='';try{u=v.currentSrc||v.src||'';if(!u){const q=v.querySelector('source[src]');u=q?q.src:'';}"
            + "if(!u||!u.startsWith('blob:'))return;"
            + "if(window.__astraZSkyCaptureDone.has(u)||window.__astraZSkyCaptureBusy.has(u))return;"
            + "window.__astraZSkyCaptureBusy.add(u);const r=await fetch(u);if(!r.ok)throw new Error('blob_http_'+r.status);"
            + "const b=await r.blob();if(!b.size)throw new Error('empty_blob');"
            + "if(!window.ASTRAZSkyVideo.beginVideo(b.type||'video/mp4','zsky_'+Date.now()+'.mp4'))throw new Error('native_begin_failed');"
            + "const size=48*1024;for(let i=0;i<b.size;i+=size){const a=new Uint8Array(await b.slice(i,Math.min(i+size,b.size)).arrayBuffer());"
            + "let bin='';for(let j=0;j<a.length;j++)bin+=String.fromCharCode(a[j]);"
            + "if(!window.ASTRAZSkyVideo.appendChunk(btoa(bin)))throw new Error('native_chunk_failed_'+i);}"
            + "if(!window.ASTRAZSkyVideo.finishVideo())throw new Error('native_finish_failed');"
            + "window.__astraZSkyCaptureDone.add(u);window.__astraZSkyCaptureBusy.delete(u);console.info('ASTRA_ZSKY_VIDEO_SAVED',b.size);"
            + "}catch(e){window.__astraZSkyCaptureBusy.delete(u);try{window.ASTRAZSkyVideo.failVideo(String(e));}catch(x){}console.error('ASTRA_ZSKY_VIDEO_CAPTURE:'+e);}}"
            + "function watch(v){if(!v||videos.has(v))return;videos.add(v);"
            + "['loadedmetadata','loadeddata','durationchange','canplay','playing','progress','loadstart'].forEach(e=>v.addEventListener(e,()=>capture(v),{passive:true}));capture(v);}"
            + "function scan(root){if(!root)return;try{root.querySelectorAll('video').forEach(watch);"
            + "if(!roots.has(root)){roots.add(root);new MutationObserver(()=>window.__astraZSkyVideoScan()).observe(root,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});}"
            + "root.querySelectorAll('*').forEach(n=>{if(n.shadowRoot)scan(n.shadowRoot);});"
            + "root.querySelectorAll('iframe').forEach(f=>{try{if(f.contentDocument)scan(f.contentDocument);}catch(e){}});"
            + "}catch(e){console.warn('ASTRA_ZSKY_SCAN:'+e);}}"
            + "window.__astraZSkyVideoScan=function(){scan(document);};window.__astraZSkyVideoScan();"
            + "setInterval(window.__astraZSkyVideoScan,2500);return 'zsky-blob-capture-installed';})()";
        webView.evaluateJavascript(js, value -> Log.i("ASTRA-ZSKY-CAPTURE", "bridge setup=" + value));
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
                + "\",\"generationState\":\"" + esc(generationState)
                + "\",\"observerPolls\":" + observerPolls
                + ",\"generationEvidence\":\"" + esc(generationEvidence)
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

    @Override protected void onPause() {
        lastLifecycleEvent = "onPause";
        Log.i("ASTRA-ZSKY", "Lifecycle onPause: url="
                + (webView != null ? webView.getUrl() : "null")
                + ", generationState=" + generationState);
        // Deliberately do not call WebView.onPause() or pauseTimers():
        // the hidden page must continue its generation workflow like Flow.
        super.onPause();
    }

    @Override protected void onStop() {
        lastLifecycleEvent = "onStop";
        Log.i("ASTRA-ZSKY", "Lifecycle onStop: url="
                + (webView != null ? webView.getUrl() : "null")
                + ", generationState=" + generationState);
        super.onStop();
    }

    @Override protected void onResume() {
        lastLifecycleEvent = "onResume";
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    @Override protected void onDestroy() {
        lastLifecycleEvent = "destroyed";
        main.removeCallbacks(generationObserver);
        main.removeCallbacks(diagPoll);
        if (videoCaptureBridge != null) videoCaptureBridge.abortVideo();
        if (zskyDownloadReceiver != null) { try { unregisterReceiver(zskyDownloadReceiver); } catch (Exception ignored) {} zskyDownloadReceiver = null; }
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
