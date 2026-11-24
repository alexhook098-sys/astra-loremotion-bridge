package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/** ZSky WebView embedded in ChatActivity's invisible workerHost (no Activity/window). */
public final class ZSkyBrowserPanel extends LinearLayout {
    public static final String ZSKY_URL = "https://zsky.ai/create";
    private final Activity host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private WebView zskyPopup;
    private ZSkyVideoCaptureBridge videoCaptureBridge;
    private TextView status;
    private PowerManager.WakeLock wakeLock;
    private String pendingPrompt;
    private boolean promptInjected;
    private boolean pageLoaded;
    private boolean disposed;
    private int automationAttempts;
    private int observerPolls;
    private long generationStartedAt;
    private int baselineVideoCount = -1;
    private String generationState = "idle";
    private String generationEvidence = "";
    private StatusListener statusListener;
    private int downloadAttempts;
    private boolean videoSaved;

    public interface StatusListener { void onZSkyStatus(String state, String detail); }
    public void setStatusListener(StatusListener listener) { statusListener = listener; }
    private void notifyStatus(String state, String detail) {
        Log.i("ASTRA-ZSKY-STATE", state + " · " + detail);
        if (statusListener != null) host.runOnUiThread(() -> {
            if (!disposed && statusListener != null) statusListener.onZSkyStatus(state, detail);
        });
    }
    private void setGenerationState(String state, String detail) {
        if (state == null || state.equals(generationState)) return;
        generationState = state;
        notifyStatus(state, detail);
    }
    public void onVideoSaved() {
        videoSaved = true;
        setGenerationState("delivered", "Видео сохранено в Movies/ASTRA/ZSky");
    }

    private final Runnable generationObserver = new Runnable() {
        @Override public void run() {
            if (disposed || webView == null || generationStartedAt == 0L
                    || host.isFinishing() || host.isDestroyed()) return;
            String js = "(function(){const t=(document.body&&document.body.innerText)||'';"
                + "const hasDownload=/\\bdownload\\b/i.test(t);"
                + "const final=(/rendered on the free lane/i.test(t)||( /re-animate/i.test(t)&&/video editor/i.test(t)&&/\\bdelete\\b/i.test(t)))&&hasDownload;"
                + "let clicked=false;const now=Date.now();if(final&&(!window.__astraZSkyLastDownloadAttempt||now-window.__astraZSkyLastDownloadAttempt>8000)){"
                + "const b=[...document.querySelectorAll('button,[role=button],a')].find(x=>{"
                + "const q=((x.innerText||x.textContent||'')+' '+(x.getAttribute('aria-label')||'')).replace(/\\s+/g,' ').trim().toLowerCase();"
                + "return q==='download'&&!x.disabled&&x.getAttribute('aria-disabled')!=='true';});"
                + "if(b){window.__astraZSkyLastDownloadAttempt=now;window.__astraZSkyDownloadAttemptCount=(window.__astraZSkyDownloadAttemptCount||0)+1;window.__astraZSkyNativeDownloadSince=performance.now();b.click();clicked=true;}}"
                + "const since=window.__astraZSkyNativeDownloadSince||Infinity;"
                + "const urls=final?performance.getEntriesByType('resource').filter(x=>x.startTime>=since).map(x=>x.name).filter(x=>/^https:\\/\\//i.test(x)&&/\\.mp4(?:$|[?#])/i.test(x)):[];"
                + "return JSON.stringify({tail:t.slice(-1400),videos:document.querySelectorAll('video').length,busy:document.querySelectorAll('[aria-busy=true],[role=progressbar]').length,final,clicked,attempts:window.__astraZSkyDownloadAttemptCount||0,urls});})()";
            webView.evaluateJavascript(js, value -> {
                observerPolls++;
                generationEvidence = value == null ? "" : value;
                if (observerPolls % 12 == 0 && "running".equals(generationState)) {
                    long elapsed = Math.max(0L, (System.currentTimeMillis() - generationStartedAt) / 1000L);
                    notifyStatus("render_heartbeat", "Наблюдение активно · " + elapsed + " сек · проверок страницы: " + observerPolls);
                }
                try {
                    String payload = generationEvidence;
                    if (payload.startsWith("\"")) payload = (String)new org.json.JSONTokener(payload).nextValue();
                    org.json.JSONObject e = new org.json.JSONObject(payload);
                    String tail=e.optString("tail","").toLowerCase(java.util.Locale.ROOT);
                    if(tail.contains("generation failed")||tail.contains("ошибка генерации")) setGenerationState("failed","ZSky сообщил об ошибке генерации");
                    else if(e.optBoolean("final",false)) setGenerationState("final_stage","Финальный экран ZSky обнаружен");
                    else if(e.optInt("busy",0)>0) setGenerationState("running","ZSky выполняет рендеринг");
                    else if(baselineVideoCount>=0&&e.optInt("videos",0)>baselineVideoCount) setGenerationState("completed_unconfirmed","Видеоэлемент появился, проверяю выдачу MP4");
                    int attempts=e.optInt("attempts",0);
                    if(attempts>downloadAttempts){downloadAttempts=attempts;notifyStatus("download_retry","Запрос Download №"+attempts+" отправлен; проверяю получение файла");}
                    org.json.JSONArray urls=e.optJSONArray("urls");
                    if(urls!=null)for(int i=0;i<urls.length();i++){String u=urls.optString(i,"");if(!u.isEmpty())downloadFinalZSkyMp4(u,"embedded-final-stage-observer");}
                } catch(Exception ex){Log.w("ASTRA-ZSKY-OBSERVER","Evidence parse failed",ex);}
                if(webView!=null&&!disposed&&!host.isFinishing()&&!host.isDestroyed())main.postDelayed(this,2500L);
            });
        }
    };

