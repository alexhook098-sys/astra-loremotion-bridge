package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.ContentValues;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Independent Google Flow browser profile reserved for image generation. */
public final class Flow2WebViewActivity extends Activity {
    public static final String FLOW2_PROJECT_URL = "https://flow.google.com/u/2/project/4daa2354-7afc-44e5-b51d-1652eb4aaaac";
    public static final String EXTRA_AUTOMATE = "astra_flow2_automate";
    public static final String EXTRA_PROMPT = "astra_flow2_prompt";
    private static final String TAG = "ASTRA-FLOW2";
    private static final long MAX_IMAGE_BYTES = 30L * 1024L * 1024L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final Set<String> seenImages = Collections.synchronizedSet(new HashSet<>());
    private WebView webView;
    private TextView status;
    private PowerManager.WakeLock wakeLock;
    private String pendingPrompt;
    private boolean promptEntered;
    private boolean pageLoaded;
    private int attempts;
    private long generationStartedAt;
    private int observerPolls;
    private String generationState = "idle";
    private boolean backgroundRequested;
    private final ImageBridge imageBridge = new ImageBridge();

    private final Runnable imageObserver = new Runnable() {
        @Override public void run() {
            if (webView == null || isFinishing() || isDestroyed() || generationStartedAt == 0L) return;
            webView.evaluateJavascript(imageScanScript(), value -> {
                observerPolls++;
                Log.i(TAG, "image observer poll=" + observerPolls + " result=" + value);
                if (webView != null && !isFinishing() && !isDestroyed()) {
                    main.postDelayed(this, 2200L);
                }
            });
        }
    };

