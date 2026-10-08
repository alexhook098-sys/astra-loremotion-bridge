package com.astra.loremotionbridge;

import android.content.Intent;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LocalBridgeServer {
    private static volatile boolean running = false;
    private static ServerSocket server;

    public static synchronized void start() {
        if (running) return;
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket(8765, 50, InetAddress.getByName("127.0.0.1"));
                running = true;
                while (running) { final Socket s = server.accept(); new Thread(() -> handle(s), "ASTRA-HTTP").start(); }
            } catch (Exception ignored) { running = false; }
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

    private static void handle(Socket s) {
        try (Socket sock=s) {
            BufferedReader r=new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String line=r.readLine();
            if(line==null) return;
            String[] parts=line.split(" ",3);
            if(parts.length<2) return;
            String result=route(parts[1]);
            byte[] body=result.getBytes(StandardCharsets.UTF_8);
            OutputStream o=sock.getOutputStream();
            String h="HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
            o.write(h.getBytes(StandardCharsets.UTF_8)); o.write(body); o.flush();
        } catch(Exception ignored) {}
    }

    private static String route(String path) {
        try {
            int q=path.indexOf('?');
            String p=q>=0?path.substring(0,q):path;
            Map<String,String> m=new HashMap<>();
            if(q>=0) for(String x:path.substring(q+1).split("&")) {
                String[] kv=x.split("=",2);
                if(kv.length==2)m.put(URLDecoder.decode(kv[0],"UTF-8"),URLDecoder.decode(kv[1],"UTF-8"));
            }
            if("/health".equals(p)) return "{\"ok\":true,\"service\":\"astra-loremotion-bridge\",\"port\":8765}";
            if("/dump".equals(p)) return AstraAccessibilityService.dump();
            if("/click".equals(p)) return "{\"ok\":"+AstraAccessibilityService.click(m.getOrDefault("text",m.getOrDefault("description","")))+"}";
            if("/type".equals(p)) return "{\"ok\":"+AstraAccessibilityService.type(m.getOrDefault("field",""),m.getOrDefault("value",""))+"}";
            if("/open".equals(p)) { openUrl(m.getOrDefault("url","https://loremotion.com/generate/")); return "{\"ok\":true}"; }
            return "{\"error\":\"unknown endpoint\"}";
        } catch(Exception e) { return "{\"error\":\""+esc(e.toString())+"\"}"; }
    }
    private static String esc(String s){return s.replace("\\","\\\\").replace("\"","\\\"");}
}