    public ZSkyBrowserPanel(Activity host, String prompt) {
        super(host);
        this.host=host;
        this.pendingPrompt=prompt==null?null:prompt.trim();
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF080B18);
        status=new TextView(host);
        status.setTextColor(0xFFE5C7FF);
        status.setTextSize(12);
        status.setText("ASTRA ZSky: embedded worker starting…");
        addView(status,new LinearLayout.LayoutParams(-1,-2));
        webView=new WebView(host);
        videoCaptureBridge=new ZSkyVideoCaptureBridge(host);
        webView.addJavascriptInterface(videoCaptureBridge,"ASTRAZSkyVideo");
        addView(webView,new LinearLayout.LayoutParams(-1,0,1f));
        if(android.os.Build.VERSION.SDK_INT>=android.os.Build.VERSION_CODES.O)
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT,false);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        WebSettings settings=webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cookies=CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView,true);
        PowerManager pm=(PowerManager)host.getSystemService(Context.POWER_SERVICE);
        if(pm!=null){wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ASTRA:ZSkyEmbedded");wakeLock.setReferenceCounted(false);wakeLock.acquire();}
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onCreateWindow(WebView view,boolean dialog,boolean userGesture,android.os.Message resultMsg){
                if(zskyPopup!=null){try{zskyPopup.destroy();}catch(Exception ignored){}}
                zskyPopup=new WebView(host);
                WebSettings ps=zskyPopup.getSettings();ps.setJavaScriptEnabled(true);ps.setDomStorageEnabled(true);ps.setUserAgentString(webView.getSettings().getUserAgentString());
                zskyPopup.setWebViewClient(new WebViewClient(){
                    @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){
                        String u=req==null||req.getUrl()==null?"":req.getUrl().toString();
                        if(u.matches("(?i)^https://.*\\.mp4([?#].*)?$")){downloadFinalZSkyMp4(u,"target-blank-navigation");return true;}
                        return false;
                    }
                });
                zskyPopup.setDownloadListener((u,ua,d,m,l)->{if(videoCaptureBridge!=null)videoCaptureBridge.downloadRemoteVideo(u,ua,webView.getUrl());});
                ((WebView.WebViewTransport)resultMsg.obj).setWebView(zskyPopup);resultMsg.sendToTarget();return true;
            }
        });
        webView.setDownloadListener((u,ua,d,m,l)->{boolean ok=videoCaptureBridge!=null&&videoCaptureBridge.downloadRemoteVideo(u,ua,webView.getUrl());Log.i("ASTRA-ZSKY-DOWNLOAD","embedded download queued="+ok);});
        webView.setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){pageLoaded=false;status.setText("ASTRA ZSky: loading…");}
            @Override public void onPageFinished(WebView v,String url){
                pageLoaded=true;status.setText("ASTRA ZSky: embedded studio ready");
                main.postDelayed(ZSkyBrowserPanel.this::installVideoCapture,1400L);
                if(pendingPrompt!=null)main.postDelayed(ZSkyBrowserPanel.this::runPendingPrompt,1200L);
                Log.i("ASTRA-ZSKY","Embedded page finished: "+url);
            }
        });
        webView.loadUrl(ZSKY_URL);
    }

    public void submitPrompt(String prompt){
        if(prompt==null||prompt.trim().isEmpty()||disposed)return;
        pendingPrompt=prompt.trim();promptInjected=false;automationAttempts=0;downloadAttempts=0;videoSaved=false;generationStartedAt=0L;setGenerationState("queued","Запрос подготовлен");
        if(pageLoaded)main.postDelayed(this::runPendingPrompt,300L);
    }

    public void onHostResume(){if(webView!=null){webView.onResume();webView.resumeTimers();}}
    public void onHostPause(){if(webView!=null)webView.onPause();}

    private void downloadFinalZSkyMp4(String url,String source){
        if(videoCaptureBridge==null||webView==null)return;
        boolean queued=videoCaptureBridge.downloadRemoteVideo(url,webView.getSettings().getUserAgentString(),webView.getUrl());
        Log.i("ASTRA-ZSKY-DOWNLOAD","embedded final MP4 queued="+queued+" source="+source);
        if(status!=null)status.setText(queued?"ASTRA ZSky: saving final video…":"ASTRA ZSky: download could not start");
    }

    private String videoCaptureScanScript(){
        return "(function(){try{window.__astraZSkyVideoScan&&window.__astraZSkyVideoScan();return 'scan';}catch(e){return String(e);}})()";
    }

    private void runPendingPrompt() {
        if (webView == null || pendingPrompt == null || !pageLoaded || host.isFinishing() || host.isDestroyed()) return;
        if (++automationAttempts > 45) {
            setGenerationState("automation_timeout","Не удалось подготовить форму ZSky за отведённое время");
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
                setGenerationState("prompt_entered","Промпт введён");
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
                        setGenerationState("submitted_unconfirmed","Generate нажат, ожидаю подтверждение рендеринга");
                        pendingPrompt = null;
                        main.removeCallbacks(generationObserver);
                        main.postDelayed(generationObserver, 1500L);
                    } else {
                        main.postDelayed(this::runPendingPrompt, 1000L);
                    }
                });
            } else {
                main.postDelayed(this::runPendingPrompt, 1000L);
            }
        });
    }

    private void installVideoCapture() {
        if (webView == null || host.isFinishing() || host.isDestroyed()) return;
        String js = "(function(){"
            + "if(window.__astraZSkyCaptureInstalled){window.__astraZSkyVideoScan&&window.__astraZSkyVideoScan();return 'already-installed-rescanned';}"
            + "window.__astraZSkyCaptureInstalled=true;"
            + "window.__astraZSkyCaptureDone=window.__astraZSkyCaptureDone||new Set();"
            + "window.__astraZSkyCaptureBusy=window.__astraZSkyCaptureBusy||new Set();"
            + "const roots=new WeakSet(),videos=new WeakSet();"
            + "function finalResultVisible(){if(window.__astraZSkyFinalResult)return true;const t=(document.body&&document.body.innerText||'').toLowerCase();if(t.includes('rendered on the free lane')&&t.includes('download')){window.__astraZSkyFinalResult=true;console.info('ASTRA_ZSKY_FINAL_STAGE_CONFIRMED');return true;}return false;}"
            + "function queueDownloadUrl(u,source){try{if(!u||!/^https:\\/\\//i.test(u))return false;const x=new URL(u,location.href);if(!/\\.mp4(?:$|[?#])/i.test(x.href))return false;if(window.__astraZSkyDownloadSeen.has(x.href))return true;window.__astraZSkyDownloadSeen.add(x.href);const ok=window.ASTRAZSkyVideo.downloadRemoteVideo(x.href,navigator.userAgent,location.href);console.info('ASTRA_ZSKY_DOWNLOAD_URL',source,ok,x.href);return !!ok;}catch(e){console.warn('ASTRA_ZSKY_DOWNLOAD_URL_ERROR',String(e));return false;}}"
            + "window.__astraZSkyDownloadSeen=window.__astraZSkyDownloadSeen||new Set();"
            + "function inspectDownloadResult(since){const links=[...document.querySelectorAll('a[href]')].map(a=>a.href);for(const u of links)if(queueDownloadUrl(u,'download-anchor'))return true;const entries=performance.getEntriesByType('resource').filter(e=>e.startTime>=since).map(e=>e.name);for(const u of entries)if(queueDownloadUrl(u,'post-download-resource'))return true;return false;}"
            + "if(!window.__astraZSkyDownloadClickHook){window.__astraZSkyDownloadClickHook=true;document.addEventListener('click',e=>{const b=e.target&&e.target.closest?e.target.closest('button,[role=button],a'):null;if(!b||!finalResultVisible())return;const label=((b.innerText||b.textContent||'')+' '+(b.getAttribute('aria-label')||'')).replace(/\\s+/g,' ').trim().toLowerCase();if(!/(^|\\b)download(\\b|$)/.test(label))return;const since=performance.now();console.info('ASTRA_ZSKY_DOWNLOAD_BUTTON_CLICKED');[100,350,900,1800,3200].forEach(ms=>setTimeout(()=>inspectDownloadResult(since),ms));},true);}"
            + "if(!window.__astraZSkyAutoDownloadTimer){window.__astraZSkyAutoDownloadTimer=setInterval(()=>{if(!finalResultVisible()||window.__astraZSkyDownloadButtonClicked)return;const b=[...document.querySelectorAll('button,[role=button],a')].find(x=>{const t=((x.innerText||x.textContent||'')+' '+(x.getAttribute('aria-label')||'')).replace(/\\s+/g,' ').trim().toLowerCase();return t==='download'&&!x.disabled&&x.getAttribute('aria-disabled')!=='true';});if(b){window.__astraZSkyDownloadButtonClicked=true;console.info('ASTRA_ZSKY_AUTO_CLICK_DOWNLOAD');b.click();}},1000);}"
            + "async function capture(v){if(!finalResultVisible())return;let u='';try{u=v.currentSrc||v.src||'';if(!u){const q=v.querySelector('source[src]');u=q?q.src:'';}"
            + "if(!u)return;"
            + "if(window.__astraZSkyCaptureDone.has(u)||window.__astraZSkyCaptureBusy.has(u))return;"
            + "if(u.startsWith('https://')){console.info('ASTRA_ZSKY_PLAYER_URL_IGNORED_USE_DOWNLOAD_BUTTON',u);return;}"
            + "if(!u.startsWith('blob:'))return;"
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
            + "function scan(root){if(!root)return;try{root.querySelectorAll('video').forEach(v=>{watch(v);if(finalResultVisible())capture(v);});"
            + "if(!roots.has(root)){roots.add(root);new MutationObserver(()=>window.__astraZSkyVideoScan()).observe(root,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});}"
            + "root.querySelectorAll('*').forEach(n=>{if(n.shadowRoot)scan(n.shadowRoot);});"
            + "root.querySelectorAll('iframe').forEach(f=>{try{if(f.contentDocument)scan(f.contentDocument);}catch(e){}});"
            + "}catch(e){console.warn('ASTRA_ZSKY_SCAN:'+e);}}"
            + "window.__astraZSkyVideoScan=function(){scan(document);};window.__astraZSkyVideoScan();"
            + "setInterval(window.__astraZSkyVideoScan,2500);return 'zsky-blob-capture-installed';})()";
        webView.evaluateJavascript(js, value -> Log.i("ASTRA-ZSKY-CAPTURE", "bridge setup=" + value));
    }

    public String statusJson(){
        return "{\"ok\":true,\"embedded\":true,\"ready\":"+pageLoaded+",\"state\":\""+generationState+"\",\"observerPolls\":"+observerPolls+"}";
    }

    public void dispose(){
        if(disposed)return;disposed=true;
        main.removeCallbacks(generationObserver);
        if(videoCaptureBridge!=null)videoCaptureBridge.abortVideo();
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        wakeLock=null;
        if(zskyPopup!=null){zskyPopup.stopLoading();zskyPopup.destroy();zskyPopup=null;}
        if(webView!=null){webView.stopLoading();webView.setWebViewClient(null);webView.destroy();webView=null;}
    }
}
