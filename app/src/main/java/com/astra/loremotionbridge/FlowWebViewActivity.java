package com.astra.loremotionbridge;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.JavascriptInterface;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private FlowVideoCaptureBridge videoCaptureBridge;
    private final Set<String> directDownloads = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> projectDownloads = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService flowDownloadExecutor = Executors.newSingleThreadExecutor();
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
    private int autoOpenAttempts = 0;
    private boolean autoOpenCompleted = false;
    private int baselineFlowTileCount = -1;
    private String flowUserAgent = "";
    private final Runnable generationObserver = new Runnable() {
        @Override public void run() {
            if (webView == null || isFinishing() || isDestroyed()
                    || generationStartedAt == 0L) return;
            String script = "(function(){try{const t=(document.body&&document.body.innerText)||'';"
                + "const videos=[...document.querySelectorAll('video')].filter(x=>x.getBoundingClientRect().width>0).length;"
                + "const busy=document.querySelectorAll('[aria-busy=true],[role=progressbar]').length;"
                + "const tiles=document.querySelectorAll('flow-grid-tile-container flow-video-tile').length;"
                + "const baseline=Number.isFinite(window.__astraFlowBaselineTiles)?window.__astraFlowBaselineTiles:-1;"
                + "return {tail:t.slice(-1400),videos:videos,busy:busy,tiles:tiles,baseline:baseline};"
                + "}catch(e){return {error:String(e)};}})()";
            webView.evaluateJavascript(script, value -> {
                generationObserverPolls++;
                if (webView != null) webView.evaluateJavascript(videoCaptureScanScript(), null);
                generationEvidence = value == null ? "" : value;
                try {
                    org.json.JSONObject evidence = new org.json.JSONObject(generationEvidence);
                    String tail = evidence.optString("tail", "").toLowerCase(java.util.Locale.ROOT);
                    int busy = evidence.optInt("busy", 0);
                    int tiles = evidence.optInt("tiles", 0);
                    if (baselineFlowTileCount < 0) baselineFlowTileCount = evidence.optInt("baseline", -1);
                    if (tail.contains("ошибка генерац") || tail.contains("generation failed")
                            || tail.contains("не удалось создать")) {
                        generationState = "failed";
                    } else if (busy == 0 && baselineFlowTileCount >= 0
                            && tiles > baselineFlowTileCount && !autoOpenCompleted) {
                        generationState = "completed_waiting_for_card";
                        Log.i("ASTRA-FLOW-OBSERVER", "New Flow result tile detected; opening from background observer");
                        main.post(FlowWebViewActivity.this::tryAutoOpenLatestFlowVideo);
                    } else if (busy > 0) {
                        generationState = "running";
                    } else {
                        generationState = "submitted_unconfirmed";
                    }
                } catch (Exception parseError) {
                    Log.w("ASTRA-FLOW-OBSERVER", "Could not parse page evidence: " + generationEvidence, parseError);
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
        videoCaptureBridge = new FlowVideoCaptureBridge();
        webView.addJavascriptInterface(videoCaptureBridge, "ASTRAFlowVideo");
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
        flowUserAgent = settings.getUserAgentString();

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
            // Native Chromium request observer. Keep the existing card-opening flow untouched.
            // Only signed MP4 CDN requests are handed to the existing downloader.
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    captureNativeFlowVideoRequest(request.getUrl().toString(), "webresource");
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, String requestUrl) {
                captureNativeFlowVideoRequest(requestUrl, "legacy-webresource");
                return super.shouldInterceptRequest(view, requestUrl);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                status.setText("ASTRA Flow: " + url);
                pageLoaded = true;
                main.postDelayed(FlowWebViewActivity.this::diagnoseFlowPage, 700L);
                main.postDelayed(FlowWebViewActivity.this::installVideoCapture, 1400L);
                main.postDelayed(FlowWebViewActivity.this::installNetworkDiagnostics, 1700L);
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

        // Perchance pattern: retain the dedicated generator Activity while
        // immediately returning the user to ASTRA. Automation and observation
        // continue in this hidden task.
        if (backgroundRequested) {
            main.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    moveTaskToBack(true);
                    Log.i("ASTRA-FLOW", "Perchance-style background task started; WebView retained");
                }
            }, 120L);
        }
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
            // Match Perchance: hide the task first, then automate the loaded page.
            if (backgroundRequested) {
                main.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) moveTaskToBack(true);
                }, 120L);
            }
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
                autoOpenAttempts = 0;
                autoOpenCompleted = false;
                baselineFlowTileCount = -1;
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
                        Log.i("ASTRA-FLOW", "Background task reaffirmed after generate click; native observer remains active");
                    }
                }, 250L);
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
                + "window.__astraFlowBaselineTiles=document.querySelectorAll('flow-grid-tile-container flow-video-tile').length;"
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

    private void publishFlowDownloadProgress(String message) {
        if (message == null || message.trim().isEmpty()) return;
        Log.i("ASTRA-FLOW-DOWNLOAD", message);
        main.post(() -> {
            if (status != null) status.setText("ASTRA Flow: " + message);
            ChatActivity.onFlowDownloadProgress(FlowWebViewActivity.this, message);
        });
    }

    // Called on Chromium's request thread. Do not block or alter the request;
    // hand only signed Flow MP4 URLs to the existing bounded downloader.
    private void captureNativeFlowVideoRequest(String requestUrl, String source) {
        if (requestUrl == null || requestUrl.length() > 8192) return;
        try {
            Uri parsed = Uri.parse(requestUrl);
            if (!"https".equalsIgnoreCase(parsed.getScheme())
                    || !"flow-content.google".equalsIgnoreCase(parsed.getHost())
                    || parsed.getPath() == null
                    || !parsed.getPath().startsWith("/video/")) return;
            Log.i("ASTRA-FLOW-NATIVE-NET", "MP4 request observed source=" + source
                    + " host=" + parsed.getHost() + " path=" + parsed.getPath());
            videoCaptureBridge.downloadVideo(requestUrl);
        } catch (Exception e) {
            Log.w("ASTRA-FLOW-NATIVE-NET", "Could not process native request candidate", e);
        }
    }

    // Captures Flow's page-owned blob: video and streams it to MediaStore in bounded chunks.
    private final class FlowVideoCaptureBridge {
        private Uri target;
        private OutputStream output;
        private long written;
        private static final long MAX_VIDEO_BYTES = 250L * 1024L * 1024L;

        // Diagnostic only: report a signed Flow video URL found in a network response.
        // Never log or persist the temporary signature/query string.
        @JavascriptInterface
        public void reportNetworkCandidate(String videoUrl, String source) {
            if (videoUrl == null || videoUrl.length() > 8192) return;
            try {
                Uri parsed = Uri.parse(videoUrl);
                if (!"https".equalsIgnoreCase(parsed.getScheme())
                        || !"flow-content.google".equalsIgnoreCase(parsed.getHost())
                        || parsed.getPath() == null
                        || !parsed.getPath().startsWith("/video/")) return;
                String safeSource = source == null ? "unknown" : source.replaceAll("[^A-Za-z0-9_:/.-]", "_");
                Log.i("ASTRA-FLOW-NET", "VIDEO_URL_CANDIDATE source=" + safeSource
                        + " host=" + parsed.getHost() + " path=" + parsed.getPath());
                // A network response can expose the real MP4 URL even when the visible
                // <video> element only points at Flow's /asb preview rendition.
                // Consume the signed URL immediately; never log or persist it.
                downloadVideo(videoUrl);
            } catch (Exception ignored) {}
        }

        // Accept only a signed MP4 URL resolved by Flow's own authenticated
        // batchexecute RPCs in the page context. The /asb/ HTMLMediaElement URL
        // is a preview rendition, never the source file.
        @JavascriptInterface
        public boolean downloadResolvedVideo(String mediaId, String videoUrl, String title) {
            if (mediaId == null || !mediaId.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || videoUrl == null || videoUrl.length() > 8192) {
                publishFlowDownloadProgress("Ошибка: Flow вернул некорректный ID или ссылку на видео");
                return false;
            }
            try {
                Uri parsed = Uri.parse(videoUrl);
                if (!"https".equalsIgnoreCase(parsed.getScheme())
                        || !"flow-content.google".equalsIgnoreCase(parsed.getHost())
                        || parsed.getPath() == null || !parsed.getPath().startsWith("/video/")) {
                    publishFlowDownloadProgress("Ошибка: Flow не выдал прямую ссылку на MP4");
                    return false;
                }
                if (!directDownloads.add(mediaId)) return true;
                publishFlowDownloadProgress("Прямая ссылка получена. Подключаюсь к видеохранилищу…");
                flowDownloadExecutor.execute(() -> downloadSignedFlowVideo(videoUrl, mediaId, title));
                return true;
            } catch (Exception e) {
                Log.e("ASTRA-FLOW-DOWNLOAD", "Could not queue resolved video", e);
                publishFlowDownloadProgress("Ошибка запуска загрузки: " +
                        (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                return false;
            }
        }

        @JavascriptInterface
        public void reportDownloadProgress(String message) {
            if (message == null || message.length() > 240) return;
            publishFlowDownloadProgress(message);
        }

        // Downloads a signed MP4 URL exposed by Flow's current batchexecute API.
        // The URL is short-lived; consume it immediately and never persist it in app state.
        @JavascriptInterface
        public boolean downloadVideo(String videoUrl) {
            if (videoUrl == null || videoUrl.length() > 8192) return false;
            final Uri parsed;
            try {
                parsed = Uri.parse(videoUrl);
                if (!"https".equalsIgnoreCase(parsed.getScheme())
                        || !"flow-content.google".equalsIgnoreCase(parsed.getHost())
                        || parsed.getPath() == null
                        || !parsed.getPath().startsWith("/video/")) {
                    Log.w("ASTRA-FLOW-DOWNLOAD", "Rejected non-Flow video URL");
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
            if (!directDownloads.add(videoUrl)) return true;
            flowDownloadExecutor.execute(() -> downloadSignedFlowVideo(videoUrl));
            return true;
        }

        private void reportVideoDownloadResult(String videoUrl, boolean success) {
            String quoted = org.json.JSONObject.quote(videoUrl);
            main.post(() -> {
                if (webView == null || isFinishing() || isDestroyed()) return;
                webView.evaluateJavascript(
                    "(function(){if(window.__astraFlowVideoDownloadResult)"
                    + "window.__astraFlowVideoDownloadResult(" + quoted + "," + success + ");return 'reported';})()",
                    value -> Log.i("ASTRA-FLOW-DOWNLOAD", "Capture result reported=" + value
                            + " success=" + success));
            });
        }

        private void downloadSignedFlowVideo(String videoUrl, String mediaId, String title) {
            Uri target = null;
            boolean published = false;
            HttpURLConnection connection = null;
            try {
                URL requestUrl = new URL(videoUrl);
                connection = (HttpURLConnection) requestUrl.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Accept", "video/mp4,video/*,application/octet-stream,*/*");
                if (flowUserAgent != null && !flowUserAgent.isEmpty())
                    connection.setRequestProperty("User-Agent", flowUserAgent);
                connection.setRequestProperty("Referer", "https://flow.google.com/");
                int code = connection.getResponseCode();
                if (code == 401 || code == 403)
                    throw new java.io.IOException("CDN отклонил подписанную ссылку (HTTP " + code + "). Запроси новую ссылку и повтори");
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL)
                    throw new java.io.IOException("Видеохранилище Flow вернуло HTTP " + code);
                String mime = connection.getContentType();
                String normalizedMime = mime == null ? "" : mime.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
                if (!normalizedMime.startsWith("video/")
                        && !"application/octet-stream".equals(normalizedMime)
                        && !"binary/octet-stream".equals(normalizedMime))
                    throw new java.io.IOException("Flow вернул не видео, а " + (mime == null ? "неизвестный тип" : mime));
                long expected = connection.getContentLengthLong();
                if (expected > FlowVideoCaptureBridge.MAX_VIDEO_BYTES)
                    throw new java.io.IOException("Flow video exceeds 250 MB");

                ContentValues values = new ContentValues();
                String safeTitle = title == null ? "" : title.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
                if (safeTitle.length() > 64) safeTitle = safeTitle.substring(0, 64).trim();
                if (safeTitle.isEmpty()) safeTitle = "flow_" + System.currentTimeMillis();
                if (!safeTitle.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) safeTitle += ".mp4";
                values.put(MediaStore.Video.Media.DISPLAY_NAME, safeTitle);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                if (Build.VERSION.SDK_INT >= 29) {
                    values.put(MediaStore.Video.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MOVIES + "/ASTRA/Flow");
                    values.put(MediaStore.Video.Media.IS_PENDING, 1);
                }
                target = getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (target == null) throw new java.io.IOException("MediaStore insert failed");

                long written = 0L;
                long lastUpdate = 0L;
                int lastPercent = -1;
                try (InputStream input = connection.getInputStream();
                     OutputStream output = getContentResolver().openOutputStream(target, "w")) {
                    if (output == null) throw new java.io.IOException("Android не открыл файл для записи");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        written += count;
                        if (written > FlowVideoCaptureBridge.MAX_VIDEO_BYTES)
                            throw new java.io.IOException("Видео Flow превысило лимит 250 МБ");
                        output.write(buffer, 0, count);
                        long now = android.os.SystemClock.elapsedRealtime();
                        int percent = expected > 0L ? (int) Math.min(100L, (written * 100L) / expected) : -1;
                        if (now - lastUpdate >= 450L || (percent >= 0 && percent != lastPercent)) {
                            lastUpdate = now;
                            lastPercent = percent;
                            String amount = String.format(java.util.Locale.US, "%.1f МБ", written / (1024.0 * 1024.0));
                            String total = expected > 0L ? String.format(java.util.Locale.US, " / %.1f МБ", expected / (1024.0 * 1024.0)) : "";
                            publishFlowDownloadProgress("Скачиваю MP4: " + amount + total + (percent >= 0 ? " · " + percent + "%" : ""));
                        }
                    }
                    output.flush();
                }
                if (written == 0L)
                    throw new java.io.IOException("Flow вернул пустой файл");
                if (expected > 0L && written != expected)
                    throw new java.io.IOException("Загрузка оборвалась: получено " + written + " из " + expected + " байт");

                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                    if (getContentResolver().update(target, ready, null, null) <= 0)
                        throw new java.io.IOException("MediaStore publish failed");
                }
                published = true;
                Log.i("ASTRA-FLOW-DOWNLOAD", "Direct MP4 saved: " + target + " bytes=" + written);
                ChatActivity.onFlowVideoReady(FlowWebViewActivity.this, target);
                reportVideoDownloadResult(videoUrl, true);
                publishFlowDownloadProgress("Готово · MP4 сохранён в Movies/ASTRA/Flow и отправлен в чат");
            } catch (Exception e) {
                reportVideoDownloadResult(videoUrl, false);
                Log.e("ASTRA-FLOW-DOWNLOAD", "Direct Flow MP4 download failed media=" + mediaId, e);
                String reason = e.getMessage();
                if (reason == null || reason.trim().isEmpty()) reason = e.getClass().getSimpleName();
                publishFlowDownloadProgress("Ошибка скачивания: " + reason);
            } finally {
                if (!published && target != null) {
                    try { getContentResolver().delete(target, null, null); }
                    catch (Exception ignored) {}
                }
                if (connection != null) connection.disconnect();
                directDownloads.remove(mediaId);
            }
        }

        @JavascriptInterface
        public synchronized boolean beginVideo(String mime, String fileName) {
            try {
                if (output != null) return false;
                String cleanMime = (mime == null || !mime.startsWith("video/")) ? "video/mp4" : mime.split(";")[0];
                ContentValues values = new ContentValues();
                String safeName = (fileName == null || !fileName.matches("[A-Za-z0-9_.-]+"))
                        ? "flow_" + System.currentTimeMillis() + ".mp4" : fileName;
                values.put(MediaStore.Video.Media.DISPLAY_NAME, safeName);
                values.put(MediaStore.Video.Media.MIME_TYPE, cleanMime);
                if (Build.VERSION.SDK_INT >= 29) {
                    values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ASTRA/Flow");
                    values.put(MediaStore.Video.Media.IS_PENDING, 1);
                }
                target = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (target == null) throw new java.io.IOException("MediaStore insert failed");
                output = getContentResolver().openOutputStream(target, "w");
                if (output == null) throw new java.io.IOException("MediaStore stream unavailable");
                written = 0L;
                Log.i("ASTRA-FLOW-CAPTURE", "Video capture started: " + target);
                return true;
            } catch (Exception e) {
                Log.e("ASTRA-FLOW-CAPTURE", "beginVideo failed", e);
                cleanupVideo(true);
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean appendChunk(String base64) {
            try {
                if (output == null || base64 == null) return false;
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                if (bytes.length == 0) return true;
                if (written + bytes.length > MAX_VIDEO_BYTES) throw new java.io.IOException("video exceeds 250 MB");
                output.write(bytes);
                written += bytes.length;
                return true;
            } catch (Exception e) {
                Log.e("ASTRA-FLOW-CAPTURE", "appendChunk failed", e);
                cleanupVideo(true);
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean finishVideo() {
            try {
                if (output == null || target == null || written == 0L) throw new java.io.IOException("empty video");
                output.flush();
                output.close();
                output = null;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                    if (getContentResolver().update(target, ready, null, null) <= 0)
                        throw new java.io.IOException("publish failed");
                }
                Uri saved = target;
                target = null;
                Log.i("ASTRA-FLOW-CAPTURE", "Video saved: " + saved + " bytes=" + written);
                ChatActivity.onFlowVideoReady(FlowWebViewActivity.this, saved);
                written = 0L;
                return true;
            } catch (Exception e) {
                Log.e("ASTRA-FLOW-CAPTURE", "finishVideo failed", e);
                cleanupVideo(true);
                return false;
            }
        }

        @JavascriptInterface
        public synchronized void failVideo(String reason) {
            Log.w("ASTRA-FLOW-CAPTURE", "Page capture failed: " + reason);
            cleanupVideo(true);
        }

        private void cleanupVideo(boolean deleteTarget) {
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            output = null;
            if (deleteTarget && target != null) {
                try { getContentResolver().delete(target, null, null); } catch (Exception ignored) {}
            }
            target = null;
            written = 0L;
        }
    }

    private void installNetworkDiagnostics() {
        if (webView == null || isFinishing() || isDestroyed()) return;
        String js = "(function(){try{"
            + "if(window.__astraFlowNetDiagInstalled)return 'already-installed';"
            + "window.__astraFlowNetDiagInstalled=true;"
            + "const report=(src,value)=>{try{if(value==null)return;const text=typeof value==='string'?value:JSON.stringify(value);"
            + "const re=/https:\\/\\/flow-content\\.google\\/video\\/[^\\\"'\\\\\\s<>]+/g;"
            + "let m;while((m=re.exec(text))!==null){try{window.ASTRAFlowVideo.reportNetworkCandidate(m[0],src);}catch(e){}}"
            + "}catch(e){}};"
            + "const originalFetch=window.fetch;"
            + "if(originalFetch){window.fetch=function(){const args=arguments;let requestUrl='';try{const q=args[0];requestUrl=typeof q==='string'?q:(q&&q.url)||'';}catch(e){}"
            + "const promise=originalFetch.apply(this,args);promise.then(response=>{try{"
            + "if(requestUrl)report('fetch-request',requestUrl);"
            + "const type=response.headers&&response.headers.get('content-type')||'';"
            + "if(/json|text|javascript/i.test(type))response.clone().text().then(body=>report('fetch-response',body));"
            + "}catch(e){}}).catch(()=>{});return promise;};}"
            + "const XHR=window.XMLHttpRequest;"
            + "if(XHR&&XHR.prototype){const oldOpen=XHR.prototype.open;const oldSend=XHR.prototype.send;"
            + "XHR.prototype.open=function(method,url){this.__astraFlowUrl=String(url||'');return oldOpen.apply(this,arguments);};"
            + "XHR.prototype.send=function(){this.addEventListener('load',function(){try{"
            + "if(this.__astraFlowUrl)report('xhr-request',this.__astraFlowUrl);"
            + "if(this.responseType===''||this.responseType==='text')report('xhr-response',this.responseText);"
            + "else if(this.responseType==='json')report('xhr-response',this.response);"
            + "}catch(e){}},{once:true});return oldSend.apply(this,arguments);};}"
            + "return 'network-diagnostics-installed';"
            + "}catch(e){return 'network-diagnostics-error:'+String(e);}})()";
        webView.evaluateJavascript(js, value ->
                Log.i("ASTRA-FLOW-NET", "Network diagnostics setup=" + value));
    }

    private String videoCaptureScanScript() {
        return "(function(){try{window.__astraFlowVideoScan&&window.__astraFlowVideoScan();return 'scan';}catch(e){return String(e);}})()";
    }

    private void installVideoCapture() {
        if (webView == null || isFinishing() || isDestroyed()) return;
        String js = "(function(){"
            + "if(window.__astraFlowVideoCaptureInstalled){window.__astraFlowVideoScan&&window.__astraFlowVideoScan();return 'already-installed-rescanned';}"
            + "window.__astraFlowVideoCaptureInstalled=true;"
            + "window.__astraFlowCaptureDone=window.__astraFlowCaptureDone||new Set();"
            + "window.__astraFlowCaptureBusy=window.__astraFlowCaptureBusy||new Set();"
            + "const observed=window.__astraFlowObservedRoots=window.__astraFlowObservedRoots||new WeakSet();"
            + "async function capture(v){let u='';try{u=v.currentSrc||v.src||'';if(!u){const s=v.querySelector('source[src]');u=s?s.src:'';}"
            + "if(!u||window.__astraFlowCaptureDone.has(u)||window.__astraFlowCaptureBusy.has(u))return;"
            + "if(!u.startsWith('blob:')&&!u.startsWith('https://flow-content.google/video/')&&!u.startsWith('https://flow.google.com/asb/')&&!u.startsWith('https://labs.google/asb/'))return;"
            + "window.__astraFlowCaptureBusy.add(u);"
            + "if(u.startsWith('https://flow.google.com/asb/')||u.startsWith('https://labs.google/asb/')){"
            + "window.__astraFlowCaptureBusy.delete(u);"
            + "const project=(location.pathname.match(/\\/project\\/([0-9a-f-]{36})/i)||[])[1]||location.href;"
            + "if(window.__astraFlowDownloadProject!==project){window.__astraFlowDownloadProject=project;window.__astraFlowProjectDownloadRequested=false;}"
            + "if(!window.__astraFlowProjectDownloadRequested){window.__astraFlowProjectDownloadRequested=true;"
            + "if(!window.ASTRAFlowVideo.downloadLatestProjectVideo(location.href)){window.__astraFlowProjectDownloadRequested=false;console.warn('ASTRA_FLOW_PROJECT_DOWNLOAD_REJECTED');}}"
            + "return;}"
            + "if(u.startsWith('https://flow-content.google/video/')){if(!window.ASTRAFlowVideo.downloadVideo(u)){window.__astraFlowCaptureBusy.delete(u);console.warn('ASTRA_FLOW_DOWNLOAD_QUEUE_REJECTED');}return;}"
            + "const r=await fetch(u,{credentials:'include'});if(!r.ok)throw new Error('media_http_'+r.status);"
            + "const b=await r.blob();if(!b.size)throw new Error('empty_media_blob');if(!/^video\\//i.test(b.type||'')&&!u.startsWith('blob:'))throw new Error('unexpected_media_type_'+(b.type||'unknown'));"
            + "if(!window.ASTRAFlowVideo.beginVideo(b.type||'video/mp4','flow_'+Date.now()+'.mp4'))throw new Error('native_begin_failed');"
            + "const size=48*1024;for(let i=0;i<b.size;i+=size){const a=new Uint8Array(await b.slice(i,Math.min(i+size,b.size)).arrayBuffer());"
            + "let bin='';for(let j=0;j<a.length;j++)bin+=String.fromCharCode(a[j]);"
            + "if(!window.ASTRAFlowVideo.appendChunk(btoa(bin)))throw new Error('native_chunk_failed_'+i);}"
            + "if(!window.ASTRAFlowVideo.finishVideo())throw new Error('native_finish_failed');"
            + "window.__astraFlowCaptureDone.add(u);window.__astraFlowCaptureBusy.delete(u);"
            + "}catch(e){window.__astraFlowCaptureBusy.delete(u);try{window.ASTRAFlowVideo.failVideo(String(e));}catch(x){}"
            + "console.error('ASTRA_FLOW_VIDEO_CAPTURE:'+e);}}"
            + "function scanRoot(root){if(!root)return;try{root.querySelectorAll('video').forEach(v=>{if(v.readyState>=1)capture(v);});"
            + "if(!observed.has(root)){observed.add(root);new MutationObserver(()=>window.__astraFlowVideoScan()).observe(root,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});}"
            + "root.querySelectorAll('*').forEach(n=>{if(n.shadowRoot)scanRoot(n.shadowRoot);});"
            + "root.querySelectorAll('iframe').forEach(f=>{try{if(f.contentDocument)scanRoot(f.contentDocument);}catch(e){}});"
            + "}catch(e){console.warn('ASTRA_FLOW_SCAN_ROOT:'+e);}}"
            + "window.__astraFlowVideoScan=function(){scanRoot(document);try{performance.getEntriesByType('resource').forEach(e=>{const u=e.name||'';if(u.startsWith('https://flow-content.google/video/'))window.ASTRAFlowVideo.reportNetworkCandidate(u,'performance-resource');});}catch(e){}};"
            + "window.__astraFlowVideoDownloadResult=function(u,ok){if(!u)return;if(window.__astraFlowCaptureBusy)window.__astraFlowCaptureBusy.delete(u);"
            + "if(ok)window.__astraFlowCaptureDone.add(u);else console.warn('ASTRA_FLOW_DIRECT_DOWNLOAD_FAILED; retrying');};"
            + "window.__astraFlowVideoScan();setInterval(window.__astraFlowVideoScan,2500);"
            + "return 'video-capture-installed-shadow-aware-retryable';})()";
        webView.evaluateJavascript(js, value -> Log.i("ASTRA-FLOW-CAPTURE", "Capture bridge setup=" + value));
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

    // When the user returns to Flow after generation, open the newest project tile
    // automatically. The existing video bridge then downloads video.currentSrc.
    private void tryAutoOpenLatestFlowVideo() {
        if (webView == null || isFinishing() || isDestroyed() || autoOpenCompleted) return;
        if (++autoOpenAttempts > 8) {
            Log.w("ASTRA-FLOW-AUTOOPEN", "No ready video tile after resume; attempts=" + autoOpenAttempts);
            return;
        }
        String js = "(function(){try{"
            + "if(!location.href.includes('/project/'))return 'NOT_PROJECT';"
            + "const v=document.querySelector('video');"
            + "if(v&&(v.currentSrc||v.src)){return 'ALREADY_OPEN';}"
            + "const t=document.querySelector('flow-grid-tile-container flow-video-tile');"
            + "if(!t)return 'NO_CARD';"
            + "t.click();return 'OPENED';"
            + "}catch(e){return 'ERROR:'+String(e);}})()";
        webView.evaluateJavascript(js, value -> {
            String result = value == null ? "" : value;
            Log.i("ASTRA-FLOW-AUTOOPEN", "attempt=" + autoOpenAttempts + " result=" + result);
            if (result.contains("OPENED") || result.contains("ALREADY_OPEN")) {
                autoOpenCompleted = true;
                main.postDelayed(() -> {
                    if (webView != null && !isFinishing() && !isDestroyed()) {
                        webView.evaluateJavascript(videoCaptureScanScript(), null);
                        main.postDelayed(FlowWebViewActivity.this::installVideoCapture, 900L);
                    }
                }, 1200L);
            } else if (result.contains("NOT_PROJECT")) {
                Log.w("ASTRA-FLOW-AUTOOPEN", "Current page is not the Flow project grid");
            } else if (autoOpenAttempts < 8) {
                main.postDelayed(this::tryAutoOpenLatestFlowVideo, 1500L);
            }
        });
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
