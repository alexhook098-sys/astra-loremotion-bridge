package com.astra.loremotionbridge;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Alice is embedded in ChatActivity like the existing Flow and Perchance worker panels. Hiding
 * the panel only changes its visibility; its WebView remains attached and alive.
 */
public final class AliceBrowserPanel extends LinearLayout {
    // Persistent in-chat browser: minimized means GONE, never detached or destroyed.
    private static final String TAG = "ASTRA-ALICE";
    private static final String HOME = "https://alice.yandex.ru/media";
    public static final int FILE_CHOOSER_REQUEST = 4817;
    private static final long MAX_UPLOAD_BYTES = 20L * 1024L * 1024L;

    public interface ProgressListener { void onProgress(String message); }
    private final Activity host;
    private final ProgressListener progressListener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> baselineIds = new HashSet<>();
    private final Set<String> downloadedIds = new HashSet<>();
    private WebView webView;
    private TextView status;
    private ValueCallback<Uri[]> pendingFileCallback;
    private boolean pageLoaded;
    private boolean monitorStarted;
    private boolean destroyed;
    private Uri queuedAutomationImage;
    private String queuedAutomationPrompt;

    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (destroyed || webView == null || host.isFinishing() || host.isDestroyed()) return;
            final WebView target = webView;
            String js = "(function(){var u=[];document.querySelectorAll('video,video source,a[href]').forEach(e=>{var x=e.currentSrc||e.src||e.href||'';if(x)u.push(x)});"
                    + "performance.getEntriesByType('resource').forEach(e=>{if(/webalice|yaart-images|video/i.test(e.name))u.push(e.name)});"
                    + "var ids=new Set();u.forEach(x=>{var m=String(x).match(/(?:[?&]video=|\\/webalice\\/)([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase())});"
                    + "var m=location.href.match(/[?&]video=([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase());"
                    + "return JSON.stringify({ids:[...ids],visibility:document.visibilityState,ready:document.readyState,url:location.href})})()";
            try {
                target.evaluateJavascript(js, raw -> {
                    if (destroyed) return;
                    try {
                        Object value = new org.json.JSONTokener(raw).nextValue();
                        JSONObject evidence = value instanceof String
                                ? new JSONObject((String) value) : new JSONObject();
                        JSONArray ids = evidence.optJSONArray("ids");
                        if (ids != null) for (int i = 0; i < ids.length(); i++) {
                            String id = ids.optString(i).toLowerCase(Locale.US);
                            if (id.matches("[0-9a-f]{32}") && !baselineIds.contains(id)
                                    && downloadedIds.add(id)) {
                                Log.i(TAG, "New video ID detected by background observer: " + id);
                                io.execute(() -> downloadAndDeliver(id));
                            }
                        }
                        Log.i(TAG, "observer page=" + evidence.optString("ready")
                                + " visibility=" + evidence.optString("visibility")
                                + " ids=" + (ids == null ? 0 : ids.length())
                                + " baseline=" + baselineIds.size());
                    } catch (Exception e) {
                        Log.w(TAG, "Alice observer parse failed: " + raw, e);
                    }
                    if (!destroyed && webView != null && !host.isDestroyed()) main.postDelayed(this, 5000L);
                });
            } catch (Exception e) {
                Log.w(TAG, "Alice observer evaluation failed", e);
                if (!destroyed && webView != null) main.postDelayed(this, 5000L);
            }
        }
    };

    public AliceBrowserPanel(Activity host) { this(host, null); }

    public AliceBrowserPanel(Activity host, ProgressListener listener) {
        super(host);
        this.host = host;
        this.progressListener = listener;
        destroyed = false;
        setOrientation(LinearLayout.VERTICAL);
        setBackgroundColor(android.graphics.Color.rgb(8, 11, 24));

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));
        TextView title = new TextView(host);
        title.setText("✦  ASTRA · ALICE MEDIA");
        title.setTextColor(android.graphics.Color.rgb(229, 199, 255));
        title.setTextSize(18);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView hint = new TextView(host);
        hint.setText("Вернуться в чат можно кнопкой «Назад». Страница и генерация сохраняются.");
        hint.setTextColor(Color.rgb(145, 160, 188));
        hint.setTextSize(11);
        root.addView(hint, new LinearLayout.LayoutParams(-1, dp(34)));

        status = new TextView(host);
        status.setText("Alice: подключаю страницу…");
        status.setTextColor(android.graphics.Color.rgb(145, 210, 220));
        status.setTextSize(11);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(24)));

        webView = new WebView(host);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            Log.i(TAG, "Renderer priority IMPORTANT; waivedWhenNotVisible=false");
        }
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (pendingFileCallback != null) {
                    try { pendingFileCallback.onReceiveValue(null); } catch (Exception ignored) {}
                }
                if (queuedAutomationImage != null) {
                    Uri image = queuedAutomationImage;
                    queuedAutomationImage = null;
                    callback.onReceiveValue(new Uri[]{image});
                    Log.i(TAG, "Automation image supplied directly to Alice file chooser");
                    progress("Изображение передано; проверяю миниатюру…");
                    main.postDelayed(() -> verifyImageAndContinue(), 1800L);
                    return true;
                }
                pendingFileCallback = callback;
                android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                pick.setType("image/*");
                pick.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                try {
                    host.startActivityForResult(android.content.Intent.createChooser(pick, "Выбрать фото для Alice"),
                            FILE_CHOOSER_REQUEST);
                    Log.i(TAG, "Alice HTML file chooser opened");
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "Could not open image picker", e);
                    pendingFileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                Log.i(TAG, "Navigation started: " + safeUrl(url));
                super.onPageStarted(view, url, favicon);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    Log.e(TAG, "Main-frame error url=" + safeUrl(String.valueOf(request.getUrl()))
                            + " code=" + (error == null ? -1 : error.getErrorCode())
                            + " description=" + (error == null ? "unknown" : error.getDescription()));
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                super.onReceivedHttpError(view, request, response);
                if (request != null && request.isForMainFrame()) {
                    Log.e(TAG, "Main-frame HTTP error url=" + safeUrl(String.valueOf(request.getUrl()))
                            + " status=" + (response == null ? -1 : response.getStatusCode()));
                }
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                pageLoaded = true;
                if (status != null) status.setText("Alice: страница загружена · наблюдатель активен");
                Log.i(TAG, "Page finished: " + safeUrl(url));
                if (!monitorStarted) captureBaselineAndStartMonitor(view);
                if (queuedAutomationImage != null) main.postDelayed(() -> clickUploadControl(), 700L);
            }
        });

        Button back = new Button(host);
        back.setText("‹ ASTRA · В ЧАТ");
        back.setOnClickListener(v -> minimizeAlice());
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(44)));
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        addView(root, new LinearLayout.LayoutParams(-1, -1));
        webView.loadUrl(HOME);
    }

    private void captureBaselineAndStartMonitor(WebView view) {
        String js = "(function(){var u=[];document.querySelectorAll('video,video source,a[href]').forEach(e=>{var x=e.currentSrc||e.src||e.href||'';if(x)u.push(x)});"
                + "performance.getEntriesByType('resource').forEach(e=>{if(/webalice|yaart-images|video/i.test(e.name))u.push(e.name)});"
                + "var ids=new Set();u.forEach(x=>{var p=String(x).split('webalice/')[1]||'';var m=p.match(/^([0-9a-f]{32})/i)||String(x).match(/[?&]video=([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase())});"
                + "var m=location.href.match(/[?&]video=([0-9a-f]{32})/i);if(m)ids.add(m[1].toLowerCase());return JSON.stringify([...ids])})()";
        view.evaluateJavascript(js, raw -> {
            try {
                Object parsed = new org.json.JSONTokener(raw).nextValue();
                JSONArray ids = parsed instanceof String ? new JSONArray((String) parsed) : new JSONArray();
                for (int i = 0; i < ids.length(); i++) baselineIds.add(ids.optString(i).toLowerCase(Locale.US));
            } catch (Exception e) { Log.w(TAG, "Alice baseline scan failed", e); }
            monitorStarted = true;
            main.removeCallbacks(monitor);
            main.post(monitor);
            Log.i(TAG, "Baseline captured; Flow-style background observer started");
        });
    }

    public void submitImageAndPrompt(Uri image, String prompt) {
        if (destroyed || image == null || prompt == null || prompt.trim().isEmpty()) {
            progress("Не удалось начать: нет изображения или промпта");
            return;
        }
        queuedAutomationImage = image;
        queuedAutomationPrompt = prompt.trim();
        progress("Подготавливаю загрузку изображения…");
        if (!pageLoaded || webView == null) {
            progress("Жду загрузки Alice…");
            return;
        }
        clickUploadControl();
    }

    private void clickUploadControl() {
        if (destroyed || webView == null || queuedAutomationImage == null) return;
        progress("Нажимаю «Загрузить фото»…");
        String js = "(function(){var vis=e=>!!(e&&e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden');"
                + "var b=[...document.querySelectorAll('button,[role=button]')].find(e=>vis(e)&&/загрузить\\s+фото/i.test((e.innerText||e.getAttribute('aria-label')||'').trim()));"
                + "if(b){b.click();return 'upload-button-clicked'}"
                + "var f=[...document.querySelectorAll('input[type=file]')].find(vis);"
                + "if(f){f.click();return 'file-input-clicked'}return 'upload-control-not-found'})()";
        webView.evaluateJavascript(js, raw -> {
            Log.i(TAG, "Alice upload control: " + raw);
            if (raw != null && raw.contains("not-found")) progress("Не нашла кнопку загрузки фото — остановилась");
            else progress("Кнопка загрузки нажата; передаю картину…");
        });
    }

    private void verifyImageAndContinue() {
        if (destroyed || webView == null || queuedAutomationPrompt == null) return;
        progress("Проверяю, приняла ли Alice изображение…");
        String js = "(function(){var vis=e=>!!(e&&e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden');"
                + "var fields=[...document.querySelectorAll('textarea,input:not([type=file]),[contenteditable=true],[role=textbox]')].filter(vis);"
                + "var field=fields.find(e=>/например|что сделать|описание/i.test((e.getAttribute('placeholder')||e.getAttribute('aria-label')||'')))||fields.find(e=>e.matches('[contenteditable=true],[role=textbox]'))||fields.find(e=>e.tagName==='TEXTAREA')||fields[0];"
                + "var files=[...document.querySelectorAll('input[type=file]')].reduce((n,e)=>n+(e.files?e.files.length:0),0);"
                + "var preview=false;for(var p=field,i=0;p&&i<9;p=p.parentElement,i++){if(p.querySelectorAll('img').length>0){preview=true;break;}}"
                + "return JSON.stringify({files:files,preview:preview});})()";
        webView.evaluateJavascript(js, raw -> {
            try {
                Object parsed = new org.json.JSONTokener(raw).nextValue();
                JSONObject state = parsed instanceof String ? new JSONObject((String)parsed) : new JSONObject();
                if (state.optInt("files",0)>0 || state.optBoolean("preview")) {
                    progress("Картина принята. Ввожу промпт…");
                    enterPromptAndStart();
                } else {
                    progress("Alice не подтвердила картину. Остановилась до запуска.");
                    Log.w(TAG, "Upload not verified: " + raw);
                }
            } catch (Exception e) {
                progress("Не удалось проверить загрузку; генерацию не запускаю");
                Log.w(TAG, "Upload verification failed: " + raw, e);
            }
        });
    }

    private void enterPromptAndStart() {
        if (destroyed || webView == null || queuedAutomationPrompt == null) return;
        final String prompt = queuedAutomationPrompt;
        final String quoted = JSONObject.quote(prompt);
        progress("Ищу поле промпта…");
        String js = "(function(){"
                + "var vis=e=>!!(e&&e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden');"
                + "var fields=[...document.querySelectorAll('textarea,input:not([type=file]),[contenteditable=true],[role=textbox]')].filter(vis);"
                + "var field=fields.find(e=>/например|что сделать|описание/i.test((e.getAttribute('placeholder')||e.getAttribute('aria-label')||'')))"
                + "||fields.find(e=>e.matches('[contenteditable=true],[role=textbox]'))||fields.find(e=>e.tagName==='TEXTAREA')||fields[0];"
                + "if(!field)return JSON.stringify({ok:false,stage:'prompt-field-not-found',fields:fields.length});"
                + "field.focus();"
                + "if(field.isContentEditable||field.getAttribute('role')==='textbox'){field.textContent=" + quoted + ";}"
                + "else{var proto=field.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "var setter=Object.getOwnPropertyDescriptor(proto,'value');if(setter&&setter.set)setter.set.call(field," + quoted + ");else field.value=" + quoted + ";}"
                + "field.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:" + quoted + "}));"
                + "field.dispatchEvent(new Event('change',{bubbles:true}));"
                + "var root=field;for(var i=0;i<8&&root;i++,root=root.parentElement){"
                + "var bs=[...root.querySelectorAll('button,[role=button]')].filter(e=>vis(e)&&!e.disabled);"
                + "var rect=e=>e.getBoundingClientRect();"
                + "var arrows=bs.filter(e=>e.querySelector('svg')&&!/что сделать/i.test(e.innerText||'')).sort((a,b)=>{var x=rect(a),y=rect(b);return (y.bottom-x.bottom)||(y.right-x.right)});"
                + "if(arrows.length){var target=arrows[0];target.click();return JSON.stringify({ok:true,stage:'prompt-entered-and-composer-arrow-clicked',button:target.outerHTML.slice(0,500)});}"
                + "}"
                + "return JSON.stringify({ok:false,stage:'prompt-entered-but-launch-button-not-found'});"
                + "})()";
        webView.evaluateJavascript(js, raw -> {
            Log.i(TAG, "Alice automation result: " + raw);
            if (raw != null && raw.contains("composer-arrow-clicked")) progress("Нажала стрелку отправки. Ожидаю генерацию…");
            else progress("Не удалось подтвердить нажатие стрелки: " + raw);
            queuedAutomationPrompt = null;
        });
    }

    public void handleActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode != FILE_CHOOSER_REQUEST) return;
        final ValueCallback<Uri[]> callback = pendingFileCallback;
        pendingFileCallback = null;
        if (callback == null) return;
        Uri selected = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (selected == null) {
            callback.onReceiveValue(null);
            Log.i(TAG, "Alice image selection cancelled");
            return;
        }
        io.execute(() -> {
            Uri shareUri = null;
            try {
                File dir = new File(host.getCacheDir(), "uploads");
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("cache_dir_failed");
                String mime = host.getContentResolver().getType(selected);
                String ext = mime != null && mime.toLowerCase(Locale.US).contains("png") ? ".png"
                        : mime != null && mime.toLowerCase(Locale.US).contains("webp") ? ".webp" : ".jpg";
                File copy = new File(dir, "alice-upload-" + System.currentTimeMillis() + ext);
                long total = 0L;
                try (InputStream in = host.getContentResolver().openInputStream(selected);
                     FileOutputStream out = new FileOutputStream(copy)) {
                    if (in == null) throw new java.io.IOException("source_stream_unavailable");
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_UPLOAD_BYTES) throw new java.io.IOException("image_too_large");
                        out.write(buffer, 0, count);
                    }
                    out.flush();
                }
                shareUri = FileProvider.getUriForFile(host, host.getPackageName() + ".fileprovider", copy);
                Log.i(TAG, "Selected image copied to app cache: bytes=" + total + " mime=" + mime);
            } catch (Exception e) {
                Log.e(TAG, "Alice image copy failed", e);
            }
            final Uri result = shareUri;
            main.post(() -> {
                if (destroyed) { callback.onReceiveValue(null); return; }
                if (result != null) callback.onReceiveValue(new Uri[]{result});
                else callback.onReceiveValue(null);
            });
        });
    }

    private void downloadAndDeliver(String id) {
        try {
            JSONObject result = new JSONObject(AliceMediaAgent.download(MainApplication.context(), id));
            if (result.optBoolean("ok")) {
                Uri video = Uri.parse(result.getString("uri"));
                ChatActivity.onAliceVideoReady(MainApplication.context(), video);
                Log.i(TAG, "MP4 downloaded and delivered: " + id);
            } else {
                downloadedIds.remove(id);
                Log.w(TAG, "MP4 download failed id=" + id + " error=" + result.optString("error"));
            }
        } catch (Exception e) {
            downloadedIds.remove(id);
            Log.e(TAG, "MP4 download/delivery failed", e);
        }
    }

    private static String safeUrl(String raw) {
        if (raw == null) return "";
        try {
            Uri u = Uri.parse(raw);
            return u.getScheme() + "://" + u.getHost() + (u.getPath() == null ? "" : u.getPath());
        } catch (Exception ignored) { return "[invalid_url]"; }
    }

    private int dp(int value) {
        return (int) (value * host.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void progress(String message) {
        Log.i(TAG, "STAGE: " + message);
        if (status != null) status.setText("Alice: " + message);
        if (progressListener != null) main.post(() -> progressListener.onProgress(message));
    }

    public void minimizeAlice() {
        setVisibility(View.GONE);
        Log.i(TAG, "Alice panel hidden in ChatActivity; WebView instance retained");
    }

    public void onHostPause() {
        if (webView != null) webView.onPause();
    }

    public void onHostResume() {
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    public void dispose() {
        destroyed = true;
        main.removeCallbacks(monitor);
        if (pendingFileCallback != null) {
            try { pendingFileCallback.onReceiveValue(null); } catch (Exception ignored) {}
            pendingFileCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        io.shutdown();
    }

}
