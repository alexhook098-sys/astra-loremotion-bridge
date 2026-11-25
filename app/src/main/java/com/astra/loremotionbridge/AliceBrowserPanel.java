package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.MotionEvent;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Alice Media worker embedded in ChatActivity.workerHost, following the same
 * hidden-panel lifecycle used by FlowBrowserPanel and PerchanceBrowserPanel.
 * It never launches a provider Activity or Android file-picker UI.
 */
public final class AliceBrowserPanel extends LinearLayout {
    private static final String TAG = "ASTRA-ALICE-PANEL";
    private static final String HOME = "https://alice.yandex.ru/media";
    private final Activity host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final Set<String> seenIds = new HashSet<>();
    private WebView webView;
    private TextView status;
    private PowerManager.WakeLock wakeLock;
    private String imagePath;
    private String prompt;
    private String pendingUploadPath;
    private ValueCallback<Uri[]> pendingFileCallback;
    private boolean pageLoaded;
    private boolean taskStarted;
    private boolean uploadButtonTapped;
    private boolean fileDelivered;
    private boolean promptSubmitted;
    private boolean resultFound;
    private boolean disposed;
    private int readyPolls;
    private int uploadPolls;
    private int previewPolls;
    private int resultPolls;
    private String uploadError = "";
    private String baselineImages = "[]";

    public AliceBrowserPanel(Activity host, String imagePath, String prompt) {
        super(host);
        this.host = host;
        this.imagePath = imagePath;
        this.prompt = prompt == null ? "" : prompt.trim();
        setOrientation(VERTICAL);
        setBackgroundColor(android.graphics.Color.TRANSPARENT);

        PowerManager pm = (PowerManager) host.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            try {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ASTRA:AliceChatWorker");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(60L * 60L * 1000L);
            } catch (Exception e) { Log.w(TAG, "WakeLock unavailable", e); }
        }

        status = new TextView(host);
        status.setText("ALICE · встроенный worker запускается");
        addView(status, new LinearLayout.LayoutParams(1, 1));

