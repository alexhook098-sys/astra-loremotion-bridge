package com.astra.loremotionbridge;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Independent Google Flow browser profile reserved for image generation. */
public final class Flow2WebViewActivity extends Activity {
    public static final String FLOW2_PROJECT_URL = "https://flow.google.com/u/2/project/4daa2354-7afc-44e5-b51d-1652eb4aaaac";
    public static final String EXTRA_AUTOMATE = "astra_flow2_automate";
    public static final String EXTRA_PROMPT = "astra_flow2_prompt";
    private final Handler main = new Handler(Looper.getMainLooper());
    private String pendingPrompt;
    private boolean promptEntered;
    private int attempts;
    private WebView webView;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        // Must run before the first WebView is created in this process.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("flow2");
        }
        super.onCreate(state);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(this,
                    "Google Flow 2 требует Android 9 или новее для отдельного профиля",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setText("ASTRA · GOOGLE FLOW 2 · отдельный профиль");
        status.setPadding(24, 18, 24, 18);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
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
            @Override public void onPageStarted(WebView view, String url,
                    android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                status.setText("ASTRA · GOOGLE FLOW 2 · открываю сервис…");
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                status.setText("ASTRA · GOOGLE FLOW 2 · " + url);
                if (pendingPrompt != null) main.postDelayed(Flow2WebViewActivity.this::attemptImageGeneration, 1400L);
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        String url = getIntent() == null ? null : getIntent().getStringExtra("url");
        if (url == null || url.trim().isEmpty()) url = FLOW2_PROJECT_URL;
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTOMATE, false)) {
            pendingPrompt = getIntent().getStringExtra(EXTRA_PROMPT);
        }
        webView.loadUrl(url);
    }

    private void attemptImageGeneration() {
        if (webView == null || pendingPrompt == null || isFinishing()) return;
        if (++attempts > 50) {
            status.setText("ASTRA · GOOGLE FLOW 2 · не удалось найти поле промпта; проверь страницу");
            Log.w("ASTRA-FLOW2", "Image automation timed out");
            pendingPrompt = null;
            return;
        }
        final String prompt = pendingPrompt;
        final String quoted = org.json.JSONObject.quote(prompt);
        final String script;
        if (!promptEntered) {
            script = "(function(p){try{"
                    + "const e=Array.from(document.querySelectorAll('[role=textbox],textarea,[contenteditable=true]')).find(x=>{"
                    + "const a=(x.getAttribute('aria-label')||'').toLowerCase();"
                    + "const h=(x.getAttribute('placeholder')||'').toLowerCase();"
                    + "return a.includes('что вы хотите создать')||a.includes('prompt')||h.includes('prompt')||x.getAttribute('role')==='textbox'||x.classList.contains('ProseMirror');});"
                    + "if(!e)return 'FIELD_NOT_FOUND';e.focus();"
                    + "if(e.isContentEditable||e.getAttribute('contenteditable')==='true'){e.innerText=p;}"
                    + "else {const proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                    + "const setter=Object.getOwnPropertyDescriptor(proto,'value')?.set;if(setter)setter.call(e,p);else e.value=p;}"
                    + "e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:p}));"
                    + "e.dispatchEvent(new Event('change',{bubbles:true}));return 'FIELD_SET';"
                    + "}catch(x){return 'ERROR:'+x.message;}})(" + quoted + ")";
        } else {
            script = "(function(){try{const b=Array.from(document.querySelectorAll('button,[role=button]')).find(x=>{"
                    + "const t=((x.innerText||x.textContent||'')+' '+(x.getAttribute('aria-label')||'')).toLowerCase();"
                    + "return !x.disabled&&(t.includes('начать генерацию')||t.includes('создать')||t.includes('generate'));});"
                    + "if(!b)return 'BUTTON_NOT_FOUND';if(b.getAttribute('aria-disabled')==='true')return 'BUTTON_DISABLED';"
                    + "b.click();return 'GENERATE_CLICKED';}catch(x){return 'ERROR:'+x.message;}})()";
        }
        webView.evaluateJavascript(script, result -> {
            String value = result == null ? "" : result;
            Log.i("ASTRA-FLOW2", "attempt=" + attempts + " result=" + value);
            if (value.contains("FIELD_SET")) {
                promptEntered = true;
                status.setText("ASTRA · GOOGLE FLOW 2 · промпт введён, запускаю генерацию…");
                main.postDelayed(this::attemptImageGeneration, 900L);
            } else if (value.contains("GENERATE_CLICKED")) {
                status.setText("ASTRA · GOOGLE FLOW 2 · запрос отправлен в проект");
                pendingPrompt = null;
            } else {
                status.setText("ASTRA · GOOGLE FLOW 2 · подключаюсь к проекту…");
                main.postDelayed(this::attemptImageGeneration, 1300L);
            }
        });
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent == null ? null : intent.getStringExtra("url");
        pendingPrompt = intent != null && intent.getBooleanExtra(EXTRA_AUTOMATE, false) ? intent.getStringExtra(EXTRA_PROMPT) : null;
        promptEntered = false; attempts = 0;
        if (webView != null && url != null && !url.trim().isEmpty()) webView.loadUrl(url);
    }
    @Override protected void onResume() {
        super.onResume();
        if (webView != null) { webView.onResume(); webView.resumeTimers(); }
    }
    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
