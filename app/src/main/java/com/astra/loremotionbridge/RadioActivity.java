package com.astra.loremotionbridge;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Official RadioPotok embed for SOUNDPARK DEEP. */
public class RadioActivity extends Activity {
    // Keep the player alive when RadioActivity closes.
    private static WebView sharedWebView;
    private WebView webView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8, 11, 24));
        getWindow().setNavigationBarColor(Color.rgb(8, 11, 24));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 11, 24));
        root.setPadding(dp(16), dp(14), dp(16), dp(12));

        TextView heading = new TextView(this);
        heading.setText("♫  ASTRA RADIO");
        heading.setTextColor(Color.rgb(229, 199, 255));
        heading.setTextSize(19);
        heading.setPadding(0, dp(8), 0, dp(4));
        root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        TextView station = new TextView(this);
        station.setText("SOUNDPARK DEEP  ·  DEEP HOUSE");
        station.setTextColor(Color.rgb(101, 221, 245));
        station.setTextSize(11);
        station.setPadding(0, 0, 0, dp(16));
        root.addView(station, new LinearLayout.LayoutParams(-1, -2));

        if (sharedWebView == null) {
            sharedWebView = new WebView(getApplicationContext());
            sharedWebView.setBackgroundColor(Color.rgb(8, 11, 24));
            sharedWebView.getSettings().setJavaScriptEnabled(true);
            sharedWebView.getSettings().setDomStorageEnabled(true);
            sharedWebView.getSettings().setMediaPlaybackRequiresUserGesture(true);
            sharedWebView.setWebViewClient(new WebViewClient());
            sharedWebView.setWebChromeClient(new WebChromeClient());
        }
        webView = sharedWebView;
        if (webView.getParent() instanceof ViewGroup) {
            ((ViewGroup) webView.getParent()).removeView(webView);
        }
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView note = new TextView(this);
        note.setText("Официальный плеер RadioPotok. Нажми Play; затем можно свернуть ASTRA.");
        note.setTextColor(Color.rgb(145, 160, 188));
        note.setTextSize(10);
        note.setPadding(0, dp(12), 0, dp(4));
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                "<meta charset='utf-8'><style>body{margin:0;background:#080b18;color:#eaf1ff;font-family:sans-serif}" +
                ".RP-SCRIPT{margin:0 auto;max-width:100%}.RP-LINK{color:#65ddf5}</style></head><body>" +
                "<div class='RP-SCRIPT'><a class='RP-LINK' href='https://radiopotok.ru/radio/1260'>SOUNDPARK DEEP</a></div>" +
                "<script defer src='https://radiopotok.ru/f/script6.1/1260.js' charset='UTF-8'></script>" +
                "</body></html>";
        webView.loadDataWithBaseURL("https://radiopotok.ru/", html, "text/html", "UTF-8", null);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            // Keep the WebView and its media session alive for ChatActivity.
            // Do not pause, stop loading, or destroy the player on Back.
            webView = null;
        }
        super.onDestroy();
    }
}
