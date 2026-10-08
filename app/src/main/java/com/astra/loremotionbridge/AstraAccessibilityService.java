package com.astra.loremotionbridge;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

public class AstraAccessibilityService extends AccessibilityService {
    private static volatile AstraAccessibilityService instance;
    @Override public void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {}
    @Override public void onInterrupt() { if (instance == this) instance = null; }

    public static AccessibilityNodeInfo root() { return instance == null ? null : instance.getRootInActiveWindow(); }

    public static String dump() {
        AccessibilityNodeInfo r = root();
        if (r == null) return "{\"error\":\"accessibility service is not connected or no active window\"}";
        StringBuilder out = new StringBuilder();
        append(r, out, 0);
        r.recycle();
        return out.toString();
    }

    private static void append(AccessibilityNodeInfo n, StringBuilder out, int depth) {
        if (n == null) return;
        indent(out, depth);
        out.append("{\"class\":\"").append(esc(n.getClassName())).append("\",\"text\":\"")
           .append(esc(n.getText())).append("\",\"desc\":\"").append(esc(n.getContentDescription()))
           .append("\",\"hint\":\"").append(esc(n.getHintText())).append("\",\"id\":\"")
           .append(esc(n.getViewIdResourceName())).append("\",\"editable\":").append(n.isEditable())
           .append(",\"clickable\":").append(n.isClickable()).append(",\"bounds\":\"");
        Rect b = new Rect(); n.getBoundsInScreen(b);
        out.append(esc(b.toShortString())).append("\",\"children\":[\n");
        for (int i = 0; i < n.getChildCount(); i++) {
            if (i > 0) out.append(",\n");
            AccessibilityNodeInfo c = n.getChild(i);
            append(c, out, depth + 1);
            if (c != null) c.recycle();
        }
        out.append("]}");
    }
    private static void indent(StringBuilder s, int n) { for (int i=0;i<n;i++) s.append("  "); }
    private static String esc(Object o) {
        if (o == null) return "";
        return String.valueOf(o).replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
    }

    public static boolean click(String query) {
        AccessibilityNodeInfo r = root();
        if (r == null) return false;
        AccessibilityNodeInfo n = find(r, query == null ? "" : query.toLowerCase());
        boolean ok = n != null && clickNode(n);
        if (n != null) n.recycle();
        r.recycle();
        return ok;
    }
    private static boolean clickNode(AccessibilityNodeInfo n) {
        if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo p = n.getParent();
        if (p != null) { boolean ok = clickNode(p); p.recycle(); return ok; }
        return false;
    }
    public static boolean type(String field, String value) {
        AccessibilityNodeInfo r = root();
        if (r == null) return false;
        AccessibilityNodeInfo n = findEditable(r, field == null ? "" : field.toLowerCase());
        boolean ok = false;
        if (n != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value == null ? "" : value);
            ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            n.recycle();
        }
        r.recycle();
        return ok;
    }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo n, String q) {
        if (has(n.getText(),q) || has(n.getContentDescription(),q) || has(n.getHintText(),q) || has(n.getViewIdResourceName(),q)) return n;
        for (int i=0;i<n.getChildCount();i++) {
            AccessibilityNodeInfo c=n.getChild(i);
            AccessibilityNodeInfo hit=c==null?null:find(c,q);
            if (hit!=null) { if(c!=hit)c.recycle(); return hit; }
            if(c!=null)c.recycle();
        }
        return null;
    }
    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n, String q) {
        if (n.isEditable() && (q.isEmpty() || has(n.getText(),q) || has(n.getHintText(),q) || has(n.getContentDescription(),q) || has(n.getViewIdResourceName(),q))) return n;
        for (int i=0;i<n.getChildCount();i++) {
            AccessibilityNodeInfo c=n.getChild(i);
            AccessibilityNodeInfo hit=c==null?null:findEditable(c,q);
            if(hit!=null){ if(c!=hit)c.recycle(); return hit; }
            if(c!=null)c.recycle();
        }
        return null;
    }
    public static String serviceInfo() {
        if (instance == null) {
            return "{\"error\":\"accessibility service is not connected\"}";
        }

        android.accessibilityservice.AccessibilityServiceInfo info =
                instance.getServiceInfo();

        if (info == null) {
            return "{\"error\":\"service info is null\"}";
        }

        return "{"
                + "\"flags\":" + info.flags + ","
                + "\"eventTypes\":" + info.eventTypes + ","
                + "\"feedbackType\":" + info.feedbackType + ","
                + "\"notificationTimeout\":" + info.notificationTimeout
                + "}";
    }

    public static String windows() {
        if (instance == null) {
            return "{\"error\":\"accessibility service is not connected\"}";
        }

        StringBuilder out = new StringBuilder();
        out.append("{\"windows\":[");

        java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows =
                instance.getWindows();

        for (int i = 0; i < windows.size(); i++) {
            if (i > 0) out.append(",");

            android.view.accessibility.AccessibilityWindowInfo w = windows.get(i);

            out.append("{");
            out.append("\"id\":").append(w.getId()).append(",");
            out.append("\"type\":").append(w.getType()).append(",");
            out.append("\"active\":").append(w.isActive()).append(",");
            out.append("\"focused\":").append(w.isFocused()).append(",");

            AccessibilityNodeInfo root = w.getRoot();

            if (root != null) {
                out.append("\"package\":\"")
                        .append(esc(root.getPackageName()))
                        .append("\",");
                out.append("\"class\":\"")
                        .append(esc(root.getClassName()))
                        .append("\"");
                root.recycle();
            } else {
                out.append("\"package\":\"\",\"class\":\"\"");
            }

            out.append("}");
            w.recycle();
        }

        out.append("]}");
        return out.toString();
    }

    private static boolean has(CharSequence s, String q) { return s != null && s.toString().toLowerCase().contains(q); }
}
