package com.astra.loremotionbridge;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.ContentValues;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PerchanceBrowserActivity extends Activity {
    private static final String TAG = "ASTRA-PERCHANCE";
    private static final String HOME = "https://perchance.org/aigen-rus-edit";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final Set<String> seen = new HashSet<>();
    private WebView webView;
    private TextView status;
    private boolean observing;

    private final Runnable observer = new Runnable() {
        @Override public void run() {
            if (webView == null || isFinishing()) { observing = false; return; }
            String current = webView.getUrl();
            if (isPerchance(current)) scanImages();
            else { observing = false; return; }
            main.postDelayed(this, 2500L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        status.setText("PERCHANCE · готов к работе");
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
        webView.setWebChromeClient(new WebChromeClient());
        // Perchance download buttons are handled by Android instead of being ignored by WebView.
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
                    status.setText("PERCHANCE · неподдерживаемый формат скачивания");
                    return;
                }
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setTitle("ASTRA · Perchance image");
                request.setDescription("Сохранение изображения");
                request.setMimeType(mimeType == null ? "image/*" : mimeType);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES,
                        "ASTRA/Perchance/PERCHANCE_" + System.currentTimeMillis() + ".jpg");
                request.addRequestHeader("User-Agent", userAgent == null ? "Mozilla/5.0" : userAgent);
                request.addRequestHeader("Referer", HOME);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) request.addRequestHeader("Cookie", cookie);
                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
                manager.enqueue(request);
                status.setText("PERCHANCE · скачивание началось");
                Log.i(TAG, "DownloadManager queued Perchance file");
            } catch (Exception e) {
                Log.e(TAG, "DownloadListener failed", e);
                status.setText("PERCHANCE · ошибка скачивания");
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                status.setText("PERCHANCE · наблюдение за результатами");
                startObserver();
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        webView.loadUrl(HOME);
    }

    private boolean isPerchance(String value) {
        try {
            String host = Uri.parse(value == null ? "" : value).getHost();
            return host != null && (host.equalsIgnoreCase("perchance.org")
                    || host.toLowerCase(Locale.US).endsWith(".perchance.org"));
        } catch (Exception e) { return false; }
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
        String js = "(function(){"
                + "const out=[],seen=new Set();"
                + "function blocked(el){let n=el;for(let k=0;n&&k<7;k++,n=n.parentElement){"
                + "const s=String((n.id||'')+' '+(n.className||'')+' '+(n.getAttribute&&n.getAttribute('aria-label')||'')).toLowerCase();"
                + "if(/advert|sponsor|banner|реклама/.test(s))return true;}return false;}"
                + "function add(src,w,h,ok,el){if(!src||seen.has(src)||blocked(el))return;seen.add(src);"
                + "if(w>=256&&h>=256&&ok)out.push({src:src,w:w,h:h,ok:true});}"
                + "Array.from(document.images).forEach(i=>add(i.currentSrc||i.src,i.naturalWidth,i.naturalHeight,i.complete,i));"
                + "Array.from(document.querySelectorAll('*')).forEach(el=>{const bg=getComputedStyle(el).backgroundImage;"
                + "if(!bg||bg==='none')return;const m=bg.match(/url\\(['\"]?(https?:[^'\")]+)['\"]?\\)/);"
                + "if(!m)return;const r=el.getBoundingClientRect();add(m[1],Math.round(r.width),Math.round(r.height),true,el);});"
                + "return JSON.stringify(out);})()";
        webView.evaluateJavascript(js, value -> {
            try {
                if (value == null || "null".equals(value)) return;
                Object parsed = new org.json.JSONTokener(value).nextValue();
                String decoded = parsed instanceof String ? (String) parsed : String.valueOf(parsed);
                JSONArray images = new JSONArray(decoded);
                Log.i(TAG, "Perchance result candidates: " + images.length());
                runOnUiThread(() -> status.setText("PERCHANCE · найдено крупных изображений: " + images.length()));
                for (int i = 0; i < images.length(); i++) {
                    JSONObject item = images.optJSONObject(i);
                    if (item == null || !item.optBoolean("ok")) continue;
                    if (item.optInt("w") < 256 || item.optInt("h") < 256) continue;
                    String src = item.optString("src");
                    if (!src.startsWith("https://") || !seen.add(src)) continue;
                    downloads.execute(() -> {
                        boolean saved = saveImage(src);
                        if (!saved) seen.remove(src);
                    });
                }
            } catch (Exception e) { Log.w(TAG, "Image scan parse failed", e); }
        });
    }

    private boolean saveImage(String imageUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setRequestProperty("Referer", HOME);
            String cookie = CookieManager.getInstance().getCookie(imageUrl);
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            connection.connect();
            String mime = connection.getContentType();
            if (mime == null || !mime.toLowerCase(Locale.US).startsWith("image/")) return false;
            long length = connection.getContentLengthLong();
            if (length > 20L * 1024L * 1024L) return false;
            String ext = mime.toLowerCase(Locale.US).contains("png") ? ".png"
                    : mime.toLowerCase(Locale.US).contains("webp") ? ".webp" : ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "ASTRA_PERCHANCE_" + System.currentTimeMillis() + ext);
            values.put(MediaStore.Images.Media.MIME_TYPE, mime);
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ASTRA/Perchance");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri target = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (target == null) return false;
            try (InputStream in = connection.getInputStream();
                 OutputStream out = getContentResolver().openOutputStream(target)) {
                if (out == null) throw new java.io.IOException("output stream unavailable");
                byte[] buffer = new byte[8192];
                int n; long total = 0;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > 20L * 1024L * 1024L) throw new java.io.IOException("image exceeds 20 MB");
                    out.write(buffer, 0, n);
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(target, done, null, null);
            }
            Log.i(TAG, "Image saved " + target);
            ChatActivity.onPerchanceImageReady(this, target);
            runOnUiThread(() -> status.setText("PERCHANCE · изображение сохранено в ASTRA/Perchance"));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Image download failed: " + e.getMessage());
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
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
