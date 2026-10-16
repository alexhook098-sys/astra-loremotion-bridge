package com.astra.loremotionbridge;

import android.util.Log;

import android.content.Context;
import android.content.Intent;

public final class WebViewBrowserController {
    private WebViewBrowserController() {}

    public static String ensureHost(Context context) {
        final String loginUrl = "https://loremotion.com/generate/";
        WebViewBrowserActivity a = WebViewBrowserActivity.getInstance();

        if (a != null) {
            return bringToFront(context);
        }

        try {
            Intent i = new Intent(context, WebViewBrowserActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            i.putExtra("url", loginUrl);
            context.startActivity(i);
            return "{\"ok\":true,\"action\":\"webview-login-page-requested\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    public static String start(Context context, String url) {
        WebViewBrowserActivity a = WebViewBrowserActivity.getInstance();

        if (a != null) {
            a.openUrlInBackground(url);

            return "{\"ok\":true,\"action\":\"webview-open-requested\",\"url\":\"" +
                    esc(url) + "\"}";
        }

        try {
            Intent i = new Intent(context, WebViewBrowserActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            i.putExtra("url", url);
            i.putExtra("background", true);

            android.content.pm.ResolveInfo ri =
                    context.getPackageManager().resolveActivity(i, 0);

            if (ri == null) {
                return "{\"ok\":false,\"error\":\"webview_activity_not_resolvable\"}";
            }

            Log.i("ASTRA-WV-DIAG", "start(): calling startActivity url=" + url + " context=" + context.getClass().getName());
            context.startActivity(i);
            Log.i("ASTRA-WV-DIAG", "start(): startActivity returned normally");

            return "{\"ok\":true,\"action\":\"webview-start-requested\",\"resolved\":true,\"activity\":\"" +
                    esc(String.valueOf(ri.activityInfo.name)) +
                    "\",\"url\":\"" +
                    esc(url) +
                    "\"}";
        } catch (Exception e) {
            Log.e("ASTRA-WV-DIAG", "start(): startActivity failed", e);
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    public static String bringToFront(Context context) {
        WebViewBrowserActivity a = WebViewBrowserActivity.getInstance();

        if (a == null)
            return "{\"ok\":false,\"error\":\"webview_not_started\"}";

        try {
            Intent i = new Intent(context, WebViewBrowserActivity.class);
            i.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP |
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            );

            context.startActivity(i);

            return "{\"ok\":true,\"action\":\"webview-brought-to-front\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" +
                    esc(String.valueOf(e)) +
                    "\"}";
        }
    }

    public static String upload(String path) {
        WebViewBrowserActivity a = WebViewBrowserActivity.getInstance();

        if (a == null)
            return "{\"ok\":false,\"error\":\"webview_not_started\"}";

        if (path == null || path.isEmpty())
            return "{\"ok\":false,\"error\":\"upload_path_empty\"}";

        a.injectUploadFile(path);

        return "{\"ok\":true,\"action\":\"upload-inject-requested\",\"path\":\"" +
                esc(path) +
                "\"}";
    }

    public static String open(String url) {
        WebViewBrowserActivity a = WebViewBrowserActivity.getInstance();

        if (a == null)
            return "{\"ok\":false,\"error\":\"webview_not_started\"}";

        a.openUrlInBackground(url);

        return "{\"ok\":true,\"action\":\"webview-open\",\"url\":\"" +
                esc(url) +
                "\"}";
    }

    public static String status() {
        WebViewBrowserActivity a = WebViewBrowserActivity.getInstance();

        return a == null
                ? "{\"ok\":true,\"ready\":false,\"state\":\"stopped\"}"
                : a.statusJson();
    }

    public static String evaluate(String script) {
        WebViewBrowserActivity a = WebViewBrowserActivity.getInstance();

        if (a == null)
            return "{\"ok\":false,\"error\":\"webview_not_started\"}";

        final Object lock = new Object();
        final String[] result = new String[1];
        final String[] error = new String[1];
        final boolean[] done = new boolean[1];

        a.evaluate(script, (value, err) -> {
            synchronized (lock) {
                result[0] = value;
                error[0] = err;
                done[0] = true;
                lock.notifyAll();
            }
        });

        synchronized (lock) {
            try {
                if (!done[0])
                    lock.wait(15000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "{\"ok\":false,\"error\":\"interrupted\"}";
            }
        }

        if (!done[0])
            return "{\"ok\":false,\"error\":\"eval_timeout\"}";

        if (error[0] != null)
            return "{\"ok\":false,\"error\":\"" +
                    esc(error[0]) +
                    "\"}";

        return "{\"ok\":true,\"value\":" +
                (result[0] == null ? "null" : result[0]) +
                "}";
    }

    public static String readyMp4() {
        String script =
                "(()=>{" +
                "const s=document.documentElement.innerHTML;" +
                "const urls=[...s.matchAll(/https:\\\\/\\\\/videos\\\\.loremotion\\\\.com\\\\/videos\\\\/[^\"\\\\\\\\]+\\\\.mp4/g)].map(x=>x[0]);" +
                "return JSON.stringify([...new Set(urls)]);" +
                "})()";

        return evaluate(script);
    }

    private static String esc(String s) {
        if (s == null)
            return "";

        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
