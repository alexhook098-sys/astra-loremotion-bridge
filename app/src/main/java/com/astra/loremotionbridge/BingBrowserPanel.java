package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bing Image Creator worker hosted inside ChatActivity's invisible workerHost.
 * It never starts WebViewBrowserActivity or another Android task.
 */
public final class BingBrowserPanel extends LinearLayout {
    private static final String TAG = "ASTRA-BING-WORKER";
    private static final String HOME = "https://www.bing.com/images/create";
    private static final long MAX_IMAGE_BYTES = 25L * 1024L * 1024L;
    private final Activity host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private WebView webView;
    private TextView status;
    private String pendingPrompt;
    private boolean pageLoaded;
    private boolean promptInjected;
    private boolean disposed;
    private int attempts;
    private int baselineImages;

    private final Runnable observer = new Runnable() {
        @Override public void run() {
            if (disposed || webView == null || host.isFinishing() || host.isDestroyed()) return;
            scanResults();
            main.postDelayed(this, 2500L);
        }
    };

    public BingBrowserPanel(Activity host, String prompt) {
        super(host);
        this.host = host;
        pendingPrompt = prompt == null ? null : prompt.trim();
        setOrientation(VERTICAL);
        setBackgroundColor(Color.rgb(8, 11, 24));
        status = new TextView(host);
        status.setTextColor(Color.WHITE);
        status.setText("BING · подключаю фоновый генератор");
        status.setPadding(8, 4, 8, 4);
        addView(status, new LayoutParams(-1, -2));

        webView = new WebView(host);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                status.setText("BING · страница загружена, проверяю интерфейс");
                baselineImages = -1;
                main.removeCallbacks(observer);
                main.post(observer);
                if (pendingPrompt != null) main.postDelayed(BingBrowserPanel.this::attemptPrompt, 1200L);
                Log.i(TAG, "Bing page loaded: " + url);
            }
        });
        addView(webView, new LayoutParams(-1, 0, 1f));
        webView.loadUrl(HOME);
    }

    public void submitPrompt(String prompt) {
        if (disposed || prompt == null || prompt.trim().isEmpty()) return;
        pendingPrompt = prompt.trim();
        promptInjected = false;
        attempts = 0;
        if (pageLoaded) main.postDelayed(this::attemptPrompt, 300L);
    }

    private void attemptPrompt() {
        if (disposed || webView == null || !pageLoaded || pendingPrompt == null
                || host.isFinishing() || host.isDestroyed()) return;
        if (++attempts > 50) {
            status.setText("BING · не удалось подтвердить поле или кнопку генерации");
            Log.w(TAG, "Automation timeout; url=" + webView.getUrl());
            pendingPrompt = null;
            return;
        }
        String script;
        if (!promptInjected) {
            script = "(function(p){try{const es=[...document.querySelectorAll('textarea,input,[contenteditable=true],[role=textbox]')];"
                    + "const e=es.find(x=>{const r=x.getBoundingClientRect(),s=getComputedStyle(x);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&!x.disabled"
                    + "&&(/prompt|describe|созда|изображ|image/i.test((x.getAttribute('placeholder')||'')+' '+(x.getAttribute('aria-label')||'')+' '+(x.id||''))||x.tagName==='TEXTAREA');});"
                    + "if(!e)return 'FIELD_NOT_FOUND';e.focus();if(e.isContentEditable)e.textContent=p;else{const proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;const d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(e,p);else e.value=p;}"
                    + "e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:p}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'FIELD_SET';"
                    + "}catch(x){return 'ERROR:'+x.message;}})(" + org.json.JSONObject.quote(pendingPrompt) + ")";
        } else {
            script = "(function(){const bs=[...document.querySelectorAll('button,[role=button],input[type=submit]')];"
                    + "const b=bs.find(x=>{const t=((x.innerText||x.textContent||'')+' '+(x.getAttribute('aria-label')||'')+' '+(x.id||'')).replace(/\\s+/g,' ').trim();"
                    + "return /^(create|создать|generate|создать изображения)$/i.test(t)||/create_btn/i.test(x.id||'');});"
                    + "if(!b)return 'BUTTON_NOT_FOUND';if(b.disabled||b.getAttribute('aria-disabled')==='true')return 'BUTTON_DISABLED';b.click();return 'GENERATE_CLICKED';})()";
        }
        webView.evaluateJavascript(script, result -> {
            String value = decode(result);
            Log.i(TAG, "automation attempt=" + attempts + " result=" + value);
            if (value.contains("FIELD_SET")) {
                promptInjected = true;
                status.setText("BING · запрос введён, запускаю генерацию");
                main.postDelayed(this::attemptPrompt, 650L);
            } else if (value.contains("GENERATE_CLICKED")) {
                pendingPrompt = null;
                baselineImages = -1;
                status.setText("BING · генерация отправлена, ожидаю результат");
                main.postDelayed(observer, 1500L);
            } else {
                status.setText("BING · ожидаю интерфейс генератора");
                main.postDelayed(this::attemptPrompt, 1000L);
            }
        });
    }

    private String decode(String raw) {
        if (raw == null) return "";
        try {
            Object value = new org.json.JSONTokener(raw).nextValue();
            return value instanceof String ? (String) value : String.valueOf(value);
        } catch (Exception e) { return raw; }
    }

    private void scanResults() {
        if (webView == null) return;
        String js = "(function(){const out=[];for(const i of [...document.images]){const u=i.currentSrc||i.src||'';const r=i.getBoundingClientRect();"
                + "if(r.width<180||r.height<180||!i.complete||i.naturalWidth<180||i.naturalHeight<180)continue;"
                + "if(!/^https:\\/\\/th\\.bing\\.com\\/th\\/id\\//i.test(u))continue;"
                + "if(!/(OIG|image)/i.test(u))continue;out.push(u);}return JSON.stringify([...new Set(out)]);})()";
        webView.evaluateJavascript(js, raw -> {
            try {
                Object parsed = new org.json.JSONTokener(raw == null ? "[]" : raw).nextValue();
                org.json.JSONArray array = new org.json.JSONArray(parsed instanceof String ? (String) parsed : "[]");
                if (array.length() == 0) return;
                status.setText("BING · обнаружены изображения: " + array.length() + " · сохраняю");
                for (int i = 0; i < array.length(); i++) {
                    String url = array.optString(i, "");
                    if (!url.isEmpty() && seen.add(url)) downloads.execute(() -> saveImage(url));
                }
            } catch (Exception e) { Log.w(TAG, "Result scan parse failed", e); }
        });
    }

    private void saveImage(String imageUrl) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(imageUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", webView == null ? "Mozilla/5.0" : webView.getSettings().getUserAgentString());
            connection.setRequestProperty("Referer", HOME);
            String cookie = CookieManager.getInstance().getCookie(imageUrl);
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
            String mime = connection.getContentType();
            if (mime != null) mime = mime.split(";")[0].trim().toLowerCase(java.util.Locale.US);
            if (mime == null || !mime.startsWith("image/")) mime = "image/jpeg";
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16384]; int n; long total = 0;
                while ((n = in.read(buffer)) != -1) {
                    total += n; if (total > MAX_IMAGE_BYTES) throw new java.io.IOException("image too large");
                    bytes.write(buffer, 0, n);
                }
            }
            if (bytes.size() == 0) throw new java.io.IOException("empty image");
            store(bytes.toByteArray(), mime);
        } catch (Exception e) {
            seen.remove(imageUrl);
            Log.w(TAG, "Bing image save failed: " + e.getMessage(), e);
            main.post(() -> status.setText("BING · ошибка сохранения результата: " + e.getClass().getSimpleName()));
        } finally { if (connection != null) connection.disconnect(); }
    }

    private void store(byte[] data, String mime) throws Exception {
        String ext = mime.contains("png") ? ".png" : mime.contains("webp") ? ".webp" : ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "ASTRA_BING_" + System.currentTimeMillis() + ext);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ASTRA/Bing");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri target = host.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (target == null) throw new java.io.IOException("MediaStore insert failed");
        try (OutputStream out = host.getContentResolver().openOutputStream(target)) {
            if (out == null) throw new java.io.IOException("MediaStore stream unavailable");
            out.write(data); out.flush();
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues ready = new ContentValues(); ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            host.getContentResolver().update(target, ready, null, null);
        }
        Log.i(TAG, "Bing image saved: " + target + " bytes=" + data.length);
        ChatActivity.onPerchanceImageReady(host, target);
        main.post(() -> status.setText("BING · изображение сохранено в Pictures/ASTRA/Bing"));
    }

    public void onHostResume() { if (webView != null) { webView.onResume(); webView.resumeTimers(); } }
    public void onHostPause() { if (webView != null) webView.onPause(); }
    public void dispose() {
        disposed = true; main.removeCallbacks(observer);
        if (webView != null) { webView.stopLoading(); webView.destroy(); webView = null; }
        downloads.shutdown();
    }
}