    @Override protected void onCreate(Bundle state) {
        // This Activity runs in :flow2 (see AndroidManifest), so it owns an
        // independent Chromium data directory and Google account cookies.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("flow2");
        }
        super.onCreate(state);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            finish();
            return;
        }
        readIntent(getIntent());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ASTRA:GoogleFlow2Images");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setText("ASTRA · GOOGLE FLOW 2 · отдельный аккаунт · запуск");
        status.setPadding(24, 18, 24, 18);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        webView.addJavascriptInterface(imageBridge, "ASTRAFlow2Image");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
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
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                pageLoaded = false;
                setStatus("Открываю отдельный проект Google Flow 2…");
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageLoaded = true;
                setStatus("Google Flow 2 · проект открыт · подключаю наблюдатель изображений");
                main.postDelayed(() -> installImageObserver(true), 900L);
                if (pendingPrompt != null) main.postDelayed(Flow2WebViewActivity.this::attemptImageGeneration, 1200L);
                if (backgroundRequested) main.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        moveTaskToBack(true);
                        Log.i(TAG, "Google Flow 2 task moved behind ASTRA after page load");
                    }
                }, 120L);
                Log.i(TAG, "Page finished: " + url);
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        String url = getIntent() == null ? null : getIntent().getStringExtra("url");
        if (url == null || url.trim().isEmpty()) url = FLOW2_PROJECT_URL;
        webView.loadUrl(url);
    }

    private void readIntent(android.content.Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_AUTOMATE, false)) return;
        String prompt = intent.getStringExtra(EXTRA_PROMPT);
        if (prompt == null || prompt.trim().isEmpty()) return;
        pendingPrompt = prompt.trim();
        promptEntered = false;
        attempts = 0;
        generationStartedAt = 0L;
        generationState = "queued";
        backgroundRequested = intent.getBooleanExtra("background", true);
        seenImages.clear();
        Log.i(TAG, "Image generation queued; prompt length=" + pendingPrompt.length());
    }

    private void attemptImageGeneration() {
        if (webView == null || pendingPrompt == null || !pageLoaded || isFinishing() || isDestroyed()) return;
        if (++attempts > 55) {
            generationState = "automation_timeout";
            setStatus("Google Flow 2 · не удалось запустить генерацию · проверь страницу");
            Log.e(TAG, "Automation timeout; promptEntered=" + promptEntered);
            pendingPrompt = null;
            return;
        }
        String script;
        if (!promptEntered) {
            String quoted = org.json.JSONObject.quote(pendingPrompt);
            // Match the proven Google Flow 1 prompt control exactly.
            script = "(function(p){try{"
                    + "const e=Array.from(document.querySelectorAll('[role=textbox]')).find(x=>x.getAttribute('aria-label')==='Что вы хотите создать?')||document.querySelector('.ProseMirror[contenteditable=true]');"
                    + "if(!e)return 'PROMPT_FIELD_NOT_FOUND';e.focus();"
                    + "const setter=Object.getOwnPropertyDescriptor(HTMLElement.prototype,'innerText')?.set;"
                    + "if(setter)setter.call(e,p);else e.innerText=p;"
                    + "e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:p}));"
                    + "e.dispatchEvent(new Event('change',{bubbles:true}));return 'FIELD_SET';"
                    + "}catch(e){return 'ERROR:'+e.message;}})(" + quoted + ")";
        } else {
            // Match Flow 1's actual submit control; do not guess by generic button text.
            script = "(function(){try{"
                    + "const b=Array.from(document.querySelectorAll('button,[role=button]')).find(x=>x.getAttribute('aria-label')==='Начать генерацию');"
                    + "if(!b)return 'GENERATE_BUTTON_NOT_FOUND';"
                    + "if(b.disabled||b.getAttribute('aria-disabled')==='true')return 'GENERATE_BUTTON_DISABLED';"
                    + "window.__astraFlow2BaselineImages=new Set(Array.from(document.images||[]).map(i=>i.currentSrc||i.src).filter(Boolean));"
                    + "window.__astraFlow2BaselineElements=new WeakSet(Array.from(document.images||[]));"
                    + "window.__astraFlow2StartedAt=Date.now();b.click();return 'GENERATE_CLICKED';"
                    + "}catch(e){return 'ERROR:'+e.message;}})()";
        }
        webView.evaluateJavascript(script, result -> {
            String value = result == null ? "" : result;
            Log.i(TAG, "automation attempt=" + attempts + " result=" + value);
            if (value.contains("FIELD_SET")) {
                promptEntered = true;
                setStatus("Google Flow 2 · промпт введён · запускаю генерацию");
                main.postDelayed(this::attemptImageGeneration, 700L);
            } else if (value.contains("GENERATE_CLICKED")) {
                generationStartedAt = System.currentTimeMillis();
                generationState = "submitted";
                pendingPrompt = null;
                setStatus("Google Flow 2 · генерация запущена · возвращаюсь в чат, жду картину");
                installImageObserver(false);
                main.removeCallbacks(imageObserver);
                main.postDelayed(imageObserver, 1200L);
                // Keep this isolated Flow task alive in its own task while returning
                // the user to the ASTRA chat, as the working Flow 1 background path does.
                if (backgroundRequested) main.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) moveTaskToBack(true);
                }, 250L);
            } else {
                setStatus("Google Flow 2 · ожидаю поле и кнопку генерации · " + value);
                main.postDelayed(this::attemptImageGeneration, 1000L);
            }
        });
    }

    private void installImageObserver(boolean establishBaseline) {
        if (webView == null || isFinishing() || isDestroyed()) return;
        String js = "(function(){try{"
                + "if(!window.__astraFlow2Captured)window.__astraFlow2Captured=new Set();"
                + "if(!window.__astraFlow2ObservedRoots)window.__astraFlow2ObservedRoots=new WeakSet();"
                + "if(" + (establishBaseline ? "true" : "false") + "&&!window.__astraFlow2BaselineImages)"
                + "window.__astraFlow2BaselineImages=new Set(Array.from(document.images||[]).map(i=>i.currentSrc||i.src).filter(Boolean));"
                + "function sendData(u){if(!u||window.__astraFlow2Captured.has(u))return;window.__astraFlow2Captured.add(u);"
                + "fetch(u,{credentials:'include'}).then(r=>{if(!r.ok)throw new Error('image_http_'+r.status);return r.blob();})"
                + ".then(b=>{if(!/^image\\//i.test(b.type||''))throw new Error('not_image_'+b.type);"
                + "const fr=new FileReader();fr.onload=()=>{try{window.ASTRAFlow2Image.saveDataUri(String(fr.result));}catch(e){}};fr.readAsDataURL(b);})"
                + ".catch(e=>{window.__astraFlow2Captured.delete(u);console.warn('ASTRA_FLOW2_IMAGE_FETCH:'+e);"
                + "try{window.ASTRAFlow2Image.downloadImageUrl(u);}catch(x){}});}"
                + "function scanRoot(root){if(!root)return;try{const base=window.__astraFlow2BaselineImages||new Set();"
                + "const baselineElements=window.__astraFlow2BaselineElements;"
                + "Array.from(root.querySelectorAll('img')).forEach(i=>{const u=i.currentSrc||i.src||'';const r=i.getBoundingClientRect();"
                + "if(!u||(baselineElements&&baselineElements.has(i))||i.naturalWidth<256||i.naturalHeight<256||r.width<100||r.height<100)return;sendData(u);});"
                + "if(!window.__astraFlow2ObservedRoots.has(root)){window.__astraFlow2ObservedRoots.add(root);"
                + "new MutationObserver(()=>scanRoot(root)).observe(root,{subtree:true,childList:true,attributes:true,attributeFilter:['src','srcset','style']});}"
                + "Array.from(root.querySelectorAll('*')).forEach(n=>{if(n.shadowRoot)scanRoot(n.shadowRoot);});"
                + "Array.from(root.querySelectorAll('iframe')).forEach(f=>{try{if(f.contentDocument)scanRoot(f.contentDocument);}catch(e){}});"
                + "}catch(e){console.warn('ASTRA_FLOW2_SCAN_ROOT:'+e);}}"
                + "window.__astraFlow2Scan=function(){scanRoot(document);};window.__astraFlow2Scan();"
                + "if(!window.__astraFlow2Timer)window.__astraFlow2Timer=setInterval(window.__astraFlow2Scan,1800);"
                + "return 'image-observer-installed-shadow-aware';}catch(e){return 'observer-error:'+e;}})()";
        webView.evaluateJavascript(js, value -> Log.i(TAG, "image observer install=" + value));
    }

    private String imageScanScript() {
        return "(function(){try{window.__astraFlow2Scan&&window.__astraFlow2Scan();return 'scan';}catch(e){return String(e);}})()";
    }

    private void setStatus(String message) {
        if (status != null) status.setText("ASTRA · " + message);
        Log.i(TAG, message);
    }

    private final class ImageBridge {
        @JavascriptInterface public void saveDataUri(String dataUri) {
            if (dataUri == null || dataUri.length() > 45 * 1024 * 1024) return;
            downloads.execute(() -> {
                try {
                    int comma = dataUri.indexOf(',');
                    if (comma < 0) throw new IllegalArgumentException("invalid_data_uri");
                    String header = dataUri.substring(5, comma);
                    String mime = header.split(";")[0].toLowerCase(java.util.Locale.US);
                    if (!mime.startsWith("image/")) throw new IllegalArgumentException("not_image");
                    byte[] bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
                    saveImage(bytes, mime);
                } catch (Exception e) {
                    Log.e(TAG, "Could not save image data URI", e);
                }
            });
        }

        @JavascriptInterface public void downloadImageUrl(String imageUrl) {
            if (imageUrl == null || imageUrl.length() > 8192 || !imageUrl.startsWith("https://")) return;
            if (!seenImages.add(imageUrl)) return;
            downloads.execute(() -> downloadImage(imageUrl));
        }
    }

    private void downloadImage(String imageUrl) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(imageUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            connection.setRequestProperty("Referer", FLOW2_PROJECT_URL);
            String cookie = CookieManager.getInstance().getCookie(imageUrl);
            if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
            String mime = connection.getContentType();
            if (mime == null || !mime.toLowerCase(java.util.Locale.US).startsWith("image/"))
                throw new java.io.IOException("response is not an image: " + mime);
            long length = connection.getContentLengthLong();
            if (length > MAX_IMAGE_BYTES) throw new java.io.IOException("image exceeds 30 MB");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] chunk = new byte[16384];
                int n; long total = 0;
                while ((n = in.read(chunk)) != -1) {
                    total += n;
                    if (total > MAX_IMAGE_BYTES) throw new java.io.IOException("image exceeds 30 MB");
                    buffer.write(chunk, 0, n);
                }
            }
            saveImage(buffer.toByteArray(), mime.split(";")[0].trim());
        } catch (Exception e) {
            seenImages.remove(imageUrl);
            Log.e(TAG, "Image download failed: " + e.getMessage(), e);
            setStatus("не удалось скачать изображение · " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void saveImage(byte[] bytes, String mime) {
        Uri target = null;
        try {
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES)
                throw new java.io.IOException("invalid image size");
            String clean = mime == null ? "image/jpeg" : mime.split(";")[0].trim().toLowerCase(java.util.Locale.US);
            String ext = clean.contains("png") ? ".png" : clean.contains("webp") ? ".webp" : clean.contains("gif") ? ".gif" : ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "ASTRA_GOOGLE_FLOW2_" + System.currentTimeMillis() + ext);
            values.put(MediaStore.Images.Media.MIME_TYPE, clean);
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ASTRA/GoogleFlow2");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            target = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new java.io.IOException("MediaStore insert failed");
            try (OutputStream out = getContentResolver().openOutputStream(target)) {
                if (out == null) throw new java.io.IOException("output stream unavailable");
                out.write(bytes); out.flush();
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (getContentResolver().update(target, ready, null, null) <= 0)
                    throw new java.io.IOException("MediaStore publish failed");
            }
            Log.i(TAG, "Image saved: " + target + " bytes=" + bytes.length);
            ChatActivity.onGoogleFlow2ImageReady(getApplicationContext(), target);
            setStatus("картина сохранена в Pictures/ASTRA/GoogleFlow2 и отправлена в чат");
        } catch (Exception e) {
            if (target != null) try { getContentResolver().delete(target, null, null); } catch (Exception ignored) {}
            Log.e(TAG, "MediaStore image save failed", e);
            setStatus("ошибка сохранения картины · " + e.getMessage());
        }
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
        String url = intent == null ? null : intent.getStringExtra("url");
        if (webView != null && url != null && !url.trim().isEmpty() && !url.equals(webView.getUrl())) {
            pageLoaded = false;
            webView.loadUrl(url);
        } else if (pendingPrompt != null && pageLoaded) {
            main.postDelayed(this::attemptImageGeneration, 350L);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        // Do not pause Chromium timers in onPause: this Activity intentionally
        // keeps observing the generation after its task moves behind ASTRA.
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(imageObserver);
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("ASTRAFlow2Image");
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        downloads.shutdown();
        super.onDestroy();
    }
}
