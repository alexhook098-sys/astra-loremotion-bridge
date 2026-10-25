package com.astra.loremotionbridge;

import org.json.JSONObject;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LocalBridgeServer {
    private static final String TAG = "ASTRA-LocalBridge";
    private static volatile boolean running = false;
    private static volatile boolean starting = false;
    private static volatile String lastError = null;
    private static ServerSocket server;

    public static boolean isRunning() { return running; }
    public static boolean isStarting() { return starting; }
    public static String getLastError() { return lastError; }

    public static synchronized void start() {
        if (running || starting) {
            Log.i(TAG, "start() ignored; running=" + running + ", starting=" + starting);
            return;
        }
        starting = true;
        Thread t = new Thread(() -> {
            ServerSocket localServer = null;
            try {
                localServer = new ServerSocket();
                localServer.setReuseAddress(true);
                localServer.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 18765));
                server = localServer;
                running = true;
                lastError = null;
                Log.i(TAG, "HTTP server listening on 127.0.0.1:18765");
                while (running) {
                    final Socket s = localServer.accept();
                    new Thread(() -> handle(s), "ASTRA-HTTP").start();
                }
            } catch (Exception e) {
                running = false;
                lastError = e.getClass().getName() + ": " + String.valueOf(e.getMessage());
                Log.e(TAG, "HTTP server FAILED", e);
                try {
                    if (localServer != null) localServer.close();
                } catch (Exception ignored) {}
            } finally {
                running = false;
                starting = false;
                if (server == localServer) server = null;
            }
        }, "ASTRA-Bridge");
        t.setDaemon(true);
        t.start();
    }

    public static void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.setPackage("com.vivaldi.browser");
            MainApplication.context().startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                MainApplication.context().startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    public static void openBrowserChooser(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            MainApplication.context().startActivity(Intent.createChooser(i, "Выберите браузер"));
        } catch (Exception e) {
            Log.e(TAG, "Browser chooser failed", e);
        }
    }

    public static String browserHandlers() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://loremotion.com/generate/"));
            android.content.pm.PackageManager pm = MainApplication.context().getPackageManager();
            java.util.List<android.content.pm.ResolveInfo> list =
                    pm.queryIntentActivities(i, android.content.pm.PackageManager.MATCH_ALL);

            StringBuilder out = new StringBuilder("{\"ok\":true,\"handlers\":[");
            for (int n = 0; n < list.size(); n++) {
                if (n > 0) out.append(",");
                android.content.pm.ResolveInfo r = list.get(n);
                out.append("{\"package\":\"")
                   .append(esc(r.activityInfo.packageName))
                   .append("\",\"activity\":\"")
                   .append(esc(r.activityInfo.name))
                   .append("\"}");
            }
            out.append("]}");
            return out.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
        }
    }

    private static void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);

            java.io.InputStream input = sock.getInputStream();

            StringBuilder headerText = new StringBuilder();
            int previous = -1;
            int current;

            while ((current = input.read()) != -1) {
                headerText.append((char) current);
                if (previous == '\r' && current == '\n') {
                    int len = headerText.length();
                    if (len >= 4 &&
                            headerText.charAt(len - 4) == '\r' &&
                            headerText.charAt(len - 3) == '\n') {
                        break;
                    }
                }
                previous = current;
            }

            String headersText = headerText.toString();
            String[] headerLines = headersText.split("\r\n");
            if (headerLines.length == 0) return;

            String[] parts = headerLines[0].split(" ", 3);
            if (parts.length < 2) return;

            String method = parts[0];
            String target = parts[1];

            int contentLength = 0;

            for (int i = 1; i < headerLines.length; i++) {
                String h = headerLines[i];
                int colon = h.indexOf(':');
                if (colon < 0) continue;

                String name = h.substring(0, colon).trim();
                String value = h.substring(colon + 1).trim();

                if ("Content-Length".equalsIgnoreCase(name)) {
                    contentLength = Integer.parseInt(value);
                }
            }

            // ASTRA_BING_POST_JSON_PATCH_V2: only these adapter routes accept JSON.
            Map<String,String> postParams = null;
            String routeOnlyPath = target.split("\\?", 2)[0];
            boolean jsonAdapterRoute =
                    "/webview/start".equals(routeOnlyPath) ||
                    "/webview/open".equals(routeOnlyPath) ||
                    "/webview/eval".equals(routeOnlyPath) ||
                    "/webview/bing-eval".equals(routeOnlyPath) ||
                "/webview/bing-save-image".equals(routeOnlyPath) ||
                    "/webview/bing-image-chunk".equals(routeOnlyPath) ||
                    "/flow/start".equals(routeOnlyPath) ||
                    "/flow/open".equals(routeOnlyPath) ||
                    "/flow/eval".equals(routeOnlyPath) ||
                    "/flow/front".equals(routeOnlyPath);
            if ("POST".equalsIgnoreCase(method) && jsonAdapterRoute) {
                if (contentLength <= 0 || contentLength > 1024 * 1024) {
                    sendResponse(sock, "{\"ok\":false,\"error\":\"invalid_json_body_size\"}",
                            "application/json; charset=utf-8");
                    return;
                }
                byte[] jsonBytes = new byte[contentLength];
                int jsonOffset = 0;
                while (jsonOffset < contentLength) {
                    int n = input.read(jsonBytes, jsonOffset, contentLength - jsonOffset);
                    if (n < 0) break;
                    jsonOffset += n;
                }
                if (jsonOffset != contentLength) {
                    sendResponse(sock, "{\"ok\":false,\"error\":\"json_body_incomplete\"}",
                            "application/json; charset=utf-8");
                    return;
                }
                try {
                    JSONObject json = new JSONObject(new String(jsonBytes, StandardCharsets.UTF_8));
                    postParams = new HashMap<>();
                    java.util.Iterator<String> keys = json.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object value = json.get(key);
                        if (value != JSONObject.NULL && value != null) {
                            postParams.put(key, String.valueOf(value));
                        }
                    }
                } catch (Exception jsonError) {
                    sendResponse(sock, "{\"ok\":false,\"error\":\"invalid_json_body\"}",
                            "application/json; charset=utf-8");
                    return;
                }
            }

            if ("POST".equalsIgnoreCase(method) &&
                    "/webview/upload".equals(target.split("\\?", 2)[0])) {

                if (contentLength <= 0 || contentLength > 20 * 1024 * 1024) {
                    sendResponse(sock,
                            "{\"ok\":false,\"error\":\"invalid_upload_size\"}",
                            "application/json; charset=utf-8");
                    return;
                }

                byte[] data = new byte[contentLength];
                int offset = 0;

                while (offset < contentLength) {
                    int n = input.read(data, offset, contentLength - offset);
                    if (n < 0) break;
                    offset += n;
                }

                if (offset != contentLength) {
                    sendResponse(sock,
                            "{\"ok\":false,\"error\":\"upload_body_incomplete\"}",
                            "application/json; charset=utf-8");
                    return;
                }

                File uploadDir = new File(
                        MainApplication.context().getCacheDir(),
                        "uploads");

                if (!uploadDir.exists() && !uploadDir.mkdirs()) {
                    sendResponse(sock,
                            "{\"ok\":false,\"error\":\"upload_cache_create_failed\"}",
                            "application/json; charset=utf-8");
                    return;
                }

                File targetFile = new File(
                        uploadDir,
                        "telegram-" + System.currentTimeMillis() + ".jpg");

                try (FileOutputStream out = new FileOutputStream(targetFile)) {
                    out.write(data);
                }

                String result = WebViewBrowserController.upload(
                        targetFile.getAbsolutePath());

                sendResponse(sock, result,
                        "application/json; charset=utf-8");
                return;
            }

            boolean binary =
                    "/control/screen.png".equals(target.split("\\?", 2)[0]);

            byte[] body;
            String contentType;

            if (binary) {
                body = AstraControl.screenPng();
                contentType = "image/png";

                if (body == null) {
                    body = "{\"ok\":false,\"error\":\"screen frame unavailable\",\"hint\":\"Call /control/screen-status first\"}"
                            .getBytes(StandardCharsets.UTF_8);
                    contentType = "application/json; charset=utf-8";
                }
            } else {
                body = route(target, postParams).getBytes(StandardCharsets.UTF_8);
                contentType = "application/json; charset=utf-8";
            }

            sendResponse(sock, body, contentType);

        } catch (Exception e) {
            Log.e(TAG, "HTTP request failed", e);
        }
    }

    private static void sendResponse(
            Socket sock,
            String body,
            String contentType) throws Exception {
        sendResponse(sock, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private static void sendResponse(
            Socket sock,
            byte[] body,
            String contentType) throws Exception {

        OutputStream o = sock.getOutputStream();

        String headers =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n";

        o.write(headers.getBytes(StandardCharsets.UTF_8));
        o.write(body);
        o.flush();
    }

    private static String route(String path) {
        return route(path, null);
    }

    // ASTRA_BING_POST_JSON_PATCH_V2: merge JSON POST fields with legacy query parameters.
    private static String route(String path, Map<String,String> postParams) {
        try {
            int q = path.indexOf('?');
            String p = q >= 0 ? path.substring(0, q) : path;
            Map<String,String> m = new HashMap<>();
            if (q >= 0) {
                for (String x : path.substring(q + 1).split("&")) {
                    String[] kv = x.split("=", 2);
                    if (kv.length == 2) m.put(URLDecoder.decode(kv[0],"UTF-8"), URLDecoder.decode(kv[1],"UTF-8"));
                }
            }
            if (postParams != null) m.putAll(postParams);
            if ("/headless/status".equals(p)) return HeadlessBrowserRuntime.statusJson();
            if ("/headless/log".equals(p)) return HeadlessBrowserRuntime.logJson();
            if ("/headless/start".equals(p)) {
                HeadlessBrowserRuntime.startAsync(MainApplication.context());
                return "{\"ok\":true,\"state\":\"STARTING\"}";
            }
            if ("/headless/open".equals(p)) return HeadlessBrowserRuntime.openUrl(m.getOrDefault("url", "https://loremotion.com/generate/"));
            if ("/flow/start".equals(p)) return FlowWebViewController.start(
                    MainApplication.context(),
                    m.getOrDefault("url", "https://flow.google.com/project/f40f4fe3-b63d-4e8a-82cd-4afb2e30558d"));
            if ("/flow/open".equals(p)) return FlowWebViewController.open(
                    MainApplication.context(),
                    m.getOrDefault("url", "https://flow.google.com/project/f40f4fe3-b63d-4e8a-82cd-4afb2e30558d"));
            if ("/flow/status".equals(p)) return FlowWebViewController.status();
            if ("/flow/eval".equals(p)) return FlowWebViewController.evaluate(
                    m.getOrDefault("script", "document.title"));
            if ("/flow/minimize".equals(p)) return FlowWebViewController.minimize();
            if ("/flow/close".equals(p)) return FlowWebViewController.close();
            if ("/flow/front".equals(p)) return FlowWebViewController.bringToFront(MainApplication.context());
            if ("/webview/bing-close".equals(p)) return WebViewBrowserController.closeBing();
            if ("/webview/close".equals(p)) return WebViewBrowserController.close();
            if ("/webview/status".equals(p)) return WebViewBrowserController.status();
            if ("/webview/start".equals(p)) return WebViewBrowserController.start(
                    MainApplication.context(),
                    m.getOrDefault("url", "https://loremotion.com/generate/"));
            if ("/webview/open".equals(p)) return WebViewBrowserController.open(
                    m.getOrDefault("url", "https://loremotion.com/generate/"));
            if ("/webview/bing-save-image".equals(p)) return WebViewBrowserController.saveBingImage(
                    m.getOrDefault("url", ""));
            if ("/webview/bing-image-chunk".equals(p)) return WebViewBrowserController.readBingImageChunk(
                    m.getOrDefault("id", ""), m.getOrDefault("offset", "0"),
                    m.getOrDefault("length", "32768"));
            if ("/webview/bing-inspect".equals(p)) return WebViewBrowserController.inspectBing();
            if ("/webview/bing-eval".equals(p)) return WebViewBrowserController.evaluateBing(
                    m.getOrDefault("script", "document.title"));
            if ("/webview/eval".equals(p)) return WebViewBrowserController.evaluate(
                    m.getOrDefault("script", "document.title"));
            if ("/webview/ready-mp4".equals(p)) return WebViewBrowserController.readyMp4();
            if ("/webview/upload".equals(p)) return WebViewBrowserController.upload(
                    m.getOrDefault("path", ""));


            if ("/control/observe".equals(p)) return AstraControl.observe();
            if ("/control/status".equals(p)) return AstraControl.status();
            if ("/control/screen-status".equals(p)) return AstraControl.screenStatus();
            if ("/health".equals(p)) return running
                    ? "{\"ok\":true,\"service\":\"astra-browser-bridge\",\"port\":18765,\"running\":true}"
                    : "{\"ok\":false,\"service\":\"astra-browser-bridge\",\"port\":18765,\"running\":false,\"error\":\"" + esc(lastError) + "\"}";
            if ("/dump".equals(p)) return AstraAccessibilityService.dump();
            if ("/windows".equals(p)) return AstraAccessibilityService.windows();
            if ("/service-info".equals(p)) return AstraAccessibilityService.serviceInfo();
            if ("/find".equals(p)) return AstraAccessibilityService.findInfo(m.getOrDefault("text",m.getOrDefault("description","")));
            if ("/click".equals(p)) return AstraAccessibilityService.clickInfo(m.getOrDefault("text",m.getOrDefault("description","")));
            if ("/type".equals(p)) return AstraAccessibilityService.typeInfo(m.getOrDefault("field",""),m.getOrDefault("value",""));
            if ("/browser/tap".equals(p)) return BrowserController.tap(Float.parseFloat(m.getOrDefault("x","0")), Float.parseFloat(m.getOrDefault("y","0")));
            if ("/browser/swipe".equals(p)) return BrowserController.swipe(
                    Float.parseFloat(m.getOrDefault("x1","0")), Float.parseFloat(m.getOrDefault("y1","0")),
                    Float.parseFloat(m.getOrDefault("x2","0")), Float.parseFloat(m.getOrDefault("y2","0")),
                    Long.parseLong(m.getOrDefault("duration","350")));
            if ("/browser/scroll".equals(p)) return BrowserController.scroll(m.getOrDefault("direction","down"));
            if ("/browser/back".equals(p)) return BrowserController.back();
            if ("/browser/home".equals(p)) return BrowserController.home();
            if ("/browser/focus".equals(p)) return BrowserController.focus(m.getOrDefault("text",""));
            if ("/control/browser-handlers".equals(p)) {
                return browserHandlers();
            }

            if ("/control/open-chrome".equals(p)) {
            String url = m.getOrDefault("url", "https://loremotion.com/generate/");
            try {
                android.content.Intent i =
                        new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url));
                i.setPackage("com.android.chrome");
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                MainApplication.context().startActivity(i);

                return "{\"ok\":true,\"action\":\"open-chrome\",\"url\":\"" + esc(url) + "\"}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
            }
        }

        if ("/control/launch-app".equals(p)) {
            String pkg = m.getOrDefault("package", "com.android.chrome");
            try {
                android.content.pm.PackageManager pm =
                        MainApplication.context().getPackageManager();
                android.content.Intent launch =
                        new android.content.Intent(android.content.Intent.ACTION_MAIN);
                launch.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
                launch.setPackage(pkg);
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);

                MainApplication.context().startActivity(launch);

                return "{\"ok\":true,\"action\":\"launch-app\",\"package\":\"" + esc(pkg) + "\"}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}";
            }
        }

        if ("/control/visual-tap".equals(p)) {
            float x = Float.parseFloat(m.getOrDefault("x", "0"));
            float y = Float.parseFloat(m.getOrDefault("y", "0"));
            return BrowserController.visualTap(x, y);
        }

        if ("/control/open-browser".equals(p)) {
                openBrowserChooser(m.getOrDefault("url","https://loremotion.com/generate/"));
                return "{\"ok\":true,\"action\":\"open-browser\"}";
            }

            if ("/open-and-dump".equals(p)) {
                openUrl(m.getOrDefault("url","https://www.google.com"));
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return AstraAccessibilityService.dump();
            }
            if ("/open".equals(p)) {
                openUrl(m.getOrDefault("url","https://loremotion.com/generate/"));
                return "{\"ok\":true}";
            }
            return "{\"error\":\"unknown endpoint\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.toString()) + "\"}";
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
    }
}
