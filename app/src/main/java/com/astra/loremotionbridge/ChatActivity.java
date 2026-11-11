package com.astra.loremotionbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

public class ChatActivity extends Activity {
    private static final int BG = Color.rgb(8, 11, 24);
    private static final int PANEL = Color.rgb(20, 27, 49);
    private static final int LILAC = Color.rgb(229, 199, 255);
    private static final int CYAN = Color.rgb(101, 221, 245);
    private static final int WHITE = Color.rgb(234, 241, 255);
    private LinearLayout messages;
    private ScrollView messageScroll;
    private EditText input;
    private JSONArray history;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 18766);
        }
        history = loadHistory();
        buildScreen();
        if (history.length() == 0) {
            addMessage("Привет. Я ASTRA. Это твоё пространство для идей, проектов и инструментов. ✦", false, true);
        } else {
            for (int i = 0; i < history.length(); i++) {
                JSONObject m = history.optJSONObject(i);
                if (m != null) addMessage(m.optString("text"), m.optBoolean("user"), false);
            }
        }
    }

    private void buildScreen() {
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setFillViewport(true);
        rootScroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(12));
        rootScroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = new TextView(this);
        mark.setText("✦");
        mark.setTextSize(27);
        mark.setTextColor(CYAN);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(Color.rgb(54, 43, 83), LILAC, 16));
        header.addView(mark, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(12), 0, 0, 0);
        TextView title = label("ASTRA CHAT", 20, LILAC, true);
        TextView subtitle = label("ТВОЁ ПРОСТРАНСТВО ДЛЯ ИДЕЙ", 9, CYAN, false);
        names.addView(title);
        names.addView(subtitle);
        header.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        TextView local = label("● LOCAL", 10, Color.rgb(131, 217, 178), true);
        header.addView(local);
        root.addView(header);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(13), 0, dp(12));
        Button browser = smallButton("◉  ASTRA BROWSER", CYAN);
        browser.setOnClickListener(v -> launchBrowser());
        actions.addView(browser, new LinearLayout.LayoutParams(0, dp(43), 1));
        Button monitor = smallButton("BRIDGE MONITOR", LILAC);
        monitor.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, dp(43), 1);
        mp.leftMargin = dp(8);
        actions.addView(monitor, mp);
        root.addView(actions);

        LinearLayout chatPanel = new LinearLayout(this);
        chatPanel.setOrientation(LinearLayout.VERTICAL);
        chatPanel.setPadding(dp(13), dp(13), dp(13), dp(10));
        chatPanel.setBackground(round(Color.rgb(20, 23, 45), Color.rgb(65, 103, 140), 22));
        TextView section = label("ПЕРЕПИСКА                         ASTRA SESSION", 10, CYAN, true);
        chatPanel.addView(section);
        messageScroll = new ScrollView(this);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messageScroll.addView(messages, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1);
        sp.topMargin = dp(12);
        chatPanel.addView(messageScroll, sp);
        TextView notice = label("ЛОКАЛЬНЫЙ РЕЖИМ · AI-МОДЕЛЬ ПОКА НЕ ПОДКЛЮЧЕНА", 8, Color.rgb(137, 149, 174), false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(8);
        chatPanel.addView(notice, np);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1);
        root.addView(chatPanel, cp);

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(13), dp(5), dp(6), dp(5));
        composer.setBackground(round(PANEL, Color.rgb(56, 67, 98), 25));
        input = new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setTextColor(WHITE);
        input.setHintTextColor(Color.rgb(131, 144, 170));
        input.setHint("Напиши ASTRA…");
        input.setTextSize(14);
        input.setBackgroundColor(Color.TRANSPARENT);
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        Button send = smallButton("➤", LILAC);
        send.setOnClickListener(v -> sendMessage());
        composer.addView(send, new LinearLayout.LayoutParams(dp(52), dp(46)));
        LinearLayout.LayoutParams composerParams = new LinearLayout.LayoutParams(-1, -2);
        composerParams.topMargin = dp(12);
        root.addView(composer, composerParams);

        TextView footer = label("ASTRA  •  CREATIVE INTELLIGENCE", 8, Color.rgb(117, 130, 156), false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(8);
        root.addView(footer, fp);
        setContentView(rootScroll);
    }

    private void launchBrowser() {
        Intent service = new Intent(this, BrowserBridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        WebViewBrowserController.ensureHost(this);
    }

    private void sendMessage() {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) return;
        addMessage(value, true, true);
        input.setText("");
    }

    private void addMessage(String text, boolean user, boolean save) {
        TextView bubble = label(text, 14, WHITE, false);
        bubble.setPadding(dp(13), dp(11), dp(13), dp(11));
        bubble.setBackground(round(user ? Color.rgb(76, 51, 111) : Color.rgb(30, 42, 65),
                Color.rgb(57, 91, 119), 17));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(user ? Gravity.RIGHT : Gravity.LEFT);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(dp(2), dp(4), dp(2), dp(5));
        bubble.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(100));
        row.addView(bubble, bp);
        messages.addView(row);
        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
        if (save) {
            try {
                history.put(new JSONObject().put("text", text).put("user", user));
                getSharedPreferences("astra_chat", MODE_PRIVATE).edit().putString("messages", history.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    private JSONArray loadHistory() {
        try {
            String raw = getSharedPreferences("astra_chat", MODE_PRIVATE).getString("messages", null);
            return raw == null ? new JSONArray() : new JSONArray(raw);
        } catch (Exception e) { return new JSONArray(); }
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button smallButton(String text, int accent) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10);
        b.setTextColor(accent);
        b.setAllCaps(false);
        b.setBackground(round(Color.rgb(29, 34, 58), accent, 15));
        return b;
    }

    private GradientDrawable round(int color, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density + 0.5f);
    }
}
