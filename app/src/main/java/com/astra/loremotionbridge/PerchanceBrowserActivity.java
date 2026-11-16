package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.ContentValues;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class PerchanceBrowserActivity extends Activity {
    private static final String TAG = "ASTRA-PERCHANCE";
    private static final String HOME = "https://perchance.org/aigen-rus-edit";
    private static final String BRIDGE_OBJECT = "ASTRA_PERCHANCE_BRIDGE";
    private static final long MAX_IMAGE_BYTES = 20L * 1024L * 1024L;
    public static final String EXTRA_AUTOMATE = "astra_perchance_automate";
    public static final String EXTRA_PROMPT = "astra_perchance_prompt";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final AtomicInteger detectedCount = new AtomicInteger();
    private final AtomicInteger savedCount = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();

    private WebView webView;
    private TextView status;
    private boolean observing;
    private boolean documentStartCaptureInstalled;
    private boolean pageLoaded;
    private boolean generatorFrameRedirected;
    private boolean promptInjected;
    private int automationAttempts;
    private String pendingPrompt;

    private final Runnable observer = new Runnable() {
        @Override public void run() {
            if (webView == null || isFinishing()) {
                observing = false;
                return;
            }
            if (isPerchance(webView.getUrl())) scanImages();
            else {
                observing = false;
                return;
            }
            main.postDelayed(this, 2000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        readAutomationIntent(getIntent());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 11, 24));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(10, 4, 10, 4);

        Button back = new Button(this);
        back.setText("‹ ASTRA");
        back.setTextColor(Color.WHITE);
        back.setOnClickListener(v -> moveTaskToBack(true));
        bar.addView(back, new LinearLayout.LayoutParams(-2, -2));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setText("PERCHANCE · подключаю сборщик изображений");
        status.setPadding(12, 0, 4, 0);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
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

        installFrameCaptureBridge();

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null && message.message() != null) {
                    handleConsoleMessage(message.message());
                }
                return true;
            }
        });

        // Handle Perchance's own arrow and "save to your device" actions.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url == null || url.isEmpty()) {
                setStatus("PERCHANCE · сайт не передал адрес файла");
                return;
            }
            if (url.startsWith("data:image/")) {
                acceptImage(url, "manual-save/data");
            } else if (url.startsWith("blob:")) {
                setStatus("PERCHANCE · получаю изображение из WebView");
                captureBlobInPage(url);
            } else if (url.startsWith("https://") || url.startsWith("http://")) {
                acceptImage(url, "manual-save/url");
            } else {
                setStatus("PERCHANCE · неизвестный тип загрузки: " + Uri.parse(url).getScheme());
                Log.w(TAG, "Unsupported download URL: " + url);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                // The public Perchance page is only a wrapper: the actual generator UI
                // lives in a cross-origin iframe. Navigate WebView to that iframe URL
                // so automation can access its DOM without violating same-origin rules.
                if (!generatorFrameRedirected && url != null
                        && "perchance.org".equalsIgnoreCase(Uri.parse(url).getHost())) {
                    view.evaluateJavascript(
                            "(function(){var f=document.querySelector('iframe');return f&&f.src?f.src:'';})()",
                            result -> {
                                String frameUrl = null;
                                try {
                                    if (result != null && result.length() > 2) {
                                        frameUrl = new JSONObject("{\"url\":" + result + "}").optString("url", "");
                                    }
                                } catch (Exception ignored) { }
                                String host = frameUrl == null ? null : Uri.parse(frameUrl).getHost();
                                if (host != null && host.endsWith(".perchance.org")
                                        && !host.equalsIgnoreCase("perchance.org")) {
                                    generatorFrameRedirected = true;
                                    pageLoaded = false;
                                    setStatus("PERCHANCE · подключаю интерфейс генератора…");
                                    view.loadUrl(frameUrl);
                                } else {
                                    finishPageLoad(view);
                                }
                            });
                    return;
                }
                finishPageLoad(view);
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        webView.loadUrl(HOME);
        // Keep this dedicated Perchance task alive, but immediately return to ASTRA.
        if (pendingPrompt != null) main.postDelayed(() -> moveTaskToBack(true), 120L);
    }

    private void finishPageLoad(WebView view) {
        if (!documentStartCaptureInstalled) {
            view.evaluateJavascript(buildCaptureScript(), null);
        }
        pageLoaded = true;
        setStatus("PERCHANCE · сборщик активен · " + counters());
        startObserver();
        if (pendingPrompt != null) main.postDelayed(this::attemptPromptInjection, 900L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readAutomationIntent(intent);
        if (pendingPrompt != null) {
            moveTaskToBack(true);
            if (pageLoaded) main.postDelayed(this::attemptPromptInjection, 500L);
        }
    }

    private void readAutomationIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_AUTOMATE, false)) return;
        String prompt = intent.getStringExtra(EXTRA_PROMPT);
        if (prompt == null || prompt.trim().isEmpty()) return;
        pendingPrompt = prompt.trim();
        promptInjected = false;
        automationAttempts = 0;
        Log.i(TAG, "Background generation queued; prompt length=" + pendingPrompt.length());
    }

    private void attemptPromptInjection() {
        if (webView == null || pendingPrompt == null || !pageLoaded || isFinishing()) return;
        if (++automationAttempts > 45) {
            setStatus("PERCHANCE · не удалось найти поле промпта или кнопку генерации");
            Log.w(TAG, "Background automation timed out");
            pendingPrompt = null;
            return;
        }
        if (!promptInjected) {
            webView.evaluateJavascript(buildPromptScript(pendingPrompt), result -> {
                if (result != null && result.contains("FIELD_SET")) {
                    promptInjected = true;
                    setStatus("PERCHANCE · промпт введён в фоновой вкладке");
                    main.postDelayed(this::attemptPromptInjection, 650L);
                } else {
                    main.postDelayed(this::attemptPromptInjection, 900L);
                }
            });
        } else {
            webView.evaluateJavascript(buildGenerateClickScript(), result -> {
                if (result != null && result.contains("CLICKED")) {
                    setStatus("PERCHANCE · генерация запущена в фоне · " + counters());
                    Log.i(TAG, "Background Generate button clicked");
                    pendingPrompt = null;
                } else {
                    main.postDelayed(this::attemptPromptInjection, 900L);
                }
            });
        }
    }

    private String buildPromptScript(String prompt) {
        // Perchance UI can place its controls in nested same-origin frames or shadow roots.
        // Search all reachable DOM roots instead of assuming the prompt is in the top document.
        return "(function(p){try{"
                + "const roots=[];function collect(root){if(!root||roots.indexOf(root)>=0)return;roots.push(root);"
                + "try{Array.from(root.querySelectorAll('*')).forEach(n=>{if(n.shadowRoot)collect(n.shadowRoot);"
                + "if(n.tagName==='IFRAME'){try{if(n.contentDocument)collect(n.contentDocument);}catch(x){}}});}catch(x){}}"
                + "collect(document);"
                + "const visible=e=>{try{const r=e.getBoundingClientRect(),s=getComputedStyle(e);"
                + "return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'&&!e.disabled&&!e.readOnly;}catch(x){return false;}};"
                + "const all=[];const selectors='textarea,input:not([type=hidden]):not([type=button]):not([type=submit]),[contenteditable=true],[role=textbox],[placeholder],[data-placeholder]';"
                + "roots.forEach(r=>{try{Array.from(r.querySelectorAll(selectors)).forEach(e=>{if(visible(e)&&all.indexOf(e)<0)all.push(e);});}catch(x){}});"
                + "const score=e=>{const h=((e.getAttribute('placeholder')||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('data-placeholder')||'')+' '+(e.getAttribute('name')||'')).toLowerCase();"
                + "return (/введите ваш промпт|enter your prompt|type your prompt/.test(h)?1000:0)"
                + "+(/prompt|описан|изображ|image/.test(h)?300:0)+(e.tagName==='TEXTAREA'?200:0)"
                + "+(e.isContentEditable?100:0)+(e.getAttribute('role')==='textbox'?80:0);};"
                + "all.sort((a,b)=>score(b)-score(a));const e=all[0];"
                + "if(!e)return 'FIELD_NOT_FOUND roots='+roots.length+' candidates='+all.length;"
                + "e.focus();if(e.isContentEditable||e.getAttribute('role')==='textbox'&&!('value' in e)){"
                + "e.textContent=p;e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:p}));}"
                + "else{const proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "const d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(e,p);else e.value=p;"
                + "try{e.setSelectionRange(p.length,p.length);}catch(x){}"
                + "e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:p}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));}"
                + "return ((('value' in e?e.value:e.textContent)||'').trim().length>0)?'FIELD_SET':'FIELD_VERIFY_FAILED';"
                + "}catch(e){return 'FIELD_ERROR:'+e.message;}})(" + JSONObject.quote(prompt) + ");";
    }

    private String buildGenerateClickScript() {
        return "(function(){try{"
                + "const roots=[];function collect(root){if(!root||roots.indexOf(root)>=0)return;roots.push(root);"
                + "try{Array.from(root.querySelectorAll('*')).forEach(n=>{if(n.shadowRoot)collect(n.shadowRoot);"
                + "if(n.tagName==='IFRAME'){try{if(n.contentDocument)collect(n.contentDocument);}catch(x){}}});}catch(x){}}"
                + "collect(document);"
                + "const visible=e=>{try{const r=e.getBoundingClientRect(),s=getComputedStyle(e);"
                + "return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';}catch(x){return false;}};"
                + "const a=[];roots.forEach(r=>{try{Array.from(r.querySelectorAll('button,[role=button],input[type=submit],[onclick]')).forEach(e=>{"
                + "if(visible(e)&&a.indexOf(e)<0)a.push(e);});}catch(x){}});"
                + "const label=e=>((e.innerText||e.textContent||e.value||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')).replace(/\\s+/g,' ').trim();"
                + "const b=a.find(e=>/сгенерировать|generate|создать изображение|create image/i.test(label(e)));"
                + "if(!b)return 'BUTTON_NOT_FOUND candidates='+a.length;"
                + "if(b.disabled||b.getAttribute('aria-disabled')==='true')return 'BUTTON_DISABLED';"
                + "b.click();return 'CLICKED';"
                + "}catch(e){return 'CLICK_ERROR:'+e.message;}})();";
    }

    private void installFrameCaptureBridge() {
        Set<String> origins = new HashSet<>(Arrays.asList(
                "https://perchance.org",
                "https://*.perchance.org"
        ));

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.addWebMessageListener(webView, BRIDGE_OBJECT, origins,
                        (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                            if (message != null && message.getType() == WebMessageCompat.TYPE_STRING) {
                                handleBridgePayload(message.getData());
                            }
                        });
            } catch (Exception e) {
                Log.w(TAG, "WebMessageListener unavailable", e);
            }
        } else {
            Log.w(TAG, "WEB_MESSAGE_LISTENER feature unavailable");
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, buildCaptureScript(), origins);
                documentStartCaptureInstalled = true;
                Log.i(TAG, "Perchance capture installed at document start in matching frames");
            } catch (Exception e) {
                Log.w(TAG, "Document-start capture unavailable; using page-finished fallback", e);
            }
        } else {
            Log.w(TAG, "DOCUMENT_START_SCRIPT feature unavailable");
        }
    }

    private String buildCaptureScript() {
        return "(function(){"
                + "if(window.__astraPerchanceCaptureInstalled)return;"
                + "window.__astraPerchanceCaptureInstalled=true;"
                + "function send(o){try{if(window." + BRIDGE_OBJECT + ")window." + BRIDGE_OBJECT
                + ".postMessage(JSON.stringify(o));}catch(e){}}"
                + "function handle(d){try{if(!d||typeof d!=='object')return;"
                + "if(d.kind==='image'&&d.src){send(d);return;}"
                + "if(d.type==='finished'&&typeof d.dataUrl==='string'&&d.dataUrl.indexOf('data:image/')===0)"
                + "{send({kind:'image',src:d.dataUrl,prompt:d.prompt||''});return;}"
                + "if((d.status==='success'||d.type==='success')&&d.imageId){"
                + "send({kind:'image',src:'https://image-generation.perchance.org/image/'+encodeURIComponent(d.imageId)+'.jpeg',prompt:d.prompt||''});return;}"
                + "var u=d.imageUrl||d.imageURL||d.url;"
                + "if(typeof u==='string'&&(u.indexOf('image-generation.perchance.org/image/')>=0||u.indexOf('data:image/')===0))"
                + "send({kind:'image',src:u,prompt:d.prompt||''});"
                + "}catch(e){}}"
                + "window.addEventListener('message',function(e){handle(e.data);},true);"
                + "try{var oldLog=console.log;console.log=function(){for(var i=0;i<arguments.length;i++)handle(arguments[i]);"
                + "return oldLog.apply(console,arguments);};}catch(e){}"
                + "function scan(){try{Array.from(document.images||[]).forEach(function(i){"
                + "var u=i.currentSrc||i.src||'';if(!u)return;"
                + "if(u.indexOf('image-generation.perchance.org/image/')<0&&u.indexOf('data:image/')!==0)return;"
                + "if(!i.complete||i.naturalWidth<100||i.naturalHeight<100)return;"
                + "send({kind:'image',src:u,prompt:''});});}catch(e){}}"
                + "scan();setInterval(scan,1800);"
                + "})();";
    }

    private boolean isPerchance(String value) {
        try {
            String host = Uri.parse(value == null ? "" : value).getHost();
            return host != null && (host.equalsIgnoreCase("perchance.org")
                    || host.toLowerCase(java.util.Locale.US).endsWith(".perchance.org"));
        } catch (Exception e) {
            return false;
        }
    }

    private void startObserver() {
        if (observing) return;
        observing = true;
        main.removeCallbacks(observer);
        main.post(observer);
        Log.i(TAG, "Dedicated Perchance observer started");
    }

    private void scanImages() {
        if (webView == null) return;
        String js = "(function(){const out=[],seen=new Set();"
                + "function add(u,w,h,ok){if(!u||seen.has(u))return;seen.add(u);"
                + "if((u.indexOf('image-generation.perchance.org/image/')>=0||u.indexOf('data:image/')===0)"
                + "&&ok&&w>=100&&h>=100)out.push(u);}"
                + "function scan(d){try{Array.from(d.images||[]).forEach(i=>add(i.currentSrc||i.src,i.naturalWidth,i.naturalHeight,i.complete));"
                + "Array.from(d.querySelectorAll('iframe')).forEach(f=>{try{if(f.contentDocument)scan(f.contentDocument);}catch(e){}});"
                + "}catch(e){}}scan(document);return JSON.stringify(out);})()";

        webView.evaluateJavascript(js, value -> {
            try {
                if (value == null || "null".equals(value)) return;
                Object parsed = new org.json.JSONTokener(value).nextValue();
                String decoded = parsed instanceof String ? (String) parsed : String.valueOf(parsed);
                JSONArray images = new JSONArray(decoded);
                if (images.length() > 0) {
                    Log.i(TAG, "DOM scan image candidates: " + images.length());
                }
                for (int i = 0; i < images.length(); i++) {
                    String src = images.optString(i, "");
                    if (!src.isEmpty()) acceptImage(src, "dom-scan");
                }
            } catch (Exception e) {
                Log.w(TAG, "DOM scan parse failed", e);
            }
        });
    }

    private void handleBridgePayload(String raw) {
        try {
            if (raw == null || raw.isEmpty()) return;
            JSONObject data = new JSONObject(raw);

            if ("image".equals(data.optString("kind"))) {
                String src = data.optString("src", "");
                if (!src.isEmpty()) acceptImage(src, "frame-message");
                return;
            }

            String type = data.optString("type", "");
            String statusValue = data.optString("status", "");
            if ("finished".equals(type) && data.optString("dataUrl", "").startsWith("data:image/")) {
                acceptImage(data.optString("dataUrl"), "postMessage-finished");
            } else if (("success".equals(type) || "success".equals(statusValue)) && data.has("imageId")) {
                String id = data.optString("imageId", "");
                if (!id.isEmpty()) {
                    acceptImage("https://image-generation.perchance.org/image/" +
                            Uri.encode(id) + ".jpeg", "postMessage-imageId");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Frame capture payload parse failed: " + e.getMessage());
        }
    }

    private void handleConsoleMessage(String raw) {
        if (raw == null) return;
        try {
            Object parsed = new org.json.JSONTokener(raw).nextValue();
            if (parsed instanceof JSONObject) handleBridgePayload(raw);
        } catch (Exception ignored) {
            // Ordinary page console messages are not capture events.
        }
    }

    private void captureBlobInPage(String blobUrl) {
        if (webView == null) return;
        String quoted = JSONObject.quote(blobUrl);
        String script = "(function(){try{fetch(" + quoted + ").then(r=>{if(!r.ok)throw new Error('blob_fetch_'+r.status);return r.blob();})"
                + ".then(b=>{const reader=new FileReader();reader.onload=()=>{try{if(window." + BRIDGE_OBJECT
                + ")window." + BRIDGE_OBJECT + ".postMessage(JSON.stringify({kind:'image',src:reader.result}));"
                + "}catch(e){}};reader.readAsDataURL(b);})"
                + ".catch(e=>{console.error('ASTRA_BLOB_CAPTURE_FAILED:'+e.message);});"
                + "}catch(e){console.error('ASTRA_BLOB_CAPTURE_FAILED:'+e.message);}})();";
        webView.evaluateJavascript(script, null);
    }

    private void acceptImage(String source, String origin) {
        if (source == null || source.isEmpty()) return;
        if (!(source.startsWith("https://") || source.startsWith("http://") || source.startsWith("data:image/"))) {
            setStatus("PERCHANCE · источник не поддерживается: " + origin);
            return;
        }
        if (!seen.add(source)) return;

        int nowDetected = detectedCount.incrementAndGet();
        setStatus("PERCHANCE · найдено: " + nowDetected + " · сохраняю…");

        downloads.execute(() -> {
            boolean ok;
            if (source.startsWith("data:image/")) {
                ok = saveDataUri(source);
            } else {
                ok = saveRemoteImage(source);
            }

            if (ok) {
                int saved = savedCount.incrementAndGet();
                setStatus("PERCHANCE · найдено: " + detectedCount.get()
                        + " · сохранено: " + saved + " · ошибки: " + failedCount.get());
            } else {
                seen.remove(source);
                int failed = failedCount.incrementAndGet();
                setStatus("PERCHANCE · найдено: " + detectedCount.get()
                        + " · сохранено: " + savedCount.get() + " · ошибки: " + failed);
            }
        });
    }

    private boolean saveDataUri(String dataUri) {
        try {
            int comma = dataUri.indexOf(',');
            if (comma <= 5) throw new IllegalArgumentException("invalid_data_uri");
            String header = dataUri.substring(5, comma);
            String mime = header.split(";")[0].toLowerCase(java.util.Locale.US);
            if (!mime.startsWith("image/")) throw new IllegalArgumentException("not_an_image");
            String payload = dataUri.substring(comma + 1);
            byte[] bytes;
            if (header.toLowerCase(java.util.Locale.US).contains(";base64")) {
                bytes = Base64.decode(payload, Base64.DEFAULT);
            } else {
                String decoded = URLDecoder.decode(payload, "UTF-8");
                bytes = decoded.getBytes(StandardCharsets.UTF_8);
            }
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("data_image_size_invalid");
            }
            return storeImageBytes(bytes, mime);
        } catch (Exception e) {
            Log.w(TAG, "Data URI save failed", e);
            return false;
        }
    }

    private boolean saveRemoteImage(String imageUrl) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(imageUrl);
            if (!"https".equalsIgnoreCase(url.getProtocol())) return false;

            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            connection.setRequestProperty("Referer", HOME);

            String cookie = CookieManager.getInstance().getCookie(imageUrl);
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);

            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                Log.w(TAG, "Image HTTP response " + response + " for " + imageUrl);
                return false;
            }

            String mime = connection.getContentType();
            if (mime != null) mime = mime.split(";")[0].trim().toLowerCase(java.util.Locale.US);
            if (mime == null || !mime.startsWith("image/")) {
                if (imageUrl.contains("image-generation.perchance.org/image/")) mime = "image/jpeg";
                else {
                    Log.w(TAG, "Not an image response: " + mime);
                    return false;
                }
            }

            long length = connection.getContentLengthLong();
            if (length > MAX_IMAGE_BYTES) return false;

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] chunk = new byte[16384];
                int count;
                long total = 0;
                while ((count = in.read(chunk)) != -1) {
                    total += count;
                    if (total > MAX_IMAGE_BYTES) throw new java.io.IOException("image exceeds 20 MB");
                    buffer.write(chunk, 0, count);
                }
            }
            byte[] bytes = buffer.toByteArray();
            if (bytes.length == 0) return false;
            return storeImageBytes(bytes, mime);
        } catch (Exception e) {
            Log.w(TAG, "Remote image save failed: " + e.getMessage(), e);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private boolean storeImageBytes(byte[] bytes, String mime) {
        Uri target = null;
        try {
            String cleanMime = mime == null ? "image/jpeg" : mime.split(";")[0].trim().toLowerCase(java.util.Locale.US);
            String ext;
            if (cleanMime.contains("png")) ext = ".png";
            else if (cleanMime.contains("webp")) ext = ".webp";
            else if (cleanMime.contains("gif")) ext = ".gif";
            else ext = ".jpg";

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "ASTRA_PERCHANCE_" + System.currentTimeMillis() + ext);
            values.put(MediaStore.Images.Media.MIME_TYPE, cleanMime);

            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/ASTRA/Perchance");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            target = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new java.io.IOException("MediaStore insert returned null");

            try (OutputStream out = getContentResolver().openOutputStream(target)) {
                if (out == null) throw new java.io.IOException("MediaStore output stream unavailable");
                out.write(bytes);
                out.flush();
            }

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (getContentResolver().update(target, ready, null, null) <= 0) {
                    throw new java.io.IOException("MediaStore publish failed");
                }
            }

            Log.i(TAG, "Perchance image saved: " + target + " (" + bytes.length + " bytes)");
            ChatActivity.onPerchanceImageReady(this, target);
            return true;
        } catch (Exception e) {
            if (target != null) {
                try { getContentResolver().delete(target, null, null); } catch (Exception ignored) {}
            }
            Log.e(TAG, "MediaStore save failed", e);
            return false;
        }
    }

    private void setStatus(String text) {
        main.post(() -> {
            if (status != null) status.setText(text);
        });
    }

    private String counters() {
        return "найдено " + detectedCount.get() + " · сохранено " + savedCount.get()
                + " · ошибки " + failedCount.get();
    }

    @Override public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override protected void onDestroy() {
        observing = false;
        main.removeCallbacks(observer);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        downloads.shutdown();
        super.onDestroy();
    }
}
