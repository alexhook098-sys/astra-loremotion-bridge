package com.astra.loremotionbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.content.Intent;
import android.content.Context;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatActivity extends Activity {
    private static volatile ChatActivity activeInstance;
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
    private FrameLayout workerHost;
    private BingBrowserPanel bingBrowserPanel;
    private ScrollView messageScroll;
    private EditText input;
    private TextView modelStatus;
    private TextView flowDownloadStatus;
    private Button installButton;
    private JSONArray history;
    private volatile boolean busy = false;
    private boolean chatResumed = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        activeInstance = this;
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 18766);
        }
        applySavedTheme();
        history = loadHistory();
        buildScreen();
        renderFlowDownloadProgress(getSharedPreferences("astra_chat", MODE_PRIVATE)
                .getString("flow_download_status", ""));
        if (history.length() == 0) {
            addMessage("Привет. Я ASTRA. Подключи OpenRouter один раз — скачивать модель на телефон не нужно. ✦", false, true);
        } else {
            for (int i = 0; i < history.length(); i++) {
                JSONObject m = history.optJSONObject(i);
                if (m != null) {
                    if ("perchance_image".equals(m.optString("type"))) {
                        addPerchanceImageMessage(Uri.parse(m.optString("uri", "")), false);
                    } else if ("bing_image".equals(m.optString("type"))) {
                        addBingImageMessage(Uri.parse(m.optString("uri", "")), false);
                    } else if ("flow_video".equals(m.optString("type"))) {
                        addVideoMessage(Uri.parse(m.optString("uri", "")), false);
                    } else if ("zsky_video".equals(m.optString("type"))) {
                        addZSkyVideoMessage(Uri.parse(m.optString("uri", "")), false);
                    } else {
                        addMessage(m.optString("text"), m.optBoolean("user"), false);
                    }
                }
            }
        }
        updateProviderStatus();
        consumePendingPerchanceImages();
        consumePendingBingImages();
        consumePendingFlowVideos();
        consumePendingZSkyVideos();
    }

    @Override protected void onResume() {
        super.onResume();
        chatResumed = true;
        if (bingBrowserPanel != null) bingBrowserPanel.onHostResume();
        consumePendingPerchanceImages();
        consumePendingBingImages();
        consumePendingFlowVideos();
        consumePendingZSkyVideos();
    }

    @Override protected void onPause() {
        chatResumed = false;
        if (bingBrowserPanel != null) bingBrowserPanel.onHostPause();
        super.onPause();
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

        Button perchance = smallButton("✦  PERCHANCE · AI IMAGE GENERATOR", LILAC);
        perchance.setOnClickListener(v -> startActivity(new Intent(this, PerchanceBrowserActivity.class)));
        LinearLayout.LayoutParams perchanceParams = new LinearLayout.LayoutParams(-1, dp(43));
        perchanceParams.bottomMargin = dp(8);
        root.addView(perchance, perchanceParams);

        Button bingLogin = smallButton("✦  BING IMAGE CREATOR · ВОЙТИ В АККАУНТ", CYAN);
        bingLogin.setOnClickListener(v -> openBingLogin());
        LinearLayout.LayoutParams bingLoginParams = new LinearLayout.LayoutParams(-1, dp(43));
        bingLoginParams.bottomMargin = dp(8);
        root.addView(bingLogin, bingLoginParams);

        Button googleFlow = smallButton("✦  GOOGLE FLOW · AI VIDEO GENERATOR", CYAN);
        googleFlow.setOnClickListener(v -> {
            Intent flowIntent = new Intent(this, FlowWebViewActivity.class);
            flowIntent.putExtra("url", FlowWebViewActivity.FLOW_PROJECT_URL);
            startActivity(flowIntent);
        });
        LinearLayout.LayoutParams flowParams = new LinearLayout.LayoutParams(-1, dp(43));
        flowParams.bottomMargin = dp(8);
        root.addView(googleFlow, flowParams);

        Button zskyStudio = smallButton("✦  ZSKY STUDIO · ВОЙТИ В АККАУНТ", LILAC);
        zskyStudio.setOnClickListener(v -> {
            // Open the authenticated studio for manual sign-in; never submit a prompt here.
            ZSkyWebViewActivity.start(this, ZSkyWebViewActivity.ZSKY_URL, false);
        });
        LinearLayout.LayoutParams zskyParams = new LinearLayout.LayoutParams(-1, dp(43));
        zskyParams.bottomMargin = dp(8);
        root.addView(zskyStudio, zskyParams);

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
        flowDownloadStatus = label("", 11, Color.rgb(174, 224, 255), false);
        flowDownloadStatus.setPadding(dp(2), dp(7), dp(2), dp(4));
        flowDownloadStatus.setVisibility(View.GONE);
        chatPanel.addView(flowDownloadStatus, new LinearLayout.LayoutParams(-1, -2));
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
        workerHost = new FrameLayout(this);
        workerHost.setAlpha(0f);
        workerHost.setClickable(false);
        workerHost.setFocusable(false);
        frame.addView(workerHost, new FrameLayout.LayoutParams(-1, -1));
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
        addMessage("Референс принят для ASTRA One Click Motion.", true, true);
        addMessage("Разбираю лист 5×4 и создаю плавное видео…", false, true);
        modelExecutor.execute(() -> {
            try {
                Uri video = AstraMotionEngine.createVideo(this, uri);
                runOnUiThread(() -> addVideoMessage(video));
            } catch (Exception e) {
                runOnUiThread(() -> addMessage("Не удалось создать видео: " +
                        (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                        false, true));
            }
        });
    }

    private void addVideoMessage(Uri video) {
        addVideoMessage(video, true);
    }

    private void addVideoMessage(Uri video, boolean save) {
        if (video == null || video.toString().isEmpty() || messages == null) return;
        String message = "✦ ASTRA FLOW: видео сохранено · Movies/ASTRA/Flow\\nНажми здесь, чтобы открыть MP4.";
        addMessage(message, false, false);
        int last = messages.getChildCount() - 1;
        if (last >= 0) {
            View row = messages.getChildAt(last);
            if (row instanceof ViewGroup && ((ViewGroup) row).getChildCount() > 0) {
                View bubble = ((ViewGroup) row).getChildAt(0);
                bubble.setOnClickListener(v -> {
                    Intent open = new Intent(Intent.ACTION_VIEW);
                    open.setDataAndType(video, "video/mp4");
                    open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(open); }
                    catch (Exception e) {
                        addMessage("Не найдено приложение для просмотра MP4.", false, true);
                    }
                });
            }
        }
        if (save) {
            try {
                history.put(new JSONObject()
                        .put("type", "flow_video")
                        .put("text", message)
                        .put("uri", video.toString())
                        .put("user", false));
                getSharedPreferences("astra_chat", MODE_PRIVATE).edit()
                        .putString("messages", history.toString()).apply();
            } catch (Exception e) {
                android.util.Log.e("ASTRA-FLOW-CHAT", "Could not persist video message", e);
            }
        }
    }

    public static void onZSkyVideoReady(Context context, Uri video) {
        if (context == null || video == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("astra_chat", MODE_PRIVATE);
        JSONArray pending;
        try { pending = new JSONArray(prefs.getString("zsky_pending_videos", "[]")); }
        catch (Exception e) { pending = new JSONArray(); }
        try {
            pending.put(new JSONObject().put("uri", video.toString()));
            prefs.edit().putString("zsky_pending_videos", pending.toString()).apply();
        } catch (Exception ignored) {}
        ChatActivity activity = activeInstance;
        if (activity != null && activity.chatResumed) activity.runOnUiThread(activity::consumePendingZSkyVideos);
    }

    private void consumePendingZSkyVideos() {
        if (isFinishing() || messages == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences("astra_chat", MODE_PRIVATE);
        JSONArray pending;
        try { pending = new JSONArray(prefs.getString("zsky_pending_videos", "[]")); }
        catch (Exception e) { pending = new JSONArray(); }
        if (pending.length() == 0) return;
        prefs.edit().remove("zsky_pending_videos").apply();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject item = pending.optJSONObject(i);
            if (item == null) continue;
            String value = item.optString("uri", "");
            if (!value.isEmpty()) addZSkyVideoMessage(Uri.parse(value), true);
        }
    }

    private void addZSkyVideoMessage(Uri video, boolean save) {
        if (video == null || video.toString().isEmpty() || messages == null) return;
        String message = "✦ ASTRA ZSKY: видео сохранено · Movies/ASTRA/ZSky\\nНажми здесь, чтобы открыть MP4.";
        addMessage(message, false, false);
        int last = messages.getChildCount() - 1;
        if (last >= 0) {
            View row = messages.getChildAt(last);
            if (row instanceof ViewGroup && ((ViewGroup) row).getChildCount() > 0) {
                View bubble = ((ViewGroup) row).getChildAt(0);
                bubble.setOnClickListener(v -> {
                    Intent open = new Intent(Intent.ACTION_VIEW);
                    open.setDataAndType(video, "video/mp4");
                    open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(open); }
                    catch (Exception e) { addMessage("Не найдено приложение для просмотра MP4.", false, true); }
                });
            }
        }
        if (save) {
            try {
                history.put(new JSONObject().put("type", "zsky_video").put("text", message)
                        .put("uri", video.toString()).put("user", false));
                getSharedPreferences("astra_chat", MODE_PRIVATE).edit()
                        .putString("messages", history.toString()).apply();
            } catch (Exception e) { android.util.Log.e("ASTRA-ZSKY-CHAT", "Could not persist video message", e); }
        }
    }

    // Backward-compatible entry point used by the existing Browser bridge.
    public static void onPerchanceImageReady(Uri image) {
        ChatActivity activity = activeInstance;
        if (activity != null) onPerchanceImageReady(activity, image);
    }

    public static void onPerchanceImageReady(Context context, Uri image) {
        if (context == null || image == null) return;
        // Persist delivery before notifying the chat. The chat may have been destroyed in background.
        android.content.SharedPreferences prefs = context.getSharedPreferences("astra_chat", MODE_PRIVATE);
        JSONArray pending;
        try {
            String raw = prefs.getString("perchance_pending", "[]");
            pending = new JSONArray(raw);
        } catch (Exception e) { pending = new JSONArray(); }
        try {
            pending.put(new JSONObject().put("uri", image.toString()));
            prefs.edit().putString("perchance_pending", pending.toString()).apply();
        } catch (Exception ignored) {}
        ChatActivity activity = activeInstance;
        if (activity != null) activity.runOnUiThread(activity::consumePendingPerchanceImages);
    }


    public static void onFlowDownloadProgress(Context context, String message) {
        if (context == null || message == null) return;
        context.getSharedPreferences("astra_chat", MODE_PRIVATE).edit()
                .putString("flow_download_status", message).apply();
        ChatActivity activity = activeInstance;
        if (activity != null) {
            activity.runOnUiThread(() -> activity.renderFlowDownloadProgress(message));
        }
    }

    private void renderFlowDownloadProgress(String message) {
        if (flowDownloadStatus == null) return;
        if (message == null || message.trim().isEmpty()) {
            flowDownloadStatus.setVisibility(View.GONE);
            return;
        }
        flowDownloadStatus.setText("⬇  FLOW · " + message);
        flowDownloadStatus.setVisibility(View.VISIBLE);
    }

    // Persist Flow video delivery while the chat screen is in the background.
    public static void onFlowVideoReady(Context context, Uri video) {
        if (context == null || video == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("astra_chat", MODE_PRIVATE);
        JSONArray pending;
        try { pending = new JSONArray(prefs.getString("flow_pending_videos", "[]")); }
        catch (Exception e) { pending = new JSONArray(); }
        try {
            pending.put(new JSONObject().put("uri", video.toString()));
            prefs.edit().putString("flow_pending_videos", pending.toString()).apply();
        } catch (Exception ignored) {}
        ChatActivity activity = activeInstance;
        if (activity != null && activity.chatResumed) {
            activity.runOnUiThread(() -> {
                if (activity.chatResumed) activity.consumePendingFlowVideos();
            });
        }
    }

    private void consumePendingFlowVideos() {
        if (isFinishing() || messages == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences("astra_chat", MODE_PRIVATE);
        JSONArray pending;
        try { pending = new JSONArray(prefs.getString("flow_pending_videos", "[]")); }
        catch (Exception e) { pending = new JSONArray(); }
        if (pending.length() == 0) return;
        prefs.edit().remove("flow_pending_videos").apply();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject item = pending.optJSONObject(i);
            if (item == null) continue;
            String value = item.optString("uri", "");
            if (!value.isEmpty()) addVideoMessage(Uri.parse(value), true);
        }
    }

    public static void onBingPromptSubmitted(String prompt) {
        ChatActivity activity = activeInstance;
        if (activity != null) activity.runOnUiThread(() -> {
            if (!activity.isFinishing()) activity.addMessage("✦ Кнопка Create нажата. Проверяю, появился ли новый результат…", false, true);
        });
    }

    public static void onBingPromptFailed(String reason) {
        ChatActivity activity = activeInstance;
        if (activity != null) activity.runOnUiThread(() -> {
            if (!activity.isFinishing()) activity.addMessage("✦ Bing не подтвердил отправку: " + (reason == null ? "элементы страницы не найдены" : reason) + ". Генерация не считается запущенной.", false, true);
        });
    }

    public static void onBingStatus(Context context, String message) {
        ChatActivity activity = activeInstance;
        if (activity == null || message == null || !activity.chatResumed) return;
        activity.runOnUiThread(() -> {
            if (activity.chatResumed && activity.modelStatus != null)
                activity.modelStatus.setText("● ASTRA · BING: " + message);
        });
    }

    public static void onBingImageReady(Context context, Uri image) {
        if (context == null || image == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("astra_chat", MODE_PRIVATE);
        JSONArray pending;
        try { pending = new JSONArray(prefs.getString("bing_pending", "[]")); }
        catch (Exception e) { pending = new JSONArray(); }
        try {
            pending.put(new JSONObject().put("uri", image.toString()));
            prefs.edit().putString("bing_pending", pending.toString()).apply();
        } catch (Exception ignored) {}
        ChatActivity activity = activeInstance;
        if (activity != null) activity.runOnUiThread(activity::consumePendingBingImages);
    }

    private void consumePendingBingImages() {
        if (isFinishing() || messages == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences("astra_chat", MODE_PRIVATE);
        JSONArray pending;
        try { pending = new JSONArray(prefs.getString("bing_pending", "[]")); }
        catch (Exception e) { pending = new JSONArray(); }
        if (pending.length() == 0) return;
        prefs.edit().remove("bing_pending").apply();
        for (int i=0;i<pending.length();i++) {
            JSONObject item = pending.optJSONObject(i);
            if (item == null) continue;
            String value = item.optString("uri", "");
            if (!value.isEmpty()) addBingImageMessage(Uri.parse(value), true);
        }
    }

    private void addBingImageMessage(Uri image, boolean save) {
        addGeneratedImageMessage(image, save, "bing_image",
                "✦ BING IMAGE CREATOR · сохранено в Pictures/ASTRA/Bing");
    }

    private void addGeneratedImageMessage(Uri image, boolean save, String type, String captionText) {
        if (image == null || image.toString().isEmpty() || messages == null) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(9), dp(10), dp(10));
        card.setBackground(round(Color.rgb(30, 42, 65), Color.rgb(57, 91, 119), 17));
        TextView caption = label(captionText, 13, WHITE, false);
        caption.setPadding(dp(3), dp(2), dp(3), dp(8));
        card.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight(dp(280));
        preview.setBackground(round(Color.rgb(16, 22, 38), Color.rgb(57, 91, 119), 12));
        preview.setImageURI(image);
        card.addView(preview, new LinearLayout.LayoutParams(-1, dp(280)));
        card.setOnClickListener(v -> {
            Intent open = new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(image, "image/*");
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(open); }
            catch (Exception e) { addMessage("Не удалось открыть изображение из Галереи.", false, true); }
        });
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.LEFT);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(2), dp(5), dp(2), dp(7));
        row.addView(card, params);
        messages.addView(row);
        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
        if (save) {
            try {
                history.put(new JSONObject().put("type", type).put("text", captionText)
                        .put("uri", image.toString()).put("user", false));
                getSharedPreferences("astra_chat", MODE_PRIVATE).edit()
                        .putString("messages", history.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    private void consumePendingPerchanceImages() {
        if (isFinishing() || messages == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences("astra_chat", MODE_PRIVATE);
        JSONArray pending;
        try { pending = new JSONArray(prefs.getString("perchance_pending", "[]")); }
        catch (Exception e) { pending = new JSONArray(); }
        if (pending.length() == 0) return;
        prefs.edit().remove("perchance_pending").apply();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject item = pending.optJSONObject(i);
            if (item == null) continue;
            Uri image = Uri.parse(item.optString("uri", ""));
            if (image.toString().isEmpty()) continue;
            addPerchanceImageMessage(image, true);
        }
    }

    private void addPerchanceImageMessage(Uri image, boolean save) {
        if (image == null || image.toString().isEmpty()) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(9), dp(10), dp(10));
        card.setBackground(round(Color.rgb(30, 42, 65), Color.rgb(57, 91, 119), 17));

        TextView caption = label("✦ PERCHANCE · изображение сохранено в Pictures/ASTRA/Perchance", 13, WHITE, false);
        caption.setPadding(dp(3), dp(2), dp(3), dp(8));
        card.addView(caption, new LinearLayout.LayoutParams(-1, -2));

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight(dp(280));
        preview.setBackground(round(Color.rgb(16, 22, 38), Color.rgb(57, 91, 119), 12));
        try {
            preview.setImageURI(image);
        } catch (Exception e) {
            preview.setImageDrawable(null);
        }
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1, dp(280));
        card.addView(preview, imageParams);
        card.setOnClickListener(v -> {
            Intent open = new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(image, "image/*");
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(open); }
            catch (Exception e) {
                addMessage("Не удалось открыть изображение из Галереи.", false, true);
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.LEFT);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(dp(2), dp(5), dp(2), dp(7));
        row.addView(card, cardParams);
        messages.addView(row);
        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));

        if (save) {
            try {
                history.put(new JSONObject()
                        .put("type", "perchance_image")
                        .put("text", "PERCHANCE · изображение сохранено")
                        .put("uri", image.toString())
                        .put("user", false));
                getSharedPreferences("astra_chat", MODE_PRIVATE).edit()
                        .putString("messages", history.toString()).apply();
            } catch (Exception ignored) {}
        }
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

    private void openBingLogin() {
        Intent intent = new Intent(this, WebViewBrowserActivity.class);
        intent.putExtra("url", "https://www.bing.com/images/create");
        intent.putExtra("background", false);
        startActivity(intent);
    }

    private void startBingGeneration(String prompt) {
        if (prompt == null || prompt.trim().isEmpty() || isFinishing()) return;
        addMessage("✦ ASTRA · Bing Image Creator: " + prompt.trim(), false, true);
        if (bingBrowserPanel == null) {
            bingBrowserPanel = new BingBrowserPanel(this, prompt.trim());
            if (workerHost != null) workerHost.addView(bingBrowserPanel,
                    new FrameLayout.LayoutParams(-1, -1));
        } else {
            bingBrowserPanel.submitPrompt(prompt.trim());
        }
        bingBrowserPanel.onHostResume();
        modelStatus.setText("● ASTRA · BING ПОДКЛЮЧАЕТСЯ");
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
        String zskyPrompt = extractZSkyPrompt(value);
        if (isZSkyRequest(value) && (zskyPrompt == null || zskyPrompt.isEmpty())) {
            addMessage(value, true, true);
            input.setText("");
            addMessage("Напиши описание видео после ZSky, например: «Астра сделай видео ZSky: волшебница идёт по городу под дождём».", false, true);
            return;
        }
        if (zskyPrompt != null && !zskyPrompt.isEmpty()) {
            addMessage(value, true, true);
            input.setText("");
            addMessage("✦ ASTRA · открываю ZSky, запускаю генерацию в фоне…", false, true);
            ZSkyWebViewActivity.start(this, ZSkyWebViewActivity.ZSKY_URL, zskyPrompt, true);
            modelStatus.setText("● ASTRA · ZSKY РАБОТАЕТ В ФОНЕ");
            return;
        }
        String flowPrompt = extractFlowPrompt(value);
        if (flowPrompt != null && !flowPrompt.isEmpty()) {
            addMessage(value, true, true);
            input.setText("");
            addMessage("✦ ASTRA · передаю запрос в Google Flow в фоне…", false, true);
            Intent generation = new Intent(this, FlowWebViewActivity.class);
            generation.putExtra("url", FlowWebViewActivity.FLOW_PROJECT_URL);
            generation.putExtra(FlowWebViewActivity.EXTRA_AUTOMATE, true);
            generation.putExtra(FlowWebViewActivity.EXTRA_PROMPT, flowPrompt);
            generation.putExtra("background", true);
            startActivity(generation);
            modelStatus.setText("● ASTRA · GOOGLE FLOW РАБОТАЕТ В ФОНЕ");
            return;
        }
        String bingPrompt = extractBingPrompt(value);
        if (isBingRequest(value) && (bingPrompt == null || bingPrompt.isEmpty())) {
            addMessage(value, true, true);
            input.setText("");
            addMessage("Укажи описание после команды Bing, например: «Bing: футуристический город на берегу моря на закате».", false, true);
            return;
        }
        if (bingPrompt != null && !bingPrompt.isEmpty()) {
            addMessage(value, true, true);
            input.setText("");
            startBingGeneration(bingPrompt);
            return;
        }
        String perchancePrompt = extractPerchancePrompt(value);
        if (perchancePrompt != null && !perchancePrompt.isEmpty()) {
            addMessage(value, true, true);
            input.setText("");
            addMessage("✦ ASTRA · открываю Perchance в фоне и запускаю генерацию…", false, true);
            Intent generation = new Intent(this, PerchanceBrowserActivity.class);
            generation.putExtra(PerchanceBrowserActivity.EXTRA_AUTOMATE, true);
            generation.putExtra(PerchanceBrowserActivity.EXTRA_PROMPT, perchancePrompt);
            startActivity(generation);
            modelStatus.setText("● ASTRA · PERCHANCE РАБОТАЕТ В ФОНЕ");
            return;
        }
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

    private boolean isZSkyRequest(String text) {
        if (text == null) return false;
        String v = text.trim().toLowerCase(java.util.Locale.ROOT);
        return v.startsWith("zsky:") || v.matches("(?iu)^\\s*(?:астра[,!]?\\s*)?(?:сделай|создай|сгенерируй)\\s+(?:мне\\s+)?видео\\s+zsky\\b.*")
                || v.matches("(?iu)^\\s*(?:астра[,!]?\\s*)?zsky\\s+(?:сделай|создай|сгенерируй)\\s+(?:мне\\s+)?видео\\b.*");
    }

    private String extractZSkyPrompt(String text) {
        if (text == null) return null;
        String value = text.trim();
        // Explicit provider routing must run before the generic video/Flow parser.
        Pattern p = Pattern.compile("(?iu)^\\s*(?:астра[,!]?\\s*)?(?:(?:сделай|создай|сгенерируй)\\s+(?:мне\\s+)?видео\\s+zsky|zsky\\s*:)\\s*[:—-]?\\s*(.*)$");
        Matcher m = p.matcher(value);
        if (m.matches()) return m.group(1).trim();
        Pattern reverse = Pattern.compile("(?iu)^\\s*(?:астра[,!]?\\s*)?zsky\\s+(?:сделай|создай|сгенерируй)\\s+(?:мне\\s+)?видео\\s*[:—-]?\\s*(.*)$");
        Matcher r = reverse.matcher(value);
        return r.matches() ? r.group(1).trim() : null;
    }

    private String extractFlowPrompt(String text) {
        if (text == null) return null;
        String value = text.trim();
        String[] prefixes = {"flow:", "google flow:", "флоу:", "создай видео", "сгенерируй видео",
                "сделай видео", "анимируй", "generate video:", "create video:", "make a video:"};
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) {
                String prompt = value.substring(prefix.length()).replaceFirst("^[\\s:—-]+", "").trim();
                if (!prompt.isEmpty()) return prompt;
            }
        }
        Pattern ru = Pattern.compile("(?iu)^\\s*(?:пожалуйста[,!]?\\s*)?(?:создай|сгенерируй|сделай)\\s+(?:мне\\s+)?видео\\s*[:—-]?\\s*(.+)$");
        Matcher rm = ru.matcher(value);
        if (rm.matches()) return rm.group(1).trim();
        Pattern en = Pattern.compile("(?i)^\\s*(?:please\\s+)?(?:generate|create|make)\\s+(?:a\\s+)?video\\s*[:—-]?\\s*(.+)$");
        Matcher em = en.matcher(value);
        return em.matches() ? em.group(1).trim() : null;
    }

    private boolean isBingRequest(String text) {
        if (text == null) return false;
        String v = text.trim().toLowerCase(java.util.Locale.ROOT);
        return v.startsWith("bing:") || v.startsWith("bing image:")
                || v.startsWith("bing image creator:")
                || v.startsWith("bing create:");
    }

    private String extractBingPrompt(String text) {
        if (text == null) return null;
        String value = text.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        String[] prefixes = {"bing image creator:", "bing image:", "bing create:", "bing:"};
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) {
                return value.substring(prefix.length()).replaceFirst("^[\\s:—-]+", "").trim();
            }
        }
        return null;
    }

    private String extractPerchancePrompt(String text) {
        if (text == null) return null;
        String value = text.trim();
        String[] prefixes = {
                "perchance:", "перчанс:",
                "создай изображение", "сгенерируй изображение",
                "создай картинку", "сгенерируй картинку",
                "создай картину", "сгенерируй картину",
                "создай иллюстрацию", "сгенерируй иллюстрацию",
                "нарисуй", "сделай картинку", "сделай изображение",
                "generate image:", "create image:", "make an image:"
        };
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) {
                String prompt = value.substring(prefix.length()).replaceFirst("^[\\s:—-]+", "").trim();
                if (!prompt.isEmpty()) return prompt;
            }
        }
        Pattern ru = Pattern.compile("(?iu)^\\s*(?:пожалуйста[,!]?\\s*)?(?:создай|сгенерируй|сделай)\\s+(?:мне\\s+)?(?:изображение|картинку|картину|иллюстрацию|арт)\\s*[:—-]?\\s*(.+)$");
        Matcher rm = ru.matcher(value);
        if (rm.matches()) return rm.group(1).trim();
        Pattern en = Pattern.compile("(?i)^\\s*(?:please\\s+)?(?:generate|create|make)\\s+(?:an?\\s+)?(?:image|picture|illustration)\\s*[:—-]?\\s*(.+)$");
        Matcher em = en.matcher(value);
        if (em.matches()) return em.group(1).trim();
        return null;
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
        if (activeInstance == this) activeInstance = null;
        if (bingBrowserPanel != null) { bingBrowserPanel.dispose(); bingBrowserPanel = null; }
        // Keep the native model alive for the process; do not unload on screen close.
        super.onDestroy();
    }
}
