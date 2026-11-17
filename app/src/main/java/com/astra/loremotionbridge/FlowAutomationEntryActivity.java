package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Small exported entry point for Termux automation.
 *
 * mode=start: open the existing Flow WebView and submit the supplied prompt.
 * mode=resume: bring the existing Flow WebView task forward; its onResume()
 *              handler opens the newest completed Flow tile and captures it.
 */
public final class FlowAutomationEntryActivity extends Activity {
    public static final String EXTRA_MODE = "astra_flow_mode";
    public static final String EXTRA_PROMPT = "astra_flow_prompt";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        String mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = "start";

        Intent flow = new Intent(this, FlowWebViewActivity.class);
        flow.putExtra("url", FlowWebViewActivity.FLOW_PROJECT_URL);
        flow.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        if ("start".equals(mode)) {
            String prompt = getIntent().getStringExtra(EXTRA_PROMPT);
            if (prompt == null || prompt.trim().isEmpty()) {
                finish();
                return;
            }
            flow.putExtra(FlowWebViewActivity.EXTRA_AUTOMATE, true);
            flow.putExtra(FlowWebViewActivity.EXTRA_PROMPT, prompt.trim());
            flow.putExtra("background", true);
        } else if ("resume".equals(mode)) {
            // Explicit false is important: do not immediately minimize again.
            flow.putExtra("background", false);
        } else {
            finish();
            return;
        }

        startActivity(flow);
        finish();
    }
}
