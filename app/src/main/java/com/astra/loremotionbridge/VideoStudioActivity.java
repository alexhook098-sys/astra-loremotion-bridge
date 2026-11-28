package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;

public class VideoStudioActivity extends Activity implements MediaController.MediaPlayerControl {
    private static final int BG = Color.rgb(7, 8, 20);
    private static final int PANEL = Color.rgb(19, 18, 39);
    private static final int PANEL_2 = Color.rgb(27, 22, 52);
    private static final int PURPLE = Color.rgb(183, 113, 255);
    private static final int LILAC = Color.rgb(222, 194, 255);
    private static final int WHITE = Color.rgb(245, 241, 255);
    private static final int MUTED = Color.rgb(157, 148, 185);
    private static final int PICK_VIDEO = 8421;

    private TextureView videoView;
    private MediaPlayer mediaPlayer;
    private MediaController mediaController;
    private Surface videoSurface;
    private boolean prepared = false;
    private Button playPauseButton;
    private SeekBar seekBar;
    private TextView currentTimeLabel;
    private TextView durationLabel;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean userSeeking = false;
    private final Runnable progressUpdater = new Runnable() {
        @Override public void run() {
            if (prepared && mediaPlayer != null) {
                int duration = mediaPlayer.getDuration();
                int position = mediaPlayer.getCurrentPosition();
                if (!userSeeking && seekBar != null && duration > 0) {
                    seekBar.setMax(duration);
                    seekBar.setProgress(position);
                }
                if (currentTimeLabel != null) currentTimeLabel.setText(formatTime(position));
                if (durationLabel != null) durationLabel.setText(formatTime(duration));
                if (playPauseButton != null) playPauseButton.setText(mediaPlayer.isPlaying() ? "Ⅱ" : "▶");
                uiHandler.postDelayed(this, 250);
            }
        }
    };
    private FrameLayout previewCard;
    private TextView previewTitle;
    private TextView previewHint;
    private LinearLayout recentList;
    private LinearLayout emptyOverlay;
    private Uri currentUri;
    private final ArrayList<File> recentFiles = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        scanAstraExports();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("✦", 30, PURPLE, true);
        header.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(48)));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("ASTRA VIDEO", 23, WHITE, true);
        title.setLetterSpacing(0.025f);
        brand.addView(title);
        brand.addView(text("ПЛЕЕР  ·  МОНТАЖ  ·  MOTION LAB", 9, MUTED, true));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button back = pill("НАЗАД", LILAC);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(76), dp(40)));
        root.addView(header);

        TextView intro = text("Твоя творческая видеостудия", 14, MUTED, false);
        LinearLayout.LayoutParams ip = wrap();
        ip.topMargin = dp(5); ip.bottomMargin = dp(17);
        root.addView(intro, ip);

        TextView section = text("СЕЙЧАС В ПРОЕКТЕ", 10, PURPLE, true);
        section.setLetterSpacing(0.12f);
        root.addView(section, wrap());

        previewCard = new FrameLayout(this);
        GradientDrawable previewBg = gradient(PANEL_2, Color.rgb(75, 48, 119), 24);
        previewCard.setBackground(previewBg);
        // VideoView uses a SurfaceView on Android. Avoid clipping/elevation on its parent,
        // which can place the separate video surface behind the card on some devices.
        previewCard.setClipToOutline(false);
        previewCard.setElevation(0f);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, dp(280));
        vp.topMargin = dp(9);
        root.addView(previewCard, vp);

        videoView = new TextureView(this);
        videoView.setOpaque(false);
        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture texture, int w, int h) {
                if (currentUri != null) prepareVideo(currentUri);
            }
            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture texture, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture texture) {
                releasePlayer();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture texture) {}
        });
        previewCard.addView(videoView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // Native ASTRA playback controls, rendered above the TextureView.
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(10), dp(5), dp(10), dp(5));
        controls.setBackground(gradient(Color.argb(225, 10, 8, 24), PURPLE, 14));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM);
        cp.setMargins(dp(8), 0, dp(8), dp(8));
        previewCard.addView(controls, cp);

        playPauseButton = pill("▶", PURPLE);
        playPauseButton.setTextSize(17);
        playPauseButton.setOnClickListener(v -> togglePlayback());
        controls.addView(playPauseButton, new LinearLayout.LayoutParams(dp(44), dp(40)));

        currentTimeLabel = text("00:00", 10, WHITE, true);
        currentTimeLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ctp = new LinearLayout.LayoutParams(dp(42), -2);
        ctp.leftMargin = dp(6);
        controls.addView(currentTimeLabel, ctp);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(PURPLE));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(LILAC));
        seekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.GRAY));
        controls.addView(seekBar, new LinearLayout.LayoutParams(0, dp(36), 1));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && prepared && mediaPlayer != null && mediaPlayer.getDuration() > 0)
                    currentTimeLabel.setText(formatTime(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (prepared && mediaPlayer != null) mediaPlayer.seekTo(bar.getProgress());
                userSeeking = false;
            }
        });

        durationLabel = text("00:00", 10, WHITE, true);
        durationLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dtp = new LinearLayout.LayoutParams(dp(42), -2);
        dtp.leftMargin = dp(4);
        controls.addView(durationLabel, dtp);


        emptyOverlay = new LinearLayout(this);
        LinearLayout empty = emptyOverlay;
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(18), dp(20), dp(18));
        empty.setBackgroundColor(Color.TRANSPARENT);
        previewCard.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        TextView playIcon = text("▶", 42, LILAC, true);
        playIcon.setGravity(Gravity.CENTER);
        empty.addView(playIcon, wrap());
        previewTitle = text("ТВОЁ СЛЕДУЮЩЕЕ ВИДЕО", 14, WHITE, true);
        previewTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tpp = wrap(); tpp.topMargin = dp(12);
        empty.addView(previewTitle, tpp);
        previewHint = text("Выбери видео из памяти телефона", 11, MUTED, false);
        previewHint.setGravity(Gravity.CENTER);
        empty.addView(previewHint, wrap());

        Button open = pill("＋   ОТКРЫТЬ ВИДЕО", LILAC);
        open.setTextSize(14);
        open.setOnClickListener(v -> chooseVideo());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, dp(54));
        op.topMargin = dp(14); op.bottomMargin = dp(9);
        root.addView(open, op);

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.addView(quickCard("✂", "Монтаж", "Скоро"), weighted());
        quick.addView(quickCard("◈", "Цвет", "Скоро"), weightedWithGap());
        quick.addView(quickCard("♫", "Музыка", "Скоро"), weightedWithGap());
        root.addView(quick);

        LinearLayout recentHeader = new LinearLayout(this);
        recentHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView recentTitle = text("МОИ ПРОЕКТЫ", 17, WHITE, true);
        recentHeader.addView(recentTitle, new LinearLayout.LayoutParams(0, -2, 1));
        recentHeader.addView(text("ЛОКАЛЬНО", 9, PURPLE, true));
        LinearLayout.LayoutParams rh = wrap(); rh.topMargin = dp(25); rh.bottomMargin = dp(8);
        root.addView(recentHeader, rh);

        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.VERTICAL);
        recentList.setPadding(dp(13), dp(13), dp(13), dp(13));
        recentList.setBackground(gradient(PANEL, Color.rgb(49, 40, 78), 18));
        root.addView(recentList, wrap());

        TextView footer = text("ASTRA VIDEO  ·  ЛОКАЛЬНАЯ СТУДИЯ", 9, MUTED, true);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = wrap(); fp.topMargin = dp(18);
        root.addView(footer, fp);

        setContentView(scroll);
    }

    private void chooseVideo() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("video/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(pick, PICK_VIDEO); }
        catch (Exception e) { Toast.makeText(this, "Не удалось открыть выбор видео", Toast.LENGTH_SHORT).show(); }
    }

    @Override @Deprecated protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_VIDEO || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) {}
        play(uri, displayName(uri));
    }

    private void play(Uri uri, String name) {
        currentUri = uri;
        if (emptyOverlay != null) emptyOverlay.setVisibility(View.GONE);
        previewTitle.setText(name == null ? "ВИДЕО ВЫБРАНО" : name);
        previewHint.setText("Загрузка видео…");
        releasePlayer();
        if (videoView.isAvailable()) prepareVideo(uri);
    }

    private void prepareVideo(Uri uri) {
        releasePlayer();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            videoSurface = new Surface(videoView.getSurfaceTexture());
            mediaPlayer.setSurface(videoSurface);
            mediaPlayer.setOnPreparedListener(mp -> {
                prepared = true;
                previewTitle.setText("ВОСПРОИЗВЕДЕНИЕ");
                previewHint.setText(formatTime(mp.getDuration()) + "  ·  готово к просмотру");
                mediaController = null; // ASTRA draws its own controls over TextureView.
                if (seekBar != null) { seekBar.setMax(mp.getDuration()); seekBar.setProgress(0); }
                if (currentTimeLabel != null) currentTimeLabel.setText("00:00");
                if (durationLabel != null) durationLabel.setText(formatTime(mp.getDuration()));
                if (playPauseButton != null) playPauseButton.setText("Ⅱ");
                mp.start();
                uiHandler.removeCallbacks(progressUpdater);
                uiHandler.post(progressUpdater);
            });
            mediaPlayer.setOnCompletionListener(mp -> previewHint.setText("Просмотр завершён"));
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                prepared = false;
                previewHint.setText("Ошибка воспроизведения (" + what + "/" + extra + ")");
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            previewHint.setText("Не удалось открыть видео: " + e.getMessage());
            releasePlayer();
        }
    }

    private void releasePlayer() {
        prepared = false;
        if (mediaPlayer != null) {
            try { mediaPlayer.setOnPreparedListener(null); mediaPlayer.setOnCompletionListener(null); mediaPlayer.setOnErrorListener(null); mediaPlayer.reset(); mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        uiHandler.removeCallbacks(progressUpdater);
        if (playPauseButton != null) playPauseButton.setText("▶");
        if (seekBar != null) seekBar.setProgress(0);
        if (currentTimeLabel != null) currentTimeLabel.setText("00:00");
        if (durationLabel != null) durationLabel.setText("00:00");
        if (videoSurface != null) { try { videoSurface.release(); } catch (Exception ignored) {} videoSurface = null; }
    }

    private void togglePlayback() {
        if (!prepared || mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            playPauseButton.setText("▶");
        } else {
            mediaPlayer.start();
            playPauseButton.setText("Ⅱ");
            uiHandler.removeCallbacks(progressUpdater);
            uiHandler.post(progressUpdater);
        }
    }

    @Override public void start() { if (prepared && mediaPlayer != null) mediaPlayer.start(); }
    @Override public void pause() { if (prepared && mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause(); }
    @Override public int getDuration() { return prepared && mediaPlayer != null ? mediaPlayer.getDuration() : 0; }
    @Override public int getCurrentPosition() { return prepared && mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0; }
    @Override public void seekTo(int pos) { if (prepared && mediaPlayer != null) mediaPlayer.seekTo(pos); }
    @Override public boolean isPlaying() { return prepared && mediaPlayer != null && mediaPlayer.isPlaying(); }
    @Override public int getBufferPercentage() { return 0; }
    @Override public boolean canPause() { return true; }
    @Override public boolean canSeekBackward() { return true; }
    @Override public boolean canSeekForward() { return true; }
    @Override public int getAudioSessionId() { return mediaPlayer != null ? mediaPlayer.getAudioSessionId() : 0; }

    private String displayName(Uri uri) {
        String last = uri.getLastPathSegment();
        if (last == null) return "МОЁ ВИДЕО";
        int slash = last.lastIndexOf('/');
        return slash >= 0 ? last.substring(slash + 1) : last;
    }

    private void scanAstraExports() {
        File[] dirs = new File[]{
            getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
            new File(getExternalFilesDir(null) == null ? getFilesDir() : getExternalFilesDir(null), "ASTRA")
        };
        for (File dir : dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles((d, n) -> {
                String s = n.toLowerCase();
                return s.endsWith(".mp4") || s.endsWith(".m4v") || s.endsWith(".webm") || s.endsWith(".mkv");
            });
            if (files != null) for (File f : files) recentFiles.add(f);
        }
        recentFiles.sort((a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        recentList.removeAllViews();
        if (recentFiles.isEmpty()) {
            TextView empty = text("Здесь появятся видео, сохранённые самой ASTRA.\nЧтобы открыть любой ролик с телефона, нажми «Открыть видео».", 12, MUTED, false);
            empty.setLineSpacing(dp(4), 1f);
            recentList.addView(empty, wrap());
            return;
        }
        int count = Math.min(8, recentFiles.size());
        for (int i=0; i<count; i++) {
            File file = recentFiles.get(i);
            Button item = pill("▶   " + file.getName() + "     ·     " + formatBytes(file.length()), LILAC);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            item.setOnClickListener(v -> play(Uri.fromFile(file), file.getName()));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
            p.bottomMargin = dp(6);
            recentList.addView(item, p);
        }
    }

    private LinearLayout quickCard(String icon, String name, String state) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(5), dp(12), dp(5), dp(12));
        card.setBackground(gradient(PANEL, Color.rgb(54, 43, 82), 17));
        TextView i = text(icon, 23, PURPLE, true); i.setGravity(Gravity.CENTER);
        card.addView(i, wrap());
        TextView n = text(name, 11, WHITE, true); n.setGravity(Gravity.CENTER);
        card.addView(n, wrap());
        TextView s = text(state, 9, MUTED, false); s.setGravity(Gravity.CENTER);
        card.addView(s, wrap());
        return card;
    }

    private String formatTime(int ms) {
        int sec = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60);
    }
    private String formatBytes(long bytes) {
        return bytes >= 1024L*1024L ? String.format(java.util.Locale.US, "%.1f MB", bytes/1048576.0)
                : (bytes/1024L) + " KB";
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button pill(String value, int accent) {
        Button b = new Button(this); b.setText(value); b.setTextColor(WHITE); b.setTextSize(11);
        b.setAllCaps(false); b.setBackground(gradient(PANEL_2, accent, 16)); return b;
    }
    private GradientDrawable gradient(int color, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color, Color.rgb(12, 11, 27)});
        d.setCornerRadius(dp(radius)); d.setStroke(dp(1), stroke); return d;
    }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, dp(88), 1); }
    private LinearLayout.LayoutParams weightedWithGap() {
        LinearLayout.LayoutParams p = weighted(); p.leftMargin = dp(7); return p;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override protected void onPause() {
        if (prepared && mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        releasePlayer();
        videoView = null;
        super.onDestroy();
    }
}