        webView = new WebView(host);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFileCallback != null) {
                    try { pendingFileCallback.onReceiveValue(null); } catch (Exception ignored) {}
                }
                pendingFileCallback = callback;
                Log.i(TAG, "Alice requested HTML file input; returning ASTRA-owned file URI directly");
                deliverPendingUpload();
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (disposed || webView == null) return;
                pageLoaded = true;
                Log.i(TAG, "Page ready: " + url);
                scheduleReadinessCheck(0);
            }
        });
        addView(webView, new LinearLayout.LayoutParams(-1, -1));
        webView.loadUrl(HOME);
        setStatus("ALICE · открываю встроенную вкладку чата");
    }

    public void submitTask(String newImagePath, String newPrompt) {
        if (disposed || newImagePath == null || newPrompt == null
                || newPrompt.trim().isEmpty()) return;
        imagePath = newImagePath;
        prompt = newPrompt.trim();
        resetTaskState();
        if (webView == null) return;
        if (pageLoaded && isAliceUrl(webView.getUrl())) scheduleReadinessCheck(0);
        else webView.loadUrl(HOME);
    }

    private void resetTaskState() {
        taskStarted = false;
        uploadButtonTapped = false;
        fileDelivered = false;
        promptSubmitted = false;
        resultFound = false;
        readyPolls = uploadPolls = previewPolls = resultPolls = 0;
        uploadError = "";
        pendingUploadPath = null;
        seenIds.clear();
        if (pendingFileCallback != null) {
            try { pendingFileCallback.onReceiveValue(null); } catch (Exception ignored) {}
            pendingFileCallback = null;
        }
    }

    private boolean isAliceUrl(String value) {
        try {
            String h = Uri.parse(value == null ? "" : value).getHost();
            return h != null && (h.equalsIgnoreCase("alice.yandex.ru")
                    || h.toLowerCase(Locale.US).endsWith(".alice.yandex.ru"));
        } catch (Exception e) { return false; }
    }

    private void scheduleReadinessCheck(final int attempt) {
        if (disposed || taskStarted || webView == null) return;
        if (attempt > 120) {
            setStatus("ALICE · не дождалась готовности студии; задание не отправлено");
            return;
        }
        String js = "(function(){var es=[...document.querySelectorAll('button,[role=button],label')];"
                + "var e=es.find(x=>x.getClientRects().length&&!x.disabled&&/загрузить фото|добавить фото|выбрать фото/i.test((x.innerText||x.textContent||'').trim()));"
                + "return JSON.stringify({url:location.href,ready:document.readyState==='complete'||document.readyState==='interactive',upload:!!e});})()";
        webView.evaluateJavascript(js, raw -> {
            boolean ready = false;
            try {
                Object v = new org.json.JSONTokener(raw).nextValue();
                if (v instanceof String) {
                    JSONObject o = new JSONObject((String)v);
                    ready = isAliceUrl(o.optString("url")) && o.optBoolean("ready")
                            && o.optBoolean("upload");
                }
            } catch (Exception ignored) {}
            readyPolls = ready ? readyPolls + 1 : 0;
            if (readyPolls >= 3) {
                setStatus("ALICE · интерфейс стабилен, готовлю передачу фотографии");
                beginUpload();
            } else {
                main.postDelayed(() -> scheduleReadinessCheck(attempt + 1), 1200L);
            }
        });
    }

    private void beginUpload() {
        if (taskStarted || disposed || webView == null) return;
        File source = imagePath == null ? null : new File(imagePath);
        if (source == null || !source.isFile() || source.length() < 1
                || source.length() > 20L * 1024L * 1024L || prompt.isEmpty()) {
            setStatus("ALICE · неверный файл или пустой промпт; генерация отменена");
            return;
        }
        taskStarted = true;
        pendingUploadPath = source.getAbsolutePath();
        String js = "(function(){var es=[...document.querySelectorAll('button,[role=button],label')];"
                + "var e=es.find(x=>x.getClientRects().length&&!x.disabled&&/загрузить фото|добавить фото|выбрать фото/i.test((x.innerText||x.textContent||'').trim()));"
                + "if(!e)return JSON.stringify({ok:false,error:'upload_button_missing'});"
                + "window.__astraAliceBaselineImages=[...document.images].map(i=>i.currentSrc||i.src||'');"
                + "var r=e.getBoundingClientRect();return JSON.stringify({ok:true,x:r.left+r.width/2,y:r.top+r.height/2});})()";
        webView.evaluateJavascript(js, raw -> {
            try {
                Object v = new org.json.JSONTokener(raw).nextValue();
                JSONObject o = new JSONObject(String.valueOf(v));
                if (!o.optBoolean("ok")) {
                    taskStarted = false;
                    setStatus("ALICE · не найдена кнопка загрузки фото");
                    return;
                }
                float scale = webView.getScale();
                float x = (float)o.getDouble("x") * scale;
                float y = (float)o.getDouble("y") * scale;
                long t = android.os.SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, x, y, 0);
                webView.dispatchTouchEvent(down);
                webView.dispatchTouchEvent(up);
                down.recycle(); up.recycle();
                uploadButtonTapped = true;
                setStatus("ALICE · кнопка загрузки нажата; жду реальный file chooser callback");
                waitForFileDelivery(0);
            } catch (Exception e) {
                taskStarted = false;
                setStatus("ALICE · ошибка запуска загрузки: " + e.getMessage());
            }
        });
    }

    private void deliverPendingUpload() {
        if (pendingFileCallback == null) return;
        final ValueCallback<Uri[]> callback = pendingFileCallback;
        pendingFileCallback = null;
        final String sourcePath = pendingUploadPath;
        if (sourcePath == null) {
            uploadError = "upload_path_missing";
            callback.onReceiveValue(null);
            return;
        }
        try {
            File source = new File(sourcePath);
            if (!source.isFile() || source.length() < 1) throw new java.io.IOException("source_file_missing");
            File dir = new File(host.getCacheDir(), "alice_worker_uploads");
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("cache_dir_failed");
            String name = source.getName();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.US) : "jpg";
            if (!ext.matches("jpg|jpeg|png|webp|heic")) ext = "jpg";
            File target = new File(dir, "alice-" + System.currentTimeMillis() + "." + ext);
            try (FileInputStream in = new FileInputStream(source);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] b = new byte[16384]; int n; long total = 0;
                while ((n = in.read(b)) != -1) {
                    total += n;
                    if (total > 20L * 1024L * 1024L) throw new java.io.IOException("image_too_large");
                    out.write(b, 0, n);
                }
            }
            Uri uri = FileProvider.getUriForFile(host, host.getPackageName() + ".fileprovider", target);
            callback.onReceiveValue(new Uri[]{uri});
            fileDelivered = true;
            uploadError = "";
            Log.i(TAG, "File URI returned to WebView callback: " + target.getName());
            setStatus("ALICE · исходный файл передан через штатный WebView callback");
            waitForImagePreview(0);
        } catch (Exception e) {
            uploadError = e.getClass().getSimpleName() + ":" + String.valueOf(e.getMessage());
            callback.onReceiveValue(null);
            setStatus("ALICE · передача файла не удалась: " + uploadError);
        }
    }

    private void waitForFileDelivery(final int attempt) {
        if (disposed || webView == null || fileDelivered || !uploadError.isEmpty()) return;
        if (attempt >= 100) {
            setStatus("ALICE · страница не запросила файл через WebView; промпт не отправлен");
            return;
        }
        main.postDelayed(() -> waitForFileDelivery(attempt + 1), 300L);
    }

    private void waitForImagePreview(final int attempt) {
        if (disposed || webView == null || promptSubmitted) return;
        if (attempt >= 90) {
            setStatus("ALICE · callback завершён, но страница не подтвердила изображение; остановлено");
            return;
        }
        String js = "(function(){var n=[...document.querySelectorAll('input[type=file]')].reduce((a,e)=>a+((e.files&&e.files.length)||0),0);"
                + "var base=new Set(window.__astraAliceBaselineImages||[]);"
                + "var imgs=[...document.images].filter(i=>i.getClientRects().length&&i.complete&&i.naturalWidth>96&&i.naturalHeight>96&&!base.has(i.currentSrc||i.src||''));"
                + "var t=(document.body&&document.body.innerText||'').toLowerCase();"
                + "return JSON.stringify({files:n,preview:imgs.length,cue:/изменить фото|заменить фото|удалить фото|сбросить фото/.test(t)});})()";
        webView.evaluateJavascript(js, raw -> {
            boolean ready = false;
            try {
                Object v = new org.json.JSONTokener(raw).nextValue();
                if (v instanceof String) {
                    JSONObject o = new JSONObject((String)v);
                    ready = o.optInt("files") > 0 || o.optInt("preview") > 0 || o.optBoolean("cue");
                }
            } catch (Exception ignored) {}
            previewPolls = ready ? previewPolls + 1 : 0;
            if (previewPolls >= 2) {
                setStatus("ALICE · изображение подтверждено на странице; отправляю промпт");
                submitPrompt(0);
            } else {
                main.postDelayed(() -> waitForImagePreview(attempt + 1), 900L);
            }
        });
    }

    private void submitPrompt(final int attempt) {
        if (disposed || webView == null || promptSubmitted) return;
        if (attempt > 35) {
            setStatus("ALICE · не найдено поле промпта или кнопка отправки");
            return;
        }
        String script = "(function(){try{var fs=[...document.querySelectorAll('textarea,input:not([type=hidden]),[contenteditable=true])].filter(e=>e.getClientRects().length&&!e.disabled);"
                + "var f=fs.find(e=>/напиш|опиш|промпт|движен|message/i.test((e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')))||fs.find(e=>e.tagName==='TEXTAREA'||e.isContentEditable);"
                + "if(!f)return 'WAIT_FIELD';var v=" + JSONObject.quote(prompt) + ";f.focus();"
                + "if(f.isContentEditable)f.textContent=v;else{var p=f.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(p,'value').set.call(f,v);}"
                + "f.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:v}));f.dispatchEvent(new Event('change',{bubbles:true}));"
                + "var s=f.parentElement;for(var n=0;n<6&&s;n++,s=s.parentElement){var bs=[...s.querySelectorAll('button,[role=button]')].filter(b=>b.getClientRects().length&&!b.disabled);"
                + "var a=bs.filter(b=>{var t=((b.innerText||'')+' '+(b.getAttribute('aria-label')||'')+' '+(b.title||'')).toLowerCase();return !/оживить|что сделать|режим/.test(t)&&!!b.querySelector('svg')&&(t.trim()===''||/отправ|созда|send|submit|arrow|стрел/.test(t));});"
                + "if(a.length===1){var ids=new Set();function scan(u){var m=String(u||'').match(/(?:[?&]video=|\\/webalice\\/)([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase());}"
                + "[...document.querySelectorAll('video,video source,a[href]')].forEach(e=>scan(e.currentSrc||e.src||e.href||''));performance.getEntriesByType('resource').forEach(e=>scan(e.name));scan(location.href);window.__astraAliceBaselineIds=[...ids];a[0].click();return 'SENT';}}"
                + "return 'WAIT_ARROW';}catch(e){return 'ERR:'+String(e);}})()";
        webView.evaluateJavascript(script, raw -> {
            if (raw != null && raw.contains("SENT")) {
                promptSubmitted = true;
                BrowserBridgeService.setAliceTaskActive(true);
                setStatus("ALICE · запрос отправлен, наблюдатель работает внутри чата");
                pollResult(0);
            } else if (raw != null && (raw.contains("WAIT_FIELD") || raw.contains("WAIT_ARROW"))) {
                main.postDelayed(() -> submitPrompt(attempt + 1), 900L);
            } else {
                setStatus("ALICE · отправка не подтверждена: " + raw);
            }
        });
    }

    private void pollResult(final int attempt) {
        if (disposed || webView == null || resultFound) return;
        if (attempt > 360) {
            BrowserBridgeService.setAliceTaskActive(false);
            setStatus("ALICE · ожидание результата завершилось по тайм-ауту");
            return;
        }
        String js = "(function(){var u=[];document.querySelectorAll('video,video source,a[href]').forEach(e=>{var x=e.currentSrc||e.src||e.href||'';if(x)u.push(x);});"
                + "performance.getEntriesByType('resource').forEach(e=>{if(/webalice|yaart-images|video/i.test(e.name))u.push(e.name);});"
                + "var ids=new Set();u.forEach(x=>{var m=String(x).match(/(?:[?&]video=|\\/webalice\\/)([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase());});"
                + "var m=location.href.match(/[?&]video=([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase());"
                + "return JSON.stringify({ids:[...ids],base:window.__astraAliceBaselineIds||[]});})()";
        webView.evaluateJavascript(js, raw -> {
            String id = null;
            try {
                Object v = new org.json.JSONTokener(raw).nextValue();
                if (v instanceof String) {
                    JSONObject o = new JSONObject((String)v);
                    JSONArray ids = o.optJSONArray("ids"), base = o.optJSONArray("base");
                    Set<String> old = new HashSet<>();
                    if (base != null) for (int i=0;i<base.length();i++) old.add(base.optString(i).toLowerCase(Locale.US));
                    if (ids != null) for (int i=0;i<ids.length();i++) {
                        String x = ids.optString(i).toLowerCase(Locale.US);
                        if (x.matches("[0-9a-f]{32}") && !old.contains(x)) { id = x; break; }
                    }
                }
            } catch (Exception e) { Log.w(TAG, "Result scan parse failed", e); }
            if (id != null) {
                resultFound = true;
                final String videoId = id;
                setStatus("ALICE · результат найден, скачиваю MP4");
                downloads.execute(() -> {
                    try {
                        JSONObject result = new JSONObject(AliceMediaAgent.download(host.getApplicationContext(), videoId));
                        if (result.optBoolean("ok")) {
                            ChatActivity.onAliceVideoReady(host.getApplicationContext(), Uri.parse(result.getString("uri")));
                            BrowserBridgeService.setAliceTaskActive(false);
                            main.post(() -> setStatus("ALICE · MP4 доставлен в чат ASTRA"));
                        } else {
                            BrowserBridgeService.setAliceTaskActive(false);
                            main.post(() -> setStatus("ALICE · ошибка скачивания: " + result.optString("error", "unknown")));
                        }
                    } catch (Exception e) {
                        BrowserBridgeService.setAliceTaskActive(false);
                        main.post(() -> setStatus("ALICE · ошибка обработки MP4: " + e.getMessage()));
                    }
                });
                return;
            }
            main.postDelayed(() -> pollResult(attempt + 1), 5000L);
        });
    }

    private void setStatus(String message) {
        Log.i(TAG, message);
        if (status != null) status.setText(message);
    }

    public void onHostResume() {
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    public void onHostPause() {
        if (webView != null) webView.onPause();
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        main.removeCallbacksAndMessages(null);
        if (pendingFileCallback != null) {
            try { pendingFileCallback.onReceiveValue(null); } catch (Exception ignored) {}
            pendingFileCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        downloads.shutdown();
    }
}
