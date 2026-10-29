package com.astra.loremotionbridge;

import android.content.Context;
import android.content.Intent;

/** Bridge facade for the isolated Leonardo website WebView. */
public final class LeonardoWebViewController {
    private LeonardoWebViewController() {}
    public static String start(Context context, String url) {
        if (url == null || url.trim().isEmpty()) url = LeonardoWebViewActivity.DEFAULT_URL;
        LeonardoWebViewActivity activity = LeonardoWebViewActivity.getInstance();
        if (activity != null) { activity.openUrl(url); return "{\"ok\":true,\"action\":\"leonardo-open-reused\",\"url\":\""+esc(url)+"\"}"; }
        try {
            Intent intent = new Intent(context, LeonardoWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra("url", url);
            context.startActivity(intent);
            return "{\"ok\":true,\"action\":\"leonardo-start-requested\",\"url\":\""+esc(url)+"\"}";
        } catch (Exception e) { return "{\"ok\":false,\"error\":\""+esc(String.valueOf(e))+"\"}"; }
    }
    public static String open(Context context, String url) { return start(context, url); }
    public static String status() {
        LeonardoWebViewActivity a=LeonardoWebViewActivity.getInstance();
        return a==null ? "{\"ok\":true,\"ready\":false,\"state\":\"stopped\"}" : a.statusJson();
    }
    public static String evaluate(String script) {
        LeonardoWebViewActivity a=LeonardoWebViewActivity.getInstance();
        if(a==null)return "{\"ok\":false,\"error\":\"leonardo_not_started\"}";
        final Object lock=new Object(); final String[] result=new String[1]; final boolean[] done=new boolean[1];
        a.evaluate(script==null?"document.title":script, value->{synchronized(lock){result[0]=value;done[0]=true;lock.notifyAll();}});
        synchronized(lock){try{if(!done[0])lock.wait(15000L);}catch(InterruptedException e){Thread.currentThread().interrupt();return "{\"ok\":false,\"error\":\"interrupted\"}";}}
        return done[0] ? "{\"ok\":true,\"value\":"+(result[0]==null?"null":result[0])+"}" : "{\"ok\":false,\"error\":\"leonardo_eval_timeout\"}";
    }

    public static String armOriginalWatch() {
        LeonardoWebViewActivity a = LeonardoWebViewActivity.getInstance();
        return a == null
            ? "{\"ok\":false,\"error\":\"leonardo_not_started\"}"
            : a.armOriginalVideoWatch();
    }

    public static String downloadOriginal(String url) {
        LeonardoWebViewActivity a = LeonardoWebViewActivity.getInstance();
        if (a == null) return "{\"ok\":false,\"error\":\"leonardo_not_started\"}";
        return url == null || url.trim().isEmpty()
            ? a.downloadOriginalVideoFromPage()
            : a.downloadOriginalVideo(url);
    }
    public static String download() {
        LeonardoWebViewActivity a=LeonardoWebViewActivity.getInstance();
        return a==null ? "{\"ok\":false,\"error\":\"leonardo_not_started\"}" : a.clickDownloadButton();
    }
    public static String minimize() { LeonardoWebViewActivity a=LeonardoWebViewActivity.getInstance(); if(a==null)return "{\"ok\":false,\"error\":\"leonardo_not_started\"}"; a.minimize(); return "{\"ok\":true,\"action\":\"leonardo-minimize-requested\"}"; }
    public static String close() { LeonardoWebViewActivity a=LeonardoWebViewActivity.getInstance(); if(a==null)return "{\"ok\":true,\"action\":\"leonardo-already-closed\"}"; a.closeLeonardo(); return "{\"ok\":true,\"action\":\"leonardo-close-requested\"}"; }
    public static String bringToFront(Context context) {
        LeonardoWebViewActivity a=LeonardoWebViewActivity.getInstance();
        if(a!=null){ Intent i=new Intent(context,LeonardoWebViewActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); context.startActivity(i); a.bringToFront(); return "{\"ok\":true,\"action\":\"leonardo-brought-to-front\"}"; }
        return start(context, LeonardoWebViewActivity.DEFAULT_URL);
    }
    private static String esc(String s) { if(s==null)return ""; return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r"); }
}
