package com.astra.loremotionbridge;

import android.app.Activity;
import android.os.Bundle;

public class PerchanceBrowserActivity extends Activity {
    private static volatile PerchanceBrowserActivity instance;
    private PerchanceBrowserPanel panel;

    public static PerchanceBrowserActivity getInstance() { return instance; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        String prompt = getIntent() != null && getIntent().getBooleanExtra(PerchanceBrowserPanel.EXTRA_AUTOMATE, false)
                ? getIntent().getStringExtra(PerchanceBrowserPanel.EXTRA_PROMPT) : null;
        panel = new PerchanceBrowserPanel(this, false, prompt);
        setContentView(panel);
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (panel != null && intent != null && intent.getBooleanExtra(PerchanceBrowserPanel.EXTRA_AUTOMATE, false)) {
            panel.submitPrompt(intent.getStringExtra(PerchanceBrowserPanel.EXTRA_PROMPT));
        }
    }

    public void evaluateScript(String script, android.webkit.ValueCallback<String> callback) {
        if (panel != null) panel.evaluateScript(script, callback);
        else if (callback != null) callback.onReceiveValue(null);
    }

    public String statusJson() { return panel == null ? "{\"ok\":false,\"error\":\"not_ready\"}" : panel.statusJson(); }
    public void minimizePerchance() { if (panel != null) panel.minimizePerchance(); }

    @Override public void onBackPressed() { if (panel != null) panel.minimizePerchance(); else super.onBackPressed(); }
    @Override protected void onPause() { if (panel != null) panel.onHostPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (panel != null) panel.onHostResume(); }

    @Override protected void onDestroy() {
        if (instance == this) instance = null;
        if (panel != null) { panel.dispose(); panel = null; }
        super.onDestroy();
    }
}
