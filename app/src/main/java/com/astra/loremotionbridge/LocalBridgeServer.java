package com.astra.loremotionbridge;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LocalBridgeServer {
    private static final String TAG = "ASTRA-LocalBridge";
    private static volatile boolean running = false;
    private static volatile String lastError = null;
    private static ServerSocket server;

    public static boolean isRunning() { return running; }
    public static String getLastError() { return lastError; }

    public static synchronized void start() {
        if (running) return;
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket(18765);
                running = true;
                lastError = null;
                Log.i(TAG, "HTTP server listening on 127.0.0.1:18765");
                while (running) {
                    final Socket s = server.accept();
                    new Thread(() -> handle(s), "ASTRA-HTTP").start();
                }
            } catch (Exception e) {
                running = false;
                lastError = e.getClass().getName() + ": " + String.valueOf(e.getMessage());
                Log.e(TAG, "HTTP server FAILED", e);
                try { if (server != null) server.close(); } catch (Exception ignored) {}
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
            sock.setSoTimeout(10000);
            BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line == null) return;
            String[] parts = line.split(" ", 3);
            if (parts.length < 2) return;
            String target = parts[1];
            boolean binary = "/control/screen.png".equals(target.split("\\?", 2)[0]);
            byte[] body;
            String contentType;
            if (binary) {
                body = AstraControl.screenPng();
                contentType = "image/png";
                if (body == null) {
                    body = "{\"ok\":false,\"error\":\"screen frame unavailable\",\"hint\":\"Call /control/screen-status first\"}".getBytes(StandardCharsets.UTF_8);
                    contentType = "application/json; charset=utf-8";
                }
            } else {
                body = route(target).getBytes(StandardCharsets.UTF_8);
                contentType = "application/json; charset=utf-8";
            }
            OutputStream o = sock.getOutputStream();
            String headers = "HTTP/1.1 200 OK\r\nContent-Type: " + contentType
                    + "\r\nContent-Length: " + body.length
                    + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
            o.write(headers.getBytes(StandardCharsets.UTF_8));
            o.write(body);
            o.flush();
        } catch (Exception e) {
            Log.e(TAG, "HTTP request failed", e);
        }
    }

    private static String route(String path) {
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
