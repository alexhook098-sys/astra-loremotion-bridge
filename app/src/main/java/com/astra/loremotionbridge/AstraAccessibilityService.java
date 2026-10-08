package com.astra.loremotionbridge;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

public class AstraAccessibilityService extends AccessibilityService {
    private static volatile AstraAccessibilityService instance;
    @Override public void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {}
    @Override public void onInterrupt() {
        // Temporary interruption does not mean that the service is disconnected.
    }

    public static AccessibilityNodeInfo root() {
        if (instance == null) return null;

        java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows =
                instance.getWindows();

        AccessibilityNodeInfo fallback = null;

        for (android.view.accessibility.AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo r = w.getRoot();

            if (r == null) {
                w.recycle();
                continue;
            }

            String pkg = String.valueOf(r.getPackageName());

            // Prefer Vivaldi whenever it is available.
            // This allows ASTRA to control the browser even when
            // the ASTRA Browser Control UI is currently focused.
            if ("com.vivaldi.browser".equals(pkg)) {
                w.recycle();
                if (fallback != null) fallback.recycle();
                return r;
            }

            if (w.isActive() && fallback == null) {
                fallback = r;
            } else {
                r.recycle();
            }

            w.recycle();
        }

        return fallback;
    }

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

    public static String findInfo(String query) {
        AccessibilityNodeInfo r = root();
        if (r == null) {
            return "{\"ok\":false,\"error\":\"accessibility service is not connected\"}";
        }

        AccessibilityNodeInfo n = find(
                r,
                query == null ? "" : query.toLowerCase()
        );

        if (n == null) {
            r.recycle();
            return "{\"ok\":false,\"found\":false}";
        }

        Rect b = new Rect();
        n.getBoundsInScreen(b);

        String result =
                "{\"ok\":true,\"found\":true"
                + ",\"text\":\"" + esc(n.getText()) + "\""
                + ",\"description\":\"" + esc(n.getContentDescription()) + "\""
                + ",\"hint\":\"" + esc(n.getHintText()) + "\""
                + ",\"id\":\"" + esc(n.getViewIdResourceName()) + "\""
                + ",\"class\":\"" + esc(n.getClassName()) + "\""
                + ",\"editable\":" + n.isEditable()
                + ",\"clickable\":" + n.isClickable()
                + ",\"bounds\":\"" + esc(b.toShortString()) + "\"}";

        n.recycle();
        r.recycle();
        return result;
    }

    public static String clickInfo(String query) {
        AccessibilityNodeInfo r = root();
        if (r == null) {
            return "{\"ok\":false,\"error\":\"accessibility service is not connected\"}";
        }

        AccessibilityNodeInfo n = find(
                r,
                query == null ? "" : query.toLowerCase()
        );

        if (n == null) {
            r.recycle();
            return "{\"ok\":false,\"found\":false,\"clicked\":false}";
        }

        boolean clicked = clickNode(n);

        String result =
                "{\"ok\":true,\"found\":true,\"clicked\":" + clicked
                + ",\"text\":\"" + esc(n.getText()) + "\""
                + ",\"description\":\"" + esc(n.getContentDescription()) + "\""
                + ",\"class\":\"" + esc(n.getClassName()) + "\"}";

        n.recycle();
        r.recycle();
        return result;
    }

    private static boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) return false;

        if (n.isClickable() &&
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }

        AccessibilityNodeInfo p = n.getParent();
        if (p != null) {
            boolean ok = clickNode(p);
            p.recycle();
            if (ok) return true;
        }

        return n.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
               n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    public static String typeInfo(String field, String value) {
        AccessibilityNodeInfo r = root();
        if (r == null) {
            return "{\"ok\":false,\"error\":\"accessibility service is not connected\"}";
        }

        AccessibilityNodeInfo n = findEditable(
                r,
                field == null ? "" : field.toLowerCase()
        );

        if (n == null) {
            r.recycle();
            return "{\"ok\":false,\"found\":false,\"typed\":false}";
        }

        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value == null ? "" : value
        );

        boolean typed = n.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                args
        );

        String result =
                "{\"ok\":true,\"found\":true,\"typed\":" + typed
                + ",\"text\":\"" + esc(n.getText()) + "\""
                + ",\"hint\":\"" + esc(n.getHintText()) + "\""
                + ",\"class\":\"" + esc(n.getClassName()) + "\"}";

        n.recycle();
        r.recycle();
        return result;
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
        if (n == null) return null;

        String query = q == null ? "" : q.toLowerCase();

        String text = n.getText() == null ? "" : n.getText().toString().toLowerCase();
        String desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString().toLowerCase();
        String hint = n.getHintText() == null ? "" : n.getHintText().toString().toLowerCase();
        String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().toLowerCase();

        boolean exact =
                text.equals(query) ||
                desc.equals(query) ||
                hint.equals(query) ||
                id.equals(query);

        if (exact) return n;

        AccessibilityNodeInfo clickableMatch = null;

        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            AccessibilityNodeInfo hit = c == null ? null : find(c, query);

            if (hit != null) {
                if (hit.isClickable() || hit.isFocusable()) {
                    if (clickableMatch != null && clickableMatch != hit) clickableMatch.recycle();
                    clickableMatch = hit;
                    if (c != hit) c.recycle();
                    continue;
                }

                if (clickableMatch == null) {
                    clickableMatch = hit;
                } else if (c != hit) {
                    hit.recycle();
                }

                if (c != hit) c.recycle();
                continue;
            }

            if (c != null) c.recycle();
        }

        return clickableMatch;
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
