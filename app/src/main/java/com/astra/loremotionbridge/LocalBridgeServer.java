package com.astra.loremotionbridge;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
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

    public static boolean isRunning() {
        return running;
    }

    public static String getLastError() {
        return lastError;
    }

    public static synchronized void start() {

        if (running) return;

        Thread t = new Thread(() -> {

            try {

                server = new ServerSocket(18765);

                running = true;
                lastError = null;

                Log.i(
                        TAG,
                        "HTTP server listening on 127.0.0.1:18765"
                );

                while (running) {

                    final Socket s = server.accept();

                    new Thread(
                            () -> handle(s),
                            "ASTRA-HTTP"
                    ).start();
                }

            } catch (Exception e) {

                running = false;

                lastError =
                        e.getClass().getName() +
                        ": " +
                        String.valueOf(e.getMessage());

                Log.e(
                        TAG,
                        "HTTP server FAILED",
                        e
                );

                try {
                    if (server != null) {
                        server.close();
                    }
                } catch (Exception ignored) {
                }
            }

        }, "ASTRA-Bridge");

        t.setDaemon(true);
        t.start();
    }

    public static void openUrl(String url) {

        try {

            Intent i = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
            );

            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.setPackage("com.vivaldi.browser");

            MainApplication.context().startActivity(i);

        } catch (Exception e) {

            try {

                Intent i = new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(url)
                );

                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                MainApplication.context().startActivity(i);

            } catch (Exception ignored) {
            }
        }
    }

    private static void handle(Socket s) {

        try (Socket sock = s) {

            BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(
                                    sock.getInputStream(),
                                    StandardCharsets.UTF_8
                            )
                    );

            String line = r.readLine();

            if (line == null) return;

            String[] parts = line.split(" ", 3);

            if (parts.length < 2) return;

            String result = route(parts[1]);

            byte[] body =
                    result.getBytes(StandardCharsets.UTF_8);

            OutputStream o = sock.getOutputStream();

            String h =
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: " +
                    body.length +
                    "\r\n" +
                    "Connection: close\r\n\r\n";

            o.write(
                    h.getBytes(StandardCharsets.UTF_8)
            );

            o.write(body);
            o.flush();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "HTTP request failed",
                    e
            );
        }
    }

    private static String route(String path) {

        try {

            int q = path.indexOf('?');

            String p =
                    q >= 0
                            ? path.substring(0, q)
                            : path;

            Map<String, String> m =
                    new HashMap<>();

            if (q >= 0) {

                for (
                        String x :
                        path.substring(q + 1).split("&")
                ) {

                    String[] kv =
                            x.split("=", 2);

                    if (kv.length == 2) {

                        m.put(
                                URLDecoder.decode(
                                        kv[0],
                                        "UTF-8"
                                ),
                                URLDecoder.decode(
                                        kv[1],
                                        "UTF-8"
                                )
                        );
                    }
                }
            }

            if ("/health".equals(p)) {

                if (running) {

                    return
                            "{\"ok\":true," +
                            "\"service\":\"astra-browser-bridge\"," +
                            "\"port\":18765," +
                            "\"running\":true}";
                }

                return
                        "{\"ok\":false," +
                        "\"service\":\"astra-browser-bridge\"," +
                        "\"port\":18765," +
                        "\"running\":false," +
                        "\"error\":\"" +
                        esc(lastError) +
                        "\"}";
            }

            if ("/dump".equals(p)) {
                return AstraAccessibilityService.dump();
            }

            if ("/windows".equals(p)) {
                return AstraAccessibilityService.windows();
            }

            if ("/service-info".equals(p)) {
                return AstraAccessibilityService.serviceInfo();
            }

            if ("/find".equals(p)) {
                return AstraAccessibilityService.findInfo(
                        m.getOrDefault("text",
                                m.getOrDefault("description", ""))
                );
            }

            if ("/click".equals(p)) {

                return
                        "{\"ok\":" +
                        AstraAccessibilityService.clickInfo(
                                m.getOrDefault(
                                        "text",
                                        m.getOrDefault(
                                                "description",
                                                ""
                                        )
                                )
                        ) +
                        "}";
            }

            if ("/type".equals(p)) {

                return
                        "{\"ok\":" +
                        AstraAccessibilityService.typeInfo(
                                m.getOrDefault("field", ""),
                                m.getOrDefault("value", "")
                        ) +
                        "}";
            }

            if ("/open-and-dump".equals(p)) {
                openUrl(
                        m.getOrDefault(
                                "url",
                                "https://www.google.com"
                        )
                );

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                return AstraAccessibilityService.dump();
            }

            if ("/open".equals(p)) {

                openUrl(
                        m.getOrDefault(
                                "url",
                                "https://loremotion.com/generate/"
                        )
                );

                return "{\"ok\":true}";
            }

            return "{\"error\":\"unknown endpoint\"}";

        } catch (Exception e) {

            return
                    "{\"error\":\"" +
                    esc(e.toString()) +
                    "\"}";
        }
    }

    private static String esc(String s) {

        if (s == null) return "";

        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
