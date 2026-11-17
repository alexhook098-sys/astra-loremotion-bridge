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
import android.webkit.DownloadListener;
import android.app.DownloadManager;
import android.net.Uri;
import android.os.Environment;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class FlowWebViewActivity extends Activity {
    public static final String FLOW_PROJECT_URL = "https://flow.google.com/u/1/project/c311a6f3-e3de-4490-b97c-54ddd8610af1";
    public static final String EXTRA_AUTOMATE = "astra_flow_automate";
    public static final String EXTRA_PROMPT = "astra_flow_prompt";
    private static volatile FlowWebViewActivity instance;
    private WebView webView;
    private PowerManager.WakeLock flowWakeLock;
    private TextView status;
    private boolean hideAfterPageLoad;
    private boolean reloadMoveToBackAfterLoad;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String lastLifecycleEvent = "created";
    private String lastPageDiag = "";
    private long lastDiagAt = 0L;
    private int diagPollCount = 0;
    private boolean diagRequestPending = false;
    private String pendingPrompt;
    private boolean promptInjected;
    private boolean pageLoaded;
    private boolean backgroundRequested;
    private int automationAttempts;
    private String generationState = "idle";
    private String generationEvidence = "";
    private long generationStartedAt = 0L;
    private int generationObserverPolls = 0;
    private final Runnable generationObserver = new Runnable() {
        @Override public void run() {
            if (webView == null || isFinishing() || isDestroyed()
                    || generationStartedAt == 0L) return;
            String script = "(function(){try{const t=(document.body&&document.body.innerText)||'';"
                + "const v=[...document.querySelectorAll('video')].filter(x=>x.getBoundingClientRect().width>0).length;"
                + "const p=document.querySelector('[role=textbox][aria-label=\\\"Что вы хотите создать?\\\"]')||document.querySelector('.ProseMirror[contenteditable=true]');"
                + "const q=p?(p.innerText||p.value||'').trim():'';"
                + "const busy=document.querySelectorAll('[aria-busy=true],[role=progressbar]').length;"
                + "return JSON.stringify({tail:t.slice(-1400),videos:v,prompt:q,busy:busy});"
                + "}catch(e){return JSON.stringify({error:String(e)});}})()";
            webView.evaluateJavascript(script, value -> {
                generationObserverPolls++;
                generationEvidence = value == null ? "" : value;
                String v = generationEvidence.toLowerCase(java.util.Locale.ROOT);
                if (v.contains("ошибка генерац") || v.contains("generation failed")
                        || v.contains("не удалось создать")) {
                    generationState = "failed";
                } else if (v.contains("\\\"busy\\\":1")
                        || v.contains("\\\"videos\\\":1")
                        || v.contains("генерация") || v.contains("создание")
                        || v.contains("processing") || v.contains("generating")) {
                    generationState = "running";
                } else if (!"failed".equals(generationState)
                        && !"running".equals(generationState)) {
                    generationState = "submitted_unconfirmed";
                }
                Log.i("ASTRA-FLOW-OBSERVER", "poll=" + generationObserverPolls
                        + " state=" + generationState + " evidence=" + generationEvidence);
                if (webView != null && !isFinishing() && !isDestroyed())
                    main.postDelayed(this, 5000L);
            });
        }
    };

    private final Runnable diagPoll = new Runnable() {
        @Override public void run() {
            if (webView == null || isFinishing() || isDestroyed()) return;
            if (diagRequestPending) return;

            diagRequestPending = true;
            final long startedAt = android.os.SystemClock.elapsedRealtime();
            final boolean[] completed = {false};

            Runnable timeout = new Runnable() {
                @Override public void run() {
                    if (completed[0]) return;
                    completed[0] = true;
                    diagRequestPending = false;
                    Log.w("ASTRA-FLOW-DIAG",
                        "JavaScript diagnostic callback timeout");
                    if (webView != null && !isFinishing() && !isDestroyed()) {
                        main.postDelayed(diagPoll, 5000L);
                    }
                }
            };

            main.postDelayed(timeout, 3000L);

            try {
                webView.evaluateJavascript(
                    "(function(){var d=document;var h=window.__astraFlowDiag||{};" +
                    "var t=(d.body&&d.body.innerText)||'';" +
                    "return JSON.stringify({visibility:d.visibilityState,hidden:d.hidden," +
                    "ready:d.readyState,title:d.title,heartbeat:h.tick||0," +
                    "heartbeatAt:h.at||0,tail:t.slice(-900)});})()",
                    value -> {
                        if (completed[0]) return;
                        completed[0] = true;
                        main.removeCallbacks(timeout);
                        diagRequestPending = false;
                        diagPollCount++;
                        lastPageDiag = value == null ? "" : value;
                        lastDiagAt = android.os.SystemClock.elapsedRealtime();

                        Log.i("ASTRA-FLOW-DIAG",
                            "poll=" + diagPollCount
                            + " lifecycle=" + lastLifecycleEvent
                            + " elapsedMs=" + (lastDiagAt - startedAt)
                            + " page=" + lastPageDiag);

                        if (webView != null && !isFinishing() && !isDestroyed()) {
                            main.postDelayed(diagPoll, 5000L);
                        }
                    }
                );
            } catch (Exception e) {
                main.removeCallbacks(timeout);
                completed[0] = true;
                diagRequestPending = false;
                Log.e("ASTRA-FLOW-DIAG", "Diagnostic request failed", e);
                if (webView != null && !isFinishing() && !isDestroyed()) {
                    main.postDelayed(diagPoll, 5000L);
                }
            }
        }
    };

    public static FlowWebViewActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        readAutomationIntent(getIntent());

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

        webView.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (downloadUrl == null || downloadUrl.isEmpty()) return;
            if (downloadUrl.startsWith("blob:")) {
                Log.w("ASTRA-FLOW-DOWNLOAD", "Blob URL download requires page-side blob bridge; not handing it to DownloadManager");
                status.setText("ASTRA Flow: получена blob-ссылка, нужен отдельный мост загрузки");
                return;
            }
            try {
                DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
                request.setTitle("ASTRA · Google Flow video");
                request.setDescription("Сохранение результата Google Flow");
                request.setMimeType(mimeType == null ? "video/mp4" : mimeType);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES,
                        "ASTRA/Flow/flow_" + System.currentTimeMillis() + ".mp4");
                String downloadCookies = CookieManager.getInstance().getCookie(downloadUrl);
                if (downloadCookies != null && !downloadCookies.isEmpty()) request.addRequestHeader("Cookie", downloadCookies);
                if (userAgent != null && !userAgent.isEmpty()) request.addRequestHeader("User-Agent", userAgent);
                manager.enqueue(request);
                status.setText("ASTRA Flow: загрузка видео в Movies/ASTRA/Flow");
                Log.i("ASTRA-FLOW-DOWNLOAD", "Download queued; mime=" + mimeType + ", bytes=" + contentLength);
            } catch (Exception e) {
                Log.e("ASTRA-FLOW-DOWNLOAD", "Could not queue video download", e);
                status.setText("ASTRA Flow: ошибка загрузки · " + e.getClass().getSimpleName());
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                status.setText("ASTRA Flow: " + url);
                pageLoaded = true;
                main.postDelayed(FlowWebViewActivity.this::diagnoseFlowPage, 700L);
                if (pendingPrompt != null) main.postDelayed(FlowWebViewActivity.this::attemptFlowAutomation, 1200L);
                Log.i("ASTRA-FLOW", "Page finished: " + url);
                lastLifecycleEvent = "pageFinished";
                view.evaluateJavascript(
                    "(function(){if(window.__astraFlowDiag)clearInterval(window.__astraFlowDiag.timer);" +
                    "var h=window.__astraFlowDiag={tick:0,at:Date.now(),timer:null};" +
                    "h.timer=setInterval(function(){h.tick++;h.at=Date.now();},2000);" +
                    "return 'heartbeat-installed';})()",
                    value -> Log.i("ASTRA-FLOW-DIAG", "heartbeat setup=" + value)
                );
                main.removeCallbacks(diagPoll);
                main.postDelayed(diagPoll, 2000L);
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
            url = FLOW_PROJECT_URL;
        }
        backgroundRequested = getIntent().getBooleanExtra("background", false);
        hideAfterPageLoad = backgroundRequested && pendingPrompt == null;
        pageLoaded = false;
        webView.loadUrl(url);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readAutomationIntent(intent);
        backgroundRequested = intent.getBooleanExtra("background", backgroundRequested);
        String url = intent.getStringExtra("url");
        if (url != null && !url.isEmpty() && webView != null && !url.equals(webView.getUrl())) {
            pageLoaded = false;
            hideAfterPageLoad = backgroundRequested && pendingPrompt == null;
            webView.loadUrl(url);
        } else if (pendingPrompt != null && webView != null && pageLoaded) {
            main.postDelayed(this::attemptFlowAutomation, 500L);
        } else if (pendingPrompt == null && backgroundRequested && pageLoaded) {
            main.postDelayed(() -> { if (!isFinishing() && !isDestroyed()) moveTaskToBack(true); }, 250L);
        }
    }


    private void diagnoseFlowPage() {
        if (webView == null || isFinishing() || isDestroyed()) return;
        String script = "(function(){try{const roots=[],frames=[],seen=new Set();"
            + "function collect(root){if(!root||seen.has(root))return;seen.add(root);roots.push(root);"
            + "try{Array.from(root.querySelectorAll('iframe')).forEach((f,i)=>{let same=false;try{same=!!f.contentDocument;}catch(e){}"
            + "frames.push({index:i,src:f.src||'',title:f.title||'',sameOrigin:same});if(same)collect(f.contentDocument);});"
            + "Array.from(root.querySelectorAll('*')).forEach(n=>{if(n.shadowRoot)collect(n.shadowRoot);});}catch(e){}}"
            + "collect(document);const visible=e=>{try{const r=e.getBoundingClientRect(),s=e.ownerDocument.defaultView.getComputedStyle(e);"
            + "return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';}catch(x){return false;}};"
            + "const fields=[],buttons=[];roots.forEach((r,i)=>{try{Array.from(r.querySelectorAll('textarea,input,[contenteditable],[role=textbox]')).filter(visible).slice(0,35).forEach(e=>fields.push({root:i,tag:e.tagName,placeholder:e.getAttribute('placeholder')||'',aria:e.getAttribute('aria-label')||'',role:e.getAttribute('role')||'',disabled:!!e.disabled}));"
            + "Array.from(r.querySelectorAll('button,[role=button],[role=tab]')).filter(visible).slice(0,60).forEach(e=>buttons.push({root:i,text:((e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim()).slice(0,90),aria:e.getAttribute('aria-label')||'',disabled:!!e.disabled}));}catch(e){}});"
            + "return JSON.stringify({url:location.href,title:document.title,ready:document.readyState,frameCount:frames.length,rootCount:roots.length,fields:fields,buttons:buttons,frames:frames});"
            + "}catch(e){return JSON.stringify({error:String(e),url:location.href});}})();";
        webView.evaluateJavascript(script, value -> {
            lastPageDiag = value == null ? "" : value;
            Log.i("ASTRA-FLOW-DIAG", "FLOW_PAGE_DIAGNOSTIC " + lastPageDiag);
            status.setText("ASTRA Flow: диагностика интерфейса записана");
        });
    }

    private void readAutomationIntent(android.content.Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_AUTOMATE, false)) return;
        String prompt = intent.getStringExtra(EXTRA_PROMPT);
        if (prompt == null || prompt.trim().isEmpty()) return;
        pendingPrompt = prompt.trim();
        backgroundRequested = intent.getBooleanExtra("background", true);
        hideAfterPageLoad = false;
        promptInjected = false;
        automationAttempts = 0;
        Log.i("ASTRA-FLOW-AUTO", "Background prompt queued; length=" + pendingPrompt.length());
    }

    private void attemptFlowAutomation() {
        if (webView == null || pendingPrompt == null || isFinishing() || isDestroyed()) return;
        if (++automationAttempts > 45) {
            Log.w("ASTRA-FLOW-AUTO", "Timeout waiting for Flow controls");
            generationState = "automation_timeout";
            pendingPrompt = null;
            return;
        }
        webView.evaluateJavascript(buildFlowAutomationScript(pendingPrompt, promptInjected), result -> {
            String value = result == null ? "" : result;
            Log.i("ASTRA-FLOW-AUTO", "attempt=" + automationAttempts + " result=" + value);
            if (value.contains("FIELD_SET")) {
                promptInjected = true;
                generationState = "prompt_entered";
                status.setText("ASTRA Flow: prompt entered");
                main.postDelayed(this::attemptFlowAutomation, 700L);
            } else if (value.contains("GENERATE_CLICKED")) {
                generationStartedAt = System.currentTimeMillis();
                generationState = "submitted_unconfirmed";
                generationEvidence = "Generate button click dispatched; awaiting page evidence";
                status.setText("ASTRA Flow: отправлено · проверяю выполнение");
                Log.i("ASTRA-FLOW-AUTO", "Generate click dispatched; native observer started");
                pendingPrompt = null;
                main.removeCallbacks(generationObserver);
                main.postDelayed(generationObserver, 1500L);
                if (backgroundRequested) main.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        moveTaskToBack(true);
                        Log.i("ASTRA-FLOW", "Background mode: minimized after generate click; native observer remains active");
                    }
                }, 500L);
            } else {
                main.postDelayed(this::attemptFlowAutomation, 1000L);
            }
        });
    }

    private String buildFlowAutomationScript(String prompt, boolean clickGenerate) {
        String quoted = org.json.JSONObject.quote(prompt);
        if (clickGenerate) {
            return "(function(){try{"
                + "const b=Array.from(document.querySelectorAll('button,[role=button]')).find(x=>x.getAttribute('aria-label')==='Начать генерацию');"
                + "if(!b)return 'GENERATE_BUTTON_NOT_FOUND';"
                + "if(b.disabled||b.getAttribute('aria-disabled')==='true')return 'GENERATE_BUTTON_DISABLED';"
                + "b.click();return 'GENERATE_CLICKED';"
                + "}catch(e){return 'ERROR:'+e.message;}})()";
        }
        return "(function(p){try{"
            + "const e=Array.from(document.querySelectorAll('[role=textbox]')).find(x=>x.getAttribute('aria-label')==='Что вы хотите создать?')||document.querySelector('.ProseMirror[contenteditable=true]');"
            + "if(!e)return 'PROMPT_FIELD_NOT_FOUND';"
            + "e.focus();const setter=Object.getOwnPropertyDescriptor(HTMLElement.prototype,'innerText')?.set;"
            + "if(setter)setter.call(e,p);else e.innerText=p;"
            + "e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:p}));"
            + "e.dispatchEvent(new Event('change',{bubbles:true}));"
            + "return 'FIELD_SET';"
            + "}catch(e){return 'ERROR:'+e.message;}})(" + quoted + ")";
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
        long diagAge = lastDiagAt == 0L ? -1L
                : android.os.SystemClock.elapsedRealtime() - lastDiagAt;

        return "{\"ok\":true,\"ready\":"
                + (url != null && !url.isEmpty() && !"about:blank".equals(url))
                + ",\"url\":\"" + esc(url)
                + "\",\"title\":\"" + esc(title)
                + "\",\"lifecycle\":\"" + esc(lastLifecycleEvent)
                + "\",\"diagPollCount\":" + diagPollCount
                + ",\"lastDiagAgeMs\":" + diagAge
                + ",\"generationState\":\"" + esc(generationState) + "\""
                + ",\"generationObserverPolls\":" + generationObserverPolls
                + ",\"generationEvidence\":\"" + esc(generationEvidence) + "\""
                + ",\"pageDiag\":\"" + esc(lastPageDiag) + "\"}";
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
        lastLifecycleEvent = "onPause";
        Log.i("ASTRA-FLOW", "Lifecycle onPause: url="
                + (webView != null ? webView.getUrl() : "null")
                + ", isFinishing=" + isFinishing());
        super.onPause();
    }

    @Override
    protected void onStop() {
        lastLifecycleEvent = "onStop";
        Log.i("ASTRA-FLOW", "Lifecycle onStop: url="
                + (webView != null ? webView.getUrl() : "null")
                + ", isFinishing=" + isFinishing());
        super.onStop();
    }

    @Override
    protected void onResume() {
        lastLifecycleEvent = "onResume";
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            Log.i("ASTRA-FLOW", "WebView resumed");
        }
    }

    @Override
    protected void onDestroy() {
        lastLifecycleEvent = "onDestroy";
        main.removeCallbacks(diagPoll);
        main.removeCallbacks(generationObserver);
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
