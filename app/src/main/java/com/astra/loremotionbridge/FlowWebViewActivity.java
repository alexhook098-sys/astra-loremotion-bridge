package com.astra.loremotionbridge;

import android.app.Activity;
import java.io.OutputStream;
import java.io.IOException;
import android.webkit.DownloadListener;
import android.provider.MediaStore;
import android.os.Environment;
import android.os.Build;
import android.net.Uri;
import android.content.Context;
import android.content.ContentValues;
import android.app.DownloadManager;
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
    private String lastLifecycleEvent = "created";
    private String lastPageDiag = "";
    private long lastDiagAt = 0L;
    private int diagPollCount = 0;
    private boolean diagRequestPending = false;

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


        // Flow export support: WebView does not download files unless a DownloadListener
        // forwards authenticated HTTPS requests (or blob URLs) to Android.
        webView.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent,
                    String contentDisposition, String mimeType, long contentLength) {
                Log.i("ASTRA-FLOW-DL", "Download callback url=" + url
                        + " mime=" + mimeType + " bytes=" + contentLength);
                if (url == null || url.isEmpty()) {
                    Log.e("ASTRA-FLOW-DL", "Rejected empty download URL");
                    return;
                }
                if (url.startsWith("blob:")) {
                    startFlowBlobDownload(url, mimeType);
                    return;
                }
                if (!url.startsWith("https://")) {
                    Log.w("ASTRA-FLOW-DL", "Rejected non-HTTPS download URL");
                    return;
                }
                try {
                    DownloadManager.Request request =
                            new DownloadManager.Request(Uri.parse(url));
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (userAgent != null && !userAgent.isEmpty())
                        request.addRequestHeader("User-Agent", userAgent);
                    if (cookie != null && !cookie.isEmpty())
                        request.addRequestHeader("Cookie", cookie);
                    request.setMimeType(mimeType != null && !mimeType.isEmpty()
                            ? mimeType : "video/mp4");
                    request.setTitle("ASTRA Flow video");
                    request.setDescription("Downloading generated Flow video");
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setAllowedOverMetered(true);
                    request.setAllowedOverRoaming(true);
                    String ext = (mimeType != null && mimeType.toLowerCase().contains("gif"))
                            ? ".gif" : ".mp4";
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                            "ASTRA_Flow_" + System.currentTimeMillis() + ext);
                    DownloadManager manager = (DownloadManager)
                            getSystemService(Context.DOWNLOAD_SERVICE);
                    if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
                    long id = manager.enqueue(request);
                    Log.i("ASTRA-FLOW-DL", "Download queued id=" + id);
                    if (status != null) status.setText("ASTRA Flow: download queued (" + id + ")");
                } catch (Exception e) {
                    Log.e("ASTRA-FLOW-DL", "Could not queue Flow download", e);
                    if (status != null) status.setText("ASTRA Flow download error: " + e.getMessage());
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                status.setText("ASTRA Flow: " + url);
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
            url = "https://flow.google.com/u/2/project/4daa2354-7afc-44e5-b51d-1652eb4aaaac";
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


    // Flow uses browser-created blob URLs for some exports. DownloadManager cannot
    // fetch blob: URLs, so read the blob in the authenticated WebView and stream it
    // to MediaStore in bounded chunks without loading the whole video into Java memory.
    private void startFlowBlobDownload(String blobUrl, String mimeType) {
        if (webView == null || blobUrl == null ||
                !blobUrl.startsWith("blob:https://flow.google.com/")) {
            Log.w("ASTRA-FLOW-DL", "Blocked blob URL outside Flow origin");
            if (status != null) status.setText("ASTRA Flow: unsupported blob origin");
            return;
        }
        String init = "window.__astraFlowBlobInit='';(async()=>{try{" +
                "const r=await fetch(" + org.json.JSONObject.quote(blobUrl) + ");" +
                "if(!r.ok)throw new Error('blob fetch failed '+r.status);" +
                "const b=await r.blob();if(!b.size)throw new Error('empty blob');" +
                "if(b.size>1073741824)throw new Error('video exceeds 1 GiB limit');" +
                "window.__astraFlowBlob={blob:b,size:b.size};" +
                "window.__astraFlowBlobInit='OK|'+b.size+'|'+encodeURIComponent(b.type||'video/mp4');" +
                "}catch(e){window.__astraFlowBlobInit='ERR|'+String(e&&e.message||e)}})();'STARTED'";
        main.post(() -> {
            if (webView == null) return;
            long deadline = System.currentTimeMillis() + 30000L;
            webView.evaluateJavascript(init, value -> pollFlowBlobInit(mimeType, deadline));
        });
    }

    private void pollFlowBlobInit(String mimeType, long deadline) {
        if (webView == null) return;
        webView.evaluateJavascript("window.__astraFlowBlobInit||''", value -> {
            String result = jsString(value);
            if (result == null || result.isEmpty()) {
                if (System.currentTimeMillis() >= deadline) {
                    Log.e("ASTRA-FLOW-DL", "Blob preparation timed out");
                    if (status != null) status.setText("ASTRA Flow: export preparation timed out");
                } else main.postDelayed(() -> pollFlowBlobInit(mimeType, deadline), 120);
                return;
            }
            if (!result.startsWith("OK|")) {
                String reason = result.startsWith("ERR|") ? result.substring(4) : result;
                Log.e("ASTRA-FLOW-DL", "Blob preparation failed: " + reason);
                if (status != null) status.setText("ASTRA Flow export error: " + reason);
                return;
            }
            try {
                String[] p = result.split("\\|", 3);
                long size = Long.parseLong(p[1]);
                String type = p.length > 2
                        ? java.net.URLDecoder.decode(p[2], "UTF-8") : mimeType;
                beginFlowBlobWrite(size, type);
            } catch (Exception e) {
                Log.e("ASTRA-FLOW-DL", "Invalid blob metadata", e);
                if (status != null) status.setText("ASTRA Flow: invalid export metadata");
            }
        });
    }

    private void beginFlowBlobWrite(long size, String mimeType) {
        if (Build.VERSION.SDK_INT < 29) {
            if (status != null) status.setText("ASTRA Flow blob export requires Android 10+");
            return;
        }
        Uri target = null;
        OutputStream out = null;
        try {
            String type = mimeType != null && mimeType.startsWith("video/")
                    ? mimeType : "video/mp4";
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "ASTRA_Flow_" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.MediaColumns.MIME_TYPE, type);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            target = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new IOException("MediaStore insert returned null");
            out = getContentResolver().openOutputStream(target, "w");
            if (out == null) throw new IOException("Cannot open Downloads output");
            if (status != null) status.setText("ASTRA Flow: saving video (" + size + " bytes)");
            pumpFlowBlobChunk(target, out, size, 0L);
        } catch (Exception e) {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            if (target != null) try { getContentResolver().delete(target, null, null); } catch (Exception ignored) {}
            Log.e("ASTRA-FLOW-DL", "Could not create Downloads file", e);
            if (status != null) status.setText("ASTRA Flow save error: " + e.getMessage());
        }
    }

    private void pumpFlowBlobChunk(Uri target, OutputStream out, long size, long offset) {
        final int chunkSize = 24576;
        if (offset >= size) {
            try {
                out.close();
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(target, done, null, null);
                if (webView != null) webView.evaluateJavascript(
                        "delete window.__astraFlowBlob;delete window.__astraFlowBlobInit;delete window.__astraFlowChunk", null);
                Log.i("ASTRA-FLOW-DL", "Blob saved successfully bytes=" + size);
                if (status != null) status.setText("ASTRA Flow video saved to Downloads");
            } catch (Exception e) {
                Log.e("ASTRA-FLOW-DL", "Could not finalize Flow file", e);
                if (status != null) status.setText("ASTRA Flow finalize error: " + e.getMessage());
            }
            return;
        }
        String js = "window.__astraFlowChunk='';(async()=>{try{" +
                "const x=window.__astraFlowBlob;if(!x)throw new Error('blob state missing');" +
                "const a=await x.blob.slice(" + offset + "," + Math.min(size, offset + chunkSize) + ").arrayBuffer();" +
                "const u=new Uint8Array(a);let s='';for(let i=0;i<u.length;i++)s+=String.fromCharCode(u[i]);" +
                "window.__astraFlowChunk='CHUNK|'+btoa(s);" +
                "}catch(e){window.__astraFlowChunk='ERR|'+String(e&&e.message||e)}})();'STARTED'";
        main.post(() -> {
            if (webView == null) { failFlowBlob(target, out, "WebView closed"); return; }
            long deadline = System.currentTimeMillis() + 15000L;
            webView.evaluateJavascript(js, value ->
                    pollFlowBlobChunk(target, out, size, offset, deadline));
        });
    }

    private void pollFlowBlobChunk(Uri target, OutputStream out, long size,
            long offset, long deadline) {
        if (webView == null) { failFlowBlob(target, out, "WebView closed"); return; }
        webView.evaluateJavascript("window.__astraFlowChunk||''", value -> {
            String result = jsString(value);
            if (result == null || result.isEmpty()) {
                if (System.currentTimeMillis() >= deadline)
                    failFlowBlob(target, out, "chunk read timed out");
                else main.postDelayed(() ->
                        pollFlowBlobChunk(target, out, size, offset, deadline), 100);
                return;
            }
            if (!result.startsWith("CHUNK|")) {
                String reason = result.startsWith("ERR|") ? result.substring(4) : result;
                failFlowBlob(target, out, reason);
                return;
            }
            try {
                byte[] bytes = android.util.Base64.decode(
                        result.substring(6), android.util.Base64.DEFAULT);
                if (bytes.length == 0) throw new IOException("empty chunk");
                out.write(bytes);
                pumpFlowBlobChunk(target, out, size, offset + bytes.length);
            } catch (Exception e) {
                failFlowBlob(target, out, "write failed: " + e.getMessage());
            }
        });
    }

    private void failFlowBlob(Uri target, OutputStream out, String reason) {
        try { out.close(); } catch (Exception ignored) {}
        try { getContentResolver().delete(target, null, null); } catch (Exception ignored) {}
        if (webView != null) webView.evaluateJavascript("delete window.__astraFlowBlob", null);
        Log.e("ASTRA-FLOW-DL", "Blob export failed: " + reason);
        if (status != null) status.setText("ASTRA Flow export failed: " + reason);
    }

    private static String jsString(String value) {
        if (value == null || "null".equals(value)) return null;
        try {
            Object parsed = new org.json.JSONTokener(value).nextValue();
            return parsed instanceof String ? (String) parsed : String.valueOf(parsed);
        } catch (Exception e) { return value; }
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

    // Executes Flow's native More options -> Download media -> 720p Original size path.
    // The WebView DownloadListener above owns the actual file transfer and persistence.
    public void downloadOriginal720(android.webkit.ValueCallback<String> callback) {
        final String script =
            "(function(){"
            + "window.__astraFlowDownloadAction='RUNNING';"
            + "const items=()=>[...document.querySelectorAll('[role=menuitem]')];"
            + "const findDownload=()=>items().find(x=>/download\\s*media/i.test(x.innerText||''));"
            + "const fail=s=>{window.__astraFlowDownloadAction='ERR|'+s};"
            + "const choose720=()=>{"
            + "const q=items().find(x=>/720p/i.test(x.innerText||'')&&/original size/i.test(x.innerText||''));"
            + "if(!q){fail('720P_ORIGINAL_SIZE_NOT_FOUND');return;}"
            + "q.click();window.__astraFlowDownloadAction='OK|720p Original size';"
            + "};"
            + "const openDownload=()=>{"
            + "const d=findDownload();if(!d){fail('DOWNLOAD_MEDIA_NOT_FOUND');return;}"
            + "d.click();setTimeout(choose720,350);"
            + "};"
            + "if(findDownload()){openDownload();}"
            + "else{"
            + "const b=[...document.querySelectorAll('button[aria-label=\\\"More options\\\"]')][0];"
            + "if(!b){fail('EDITOR_MORE_OPTIONS_NOT_FOUND');return 'FAILED';}"
            + "b.click();setTimeout(openDownload,350);"
            + "}"
            + "return 'STARTED';"
            + "})()";
        main.post(() -> {
            if (webView == null) {
                callback.onReceiveValue("\"ERR|FLOW_WEBVIEW_UNAVAILABLE\"");
                return;
            }
            webView.evaluateJavascript(script, value ->
                pollFlowDownloadAction(callback, System.currentTimeMillis() + 10000L));
        });
    }

    private void pollFlowDownloadAction(android.webkit.ValueCallback<String> callback,
            long deadline) {
        if (webView == null) {
            callback.onReceiveValue("\"ERR|FLOW_WEBVIEW_CLOSED\"");
            return;
        }
        webView.evaluateJavascript("window.__astraFlowDownloadAction||'RUNNING'", value -> {
            String result = jsString(value);
            if (result != null && !"RUNNING".equals(result)) {
                callback.onReceiveValue(value);
            } else if (System.currentTimeMillis() >= deadline) {
                callback.onReceiveValue("\"ERR|FLOW_DOWNLOAD_MENU_TIMEOUT\"");
            } else {
                main.postDelayed(() -> pollFlowDownloadAction(callback, deadline), 150L);
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
        long diagAge = lastDiagAt == 0L ? -1L
                : android.os.SystemClock.elapsedRealtime() - lastDiagAt;

        return "{\"ok\":true,\"ready\":"
                + (url != null && !url.isEmpty() && !"about:blank".equals(url))
                + ",\"url\":\"" + esc(url)
                + "\",\"title\":\"" + esc(title)
                + "\",\"lifecycle\":\"" + esc(lastLifecycleEvent)
                + "\",\"diagPollCount\":" + diagPollCount
                + ",\"lastDiagAgeMs\":" + diagAge
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
