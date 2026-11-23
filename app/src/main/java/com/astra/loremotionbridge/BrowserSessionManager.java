package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;

/**
 * Central entry point for ASTRA-managed browser providers.
 *
 * This first migration step deliberately delegates to the existing provider
 * Activities. Their WebView instances, cookies, automation scripts, download
 * bridges, and result-delivery code remain unchanged.
 */
public final class BrowserSessionManager {
    private BrowserSessionManager() { }

    public static void openPerchance(Activity host) {
        if (host == null || host.isFinishing()) return;
        host.startActivity(new Intent(host, PerchanceBrowserActivity.class));
    }

    public static void submitPerchance(Activity host, String prompt) {
        if (host == null || host.isFinishing() || TextUtils.isEmpty(prompt)) return;

        Intent intent = new Intent(host, PerchanceBrowserActivity.class);
        intent.putExtra(PerchanceBrowserPanel.EXTRA_AUTOMATE, true);
        intent.putExtra(PerchanceBrowserPanel.EXTRA_PROMPT, prompt);
        host.startActivity(intent);
    }

    public static void openFlow(Activity host) {
        if (host == null || host.isFinishing()) return;

        Intent intent = new Intent(host, FlowWebViewActivity.class);
        intent.putExtra("url", FlowWebViewActivity.FLOW_PROJECT_URL);
        host.startActivity(intent);
    }

    public static void submitFlow(Activity host, String prompt) {
        if (host == null || host.isFinishing() || TextUtils.isEmpty(prompt)) return;

        Intent intent = new Intent(host, FlowWebViewActivity.class);
        intent.putExtra("url", FlowWebViewActivity.FLOW_PROJECT_URL);
        intent.putExtra(FlowWebViewActivity.EXTRA_AUTOMATE, true);
        intent.putExtra(FlowWebViewActivity.EXTRA_PROMPT, prompt);
        intent.putExtra("background", true);
        host.startActivity(intent);
    }
}
