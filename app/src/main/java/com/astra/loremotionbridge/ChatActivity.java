package com.astra.loremotionbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatActivity extends Activity {
    private static int BG = Color.rgb(8, 11, 24);
    private static int PANEL = Color.rgb(20, 27, 49);
    private static int LILAC = Color.rgb(229, 199, 255);
    private static int CYAN = Color.rgb(101, 221, 245);
    private static int WHITE = Color.rgb(234, 241, 255);
    private static final int PICK_WALLPAPER = 18768;
    private static final int PICK_MODEL = 18767;
    private static final int PICK_MOTION_REFERENCE = 18769;

    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();
    private LinearLayout messages;
    private ScrollView messageScroll;
    private EditText input;
    private TextView modelStatus;
    private Button installButton;
    private JSONArray history;
    private volatile boolean busy = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 18766);
        }
        applySavedTheme();
        history = loadHistory();
        buildScreen();
        if (history.length() == 0) {
            addMessage("Привет. Я ASTRA. Подключи OpenRouter один раз — скачивать модель на телефон не нужно. ✦", false, true);
        } else {
            for (int i = 0; i < history.length(); i++) {
                JSONObject m = history.optJSONObject(i);
                if (m != null) addMessage(m.optString("text"), m.optBoolean("user"), false);
            }
        }
        updateProviderStatus();
    }

    private void buildScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(16), dp(12), dp(16), dp(8));

        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setFillViewport(true);
        rootScroll.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, dp(8));
        rootScroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        screen.addView(rootScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = label("✦", 27, CYAN, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(Color.rgb(54, 43, 83), LILAC, 16));
        header.addView(mark, new LinearLayout.LayoutParams(dp(48), dp(48)));
        // Minimal header: keep the ASTRA symbol, remove redundant title/provider/status labels.
        root.addView(header);

        Button appearance = smallButton("✧  ОБОИ И ЦВЕТОВАЯ СХЕМА", LILAC);
        appearance.setOnClickListener(v -> showAppearanceMenu());
        LinearLayout.LayoutParams appearanceParams = new LinearLayout.LayoutParams(-1, dp(44));
        appearanceParams.topMargin = dp(12);
        appearanceParams.bottomMargin = dp(8);
        root.addView(appearance, appearanceParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(13), 0, dp(8));
        Button browser = smallButton("◉ ASTRA BROWSER", CYAN);
        browser.setOnClickListener(v -> launchBrowser());
        actions.addView(browser, new LinearLayout.LayoutParams(0, dp(43), 1));
        Button monitor = smallButton("BRIDGE", LILAC);
        monitor.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, dp(43), 1);
        mp.leftMargin = dp(8);
        actions.addView(monitor, mp);
        root.addView(actions);

        installButton = smallButton("＋  ПОДКЛЮЧИТЬ OPENROUTER · API KEY", CYAN);
        installButton.setOnClickListener(v -> showOpenRouterSetup());
        root.addView(installButton, new LinearLayout.LayoutParams(-1, dp(44)));

        modelStatus = label("Нужен API-ключ OpenRouter", 10, Color.rgb(160, 181, 211), false);
        modelStatus.setPadding(dp(3), dp(8), dp(3), dp(8));
        root.addView(modelStatus);

        Button gifStudio = smallButton("✦  GIF STUDIO · СОБРАТЬ ИЗ КАДРОВ", LILAC);
        gifStudio.setOnClickListener(v -> startActivity(new Intent(this, GifStudioActivity.class)));
        LinearLayout.LayoutParams gifParams = new LinearLayout.LayoutParams(-1, dp(44));
        gifParams.bottomMargin = dp(8);
        root.addView(gifStudio, gifParams);

        Button motionButton = smallButton("✦  ONE CLICK MOTION · ИЗОБРАЖЕНИЕ В ВИДЕО", CYAN);
        motionButton.setOnClickListener(v -> chooseMotionReference());
        LinearLayout.LayoutParams motionParams = new LinearLayout.LayoutParams(-1, dp(44));
        motionParams.bottomMargin = dp(8);
        root.addView(motionButton, motionParams);

        Button radioButton = smallButton("♫  SOUNDPARK DEEP · RADIO", CYAN);
        radioButton.setOnClickListener(v -> startActivity(new Intent(this, RadioActivity.class)));
        LinearLayout.LayoutParams radioParams = new LinearLayout.LayoutParams(-1, dp(40));
        radioParams.bottomMargin = dp(10);
        root.addView(radioButton, radioParams);

        LinearLayout chatPanel = new LinearLayout(this);
        chatPanel.setOrientation(LinearLayout.VERTICAL);
        chatPanel.setPadding(dp(13), dp(13), dp(13), dp(10));
        chatPanel.setBackground(round(Color.rgb(20, 23, 45), Color.rgb(65, 103, 140), 22));
        chatPanel.addView(label("ПЕРЕПИСКА  ·  ASTRA SESSION", 10, CYAN, true));
        messageScroll = new ScrollView(this);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messageScroll.addView(messages, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(360));
        sp.topMargin = dp(12);
        chatPanel.addView(messageScroll, sp);
        root.addView(chatPanel);

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
        composerParams.topMargin = dp(8);
        screen.addView(composer, composerParams);

        FrameLayout frame = new FrameLayout(this);
        ImageView wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        File wallpaperFile = new File(getFilesDir(), "astra_chat_wallpaper.jpg");
        if (wallpaperFile.exists()) {
            Bitmap bitmap = BitmapFactory.decodeFile(wallpaperFile.getAbsolutePath());
            if (bitmap != null) wallpaper.setImageBitmap(bitmap);
        }
        frame.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
    }


    private void showAppearanceMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Внешний вид ASTRA")
                .setItems(new String[]{"Выбрать обои из Галереи", "Тема: фиолетовая", "Тема: бирюзовая", "Тема: синяя"}, (d, which) -> {
                    if (which == 0) {
                        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                        pick.setType("image/*");
                        pick.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(Intent.createChooser(pick, "Выбери обои ASTRA"), PICK_WALLPAPER);
                    } else {
                        getSharedPreferences("astra_chat", MODE_PRIVATE).edit()
                                .putInt("theme", which).apply();
                        recreate();
                    }
                }).show();
    }

    private void applySavedTheme() {
        int theme = getSharedPreferences("astra_chat", MODE_PRIVATE).getInt("theme", 1);
        if (theme == 2) {
            BG = Color.rgb(5, 18, 28); PANEL = Color.rgb(10, 31, 45);
            LILAC = Color.rgb(111, 225, 222); CYAN = Color.rgb(88, 197, 255);
            WHITE = Color.rgb(232, 249, 255);
        } else if (theme == 3) {
            BG = Color.rgb(5, 12, 29); PANEL = Color.rgb(15, 25, 49);
            LILAC = Color.rgb(157, 190, 255); CYAN = Color.rgb(91, 160, 255);
            WHITE = Color.rgb(237, 243, 255);
        } else {
            BG = Color.rgb(8, 11, 24); PANEL = Color.rgb(20, 27, 49);
            LILAC = Color.rgb(229, 199, 255); CYAN = Color.rgb(101, 221, 245);
            WHITE = Color.rgb(234, 241, 255);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_MOTION_REFERENCE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            processMotionReference(data.getData());
            return;
        }
        if (requestCode == PICK_WALLPAPER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try (InputStream in = getContentResolver().openInputStream(data.getData());
                 FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "astra_chat_wallpaper.jpg"))) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
                recreate();
            } catch (Exception e) {
                new AlertDialog.Builder(this).setMessage("Не удалось установить обои: " + e.getMessage()).setPositiveButton("OK", null).show();
            }
        }
    }

    private void chooseMotionReference() {
        Intent pick;
        if (Build.VERSION.SDK_INT >= 33) {
            pick = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
            pick.setType("image/*");
            pick.putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 1);
        } else {
            pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("image/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
        }
        startActivityForResult(pick, PICK_MOTION_REFERENCE);
    }

    private void processMotionReference(Uri uri) {
        addMessage("Выбрала референс для ASTRA One Click Motion.", true, true);
        addMessage("Подготавливаю контактный лист 5×4…", false, true);
        modelExecutor.execute(() -> {
            try {
                File frames = AstraContactSheetSplitter.split(this, uri);
                runOnUiThread(() -> addMessage(
                        "Подготовлено 20 упорядоченных кадров (576×896). Папка: " +
                        frames.getAbsolutePath() +
                        "
Следующий этап — подключение автономного интерполятора и сборка MP4.",
                        false, true));
            } catch (Exception e) {
                runOnUiThread(() -> addMessage("Не удалось обработать референс: " +
                        (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                        false, true));
            }
        });
    }

    private void updateProviderStatus() {
        boolean ready = OpenRouterClient.hasKey();
        modelStatus.setText(ready ? "● OPENROUTER ПОДКЛЮЧЁН · МОДЕЛЬ: FREE ROUTER"
                : "Вставь API-ключ OpenRouter один раз для подключения");
        installButton.setText(ready ? "✓ OPENROUTER ПОДКЛЮЧЁН · ИЗМЕНИТЬ КЛЮЧ" : "＋  ПОДКЛЮЧИТЬ OPENROUTER · API KEY");
    }

    private void showOpenRouterSetup() {
        EditText keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setHint("sk-or-v1-…");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setTextColor(WHITE);
        keyInput.setHintTextColor(Color.GRAY);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(keyInput, new LinearLayout.LayoutParams(-1, dp(54)));
        new AlertDialog.Builder(this)
                .setTitle("Подключить OpenRouter")
                .setMessage("Вставь API-ключ. Он будет сохранён зашифрованным на этом устройстве. По умолчанию используется бесплатный маршрутизатор openrouter/free.")
                .setView(box)
                .setPositiveButton("СОХРАНИТЬ", (dialog, which) -> {
                    try {
                        OpenRouterClient.saveKey(keyInput.getText().toString());
                        updateProviderStatus();
                        addMessage("OpenRouter подключён. Теперь можно общаться без загрузки GGUF-модели. ✦", false, true);
                    } catch (Exception e) {
                        modelStatus.setText("Ключ не сохранён: " + e.getMessage());
                    }
                })
                .setNeutralButton("УДАЛИТЬ КЛЮЧ", (dialog, which) -> {
                    OpenRouterClient.clearKey();
                    updateProviderStatus();
                })
                .setNegativeButton("ОТМЕНА", null)
                .show();
    }

    private void launchBrowser() {
        Intent service = new Intent(this, BrowserBridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        WebViewBrowserController.ensureHost(this);
    }

    private void sendMessage() {
        String value = input.getText().toString().trim();
        if (value.isEmpty() || busy) return;
        if (!OpenRouterClient.hasKey()) {
            addMessage("Сначала подключи OpenRouter: нажми кнопку API KEY и вставь ключ.", false, true);
            return;
        }
        addMessage(value, true, true);
        input.setText("");
        String localAnswer = AstraCore.handleLocal(value);
        if (localAnswer != null) {
            addMessage(localAnswer, false, true);
            modelStatus.setText("● ASTRA · ЛОКАЛЬНЫЙ ОТВЕТ");
            return;
        }
        busy = true;
        modelStatus.setText("ASTRA ОБРАЩАЕТСЯ К OPENROUTER…");
        modelExecutor.execute(() -> {
            String answer = OpenRouterClient.chat(value, history, "openrouter/free");
            runOnUiThread(() -> {
                addMessage(answer.isEmpty() ? "Модель не вернула ответ." : answer, false, true);
                busy = false;
                updateProviderStatus();
            });
        });
    }

    private JSONObject parse(String raw) {
        try { return new JSONObject(raw); }
        catch (Exception e) { return new JSONObject(); }
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

    @Override protected void onDestroy() {
        // Keep the native model alive for the process; do not unload on screen close.
        super.onDestroy();
    }
}
