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

    public static void openAliceMedia(Activity host) {
        if (host == null || host.isFinishing()) return;

        Intent intent = new Intent(host, WebViewBrowserActivity.class);
        intent.putExtra("url", "https://alice.yandex.ru/media");
        host.startActivity(intent);
    }

    public static void submitAlice(Activity host, String imagePath, String prompt) {
        if (host == null || host.isFinishing() || imagePath == null || imagePath.isEmpty()
                || prompt == null || prompt.trim().isEmpty()) return;
        // Keep the browser bridge process at foreground-service priority while Alice renders
        // and the WebView Activity is moved behind the chat.
        try {
            Intent service = new Intent(host, BrowserBridgeService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                host.startForegroundService(service);
            } else {
                host.startService(service);
            }
        } catch (Exception ignored) { }

        Intent intent = new Intent(host, WebViewBrowserActivity.class);
        intent.putExtra("url", "https://alice.yandex.ru/media");
        intent.putExtra("alice_automate", true);
        intent.putExtra("alice_image_path", imagePath);
        intent.putExtra("alice_prompt", prompt.trim());
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
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
