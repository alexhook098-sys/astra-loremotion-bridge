package com.astra.loremotionbridge;

/** Routes local bridge inspection requests to the Alice WebView embedded in ChatActivity. */
public final class AliceBrowserController {
    private AliceBrowserController() {}

    private static AliceBrowserPanel panel() {
        return ChatActivity.alicePanelForBridge();
    }

    public static String status() {
        AliceBrowserPanel panel = panel();
        return panel == null
                ? "{\"ok\":true,\"panel_exists\":false,\"webview_exists\":false}"
                : panel.bridgeStatusJson();
    }

    public static String evaluate(String script) {
        AliceBrowserPanel panel = panel();
        return panel == null
                ? "{\"ok\":false,\"error\":\"alice_panel_not_open\"}"
                : panel.bridgeEvaluate(script);
    }

    public static String dom() {
        return evaluate("(function(){return JSON.stringify({url:location.href,title:document.title,readyState:document.readyState,body:document.body?document.body.innerText.slice(0,12000):'',elements:[...document.querySelectorAll('button,input,textarea,select,[role=button],[contenteditable=true]')].slice(0,250).map((e,i)=>({i,tag:e.tagName,type:e.type||'',role:e.getAttribute('role')||'',text:(e.innerText||e.getAttribute('aria-label')||e.placeholder||e.value||'').trim().slice(0,300),placeholder:e.placeholder||'',aria:e.getAttribute('aria-label')||'',disabled:!!e.disabled}))})})()");
    }

    public static byte[] screenshotPng() {
        AliceBrowserPanel panel = panel();
        return panel == null ? null : panel.bridgeScreenshotPng();
    }
}
