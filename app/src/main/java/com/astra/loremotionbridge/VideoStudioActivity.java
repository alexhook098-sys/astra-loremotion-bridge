package com.astra.loremotionbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.view.Surface;
import android.view.TextureView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import android.widget.Toast;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

public class VideoStudioActivity extends Activity implements MediaController.MediaPlayerControl {
    private static final int BG = Color.rgb(7, 8, 20);
    private static final int PANEL = Color.rgb(19, 18, 39);
    private static final int PANEL_2 = Color.rgb(27, 22, 52);
    private static final int PURPLE = Color.rgb(183, 113, 255);
    private static final int LILAC = Color.rgb(222, 194, 255);
    private static final int WHITE = Color.rgb(245, 241, 255);
    private static final int MUTED = Color.rgb(157, 148, 185);
    private static final int PICK_VIDEO = 8421;
    private static final int PICK_EXTRA_VIDEO = 8422;

    private TextureView videoView;
    private MediaPlayer mediaPlayer;
    private ExoPlayer timelinePlayer;
    private MediaController mediaController;
    private Surface videoSurface;
    private boolean prepared = false;
    private Button playPauseButton;
    private Button exportButton;
    private Button speedButton;
    private SeekBar trimStartBar;
    private SeekBar trimEndBar;
    private TextView trimRangeLabel;
    private int trimStartMs = 0;
    private int trimEndMs = 0;
    private int sourceDurationMs = 0;
    private final float[] playbackSpeeds = new float[]{1.0f, 1.25f, 1.5f, 2.0f, 0.75f, 0.5f};
    private int playbackSpeedIndex = 0;
    private Transformer activeTransformer;
    private File activeExportFile;
    private final ProgressHolder exportProgress = new ProgressHolder();
    private final Runnable exportProgressUpdater = new Runnable() {
        @Override public void run() {
            if (activeTransformer == null) return;
            int state = activeTransformer.getProgress(exportProgress);
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                previewHint.setText("Экспорт видео… " + exportProgress.progress + "%");
            }
            if (state != Transformer.PROGRESS_STATE_NOT_STARTED) uiHandler.postDelayed(this, 500);
        }
    };
    private SeekBar seekBar;
    private TextView currentTimeLabel;
    private TextView durationLabel;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean userSeeking = false;
    private final Runnable progressUpdater = new Runnable() {
        @Override public void run() {
            if ((prepared && mediaPlayer != null) || timelinePlayer != null) {
                int duration = timelinePlayer != null ? (int) Math.max(0, timelinePlayer.getDuration()) : mediaPlayer.getDuration();
                int position = timelinePlayer != null ? (int) timelinePlayer.getCurrentPosition() : mediaPlayer.getCurrentPosition();
                if (!userSeeking && seekBar != null && duration > 0) {
                    seekBar.setMax(duration);
                    seekBar.setProgress(position);
                }
                if (currentTimeLabel != null) currentTimeLabel.setText(formatTime(position));
                if (durationLabel != null) durationLabel.setText(formatTime(duration));
                if (playPauseButton != null) playPauseButton.setText((timelinePlayer != null ? timelinePlayer.isPlaying() : mediaPlayer.isPlaying()) ? "Ⅱ" : "▶");
                updateTimelinePlayhead();
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
    private final ArrayList<TimelineClip> timelineClips = new ArrayList<>();
    private LinearLayout timelineList;
    private HorizontalScrollView timelineScroller;
    private FrameLayout timelineFrame;
    private View timelinePlayhead;
    private TextView timelineSummary;
    private int selectedClipIndex = -1;
    private boolean timelinePreviewActive = false;
    private int previewTimelineIndex = -1;
    private static class TimelineClip { Uri uri; String name; int durationMs; int startMs; int endMs; TimelineClip(Uri u, String n, int d) { uri=u; name=n; durationMs=d; startMs=0; endMs=d; } }

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

        previewCard = new FrameLayout(this);
        GradientDrawable previewBg = gradient(PANEL_2, Color.rgb(75, 48, 119), 24);
        previewCard.setBackground(previewBg);
        // VideoView uses a SurfaceView on Android. Avoid clipping/elevation on its parent,
        // which can place the separate video surface behind the card on some devices.
        previewCard.setClipToOutline(false);
        previewCard.setElevation(0f);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, dp(330));
        vp.topMargin = dp(8);
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
                if (timelinePlayer != null) timelinePlayer.seekTo(bar.getProgress());
                else if (prepared && mediaPlayer != null) mediaPlayer.seekTo(bar.getProgress());
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

        exportButton = pill("↧   СОХРАНИТЬ КОПИЮ", PURPLE);
        exportButton.setTextSize(13);
        exportButton.setVisibility(View.GONE);
        exportButton.setOnClickListener(v -> exportCurrentVideo());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, dp(48));
        ep.bottomMargin = dp(9);
        root.addView(exportButton, ep);

        speedButton = pill("СКОРОСТЬ  1.0×", LILAC);
        speedButton.setOnClickListener(v -> cyclePlaybackSpeed());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(42));
        sp.bottomMargin = dp(9);
        root.addView(speedButton, sp);

        LinearLayout timelinePanel = new LinearLayout(this);
        timelinePanel.setOrientation(LinearLayout.VERTICAL);
        timelinePanel.setPadding(dp(13), dp(12), dp(13), dp(12));
        timelinePanel.setBackground(gradient(PANEL, Color.rgb(75, 48, 119), 17));
        timelinePanel.addView(text("МОНТАЖНАЯ ШКАЛА", 13, WHITE, true), wrap());
        timelineSummary = text("Фрагменты будут идти друг за другом в готовом видео", 10, MUTED, false);
        timelinePanel.addView(timelineSummary, wrap());
        timelineFrame = new FrameLayout(this);
        timelineScroller = new HorizontalScrollView(this);
        timelineScroller.setHorizontalScrollBarEnabled(false);
        timelineScroller.setFillViewport(false);
        timelineList = new LinearLayout(this);
        timelineList.setOrientation(LinearLayout.HORIZONTAL);
        timelineList.setGravity(Gravity.CENTER_VERTICAL);
        timelineScroller.addView(timelineList, new HorizontalScrollView.LayoutParams(-2, dp(112)));
        timelineFrame.addView(timelineScroller, new FrameLayout.LayoutParams(-1, dp(118)));
        timelinePlayhead = new View(this);
        timelinePlayhead.setBackgroundColor(Color.rgb(255, 224, 255));
        FrameLayout.LayoutParams playheadParams = new FrameLayout.LayoutParams(dp(2), dp(104), Gravity.TOP | Gravity.LEFT);
        playheadParams.topMargin = dp(3);
        timelineFrame.addView(timelinePlayhead, playheadParams);
        timelinePlayhead.setElevation(dp(6));
        LinearLayout.LayoutParams timelineScrollParams = new LinearLayout.LayoutParams(-1, dp(118));
        timelineScrollParams.topMargin = dp(8);
        timelinePanel.addView(timelineFrame, timelineScrollParams);
        Button splitClip = pill("✂  РАЗДЕЛИТЬ В ТОЧКЕ ПРОСМОТРА", PURPLE);
        splitClip.setOnClickListener(v -> splitSelectedClipAtPlayhead());
        LinearLayout.LayoutParams splitParams = new LinearLayout.LayoutParams(-1, dp(42));
        splitParams.topMargin = dp(7);
        timelinePanel.addView(splitClip, splitParams);
        Button addClip = pill("＋  ДОБАВИТЬ ФРАГМЕНТ", LILAC);
        addClip.setOnClickListener(v -> chooseExtraVideo());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(44));
        addParams.topMargin = dp(8);
        timelinePanel.addView(addClip, addParams);
        TextView transitionInfo = text("ПЕРЕХОДЫ: пока доступна склейка. Растворение и затемнение — следующий этап.", 10, MUTED, false);
        LinearLayout.LayoutParams transitionParams = wrap(); transitionParams.topMargin = dp(8);
        timelinePanel.addView(transitionInfo, transitionParams);
        LinearLayout.LayoutParams timelineParams = new LinearLayout.LayoutParams(-1, -2); timelineParams.bottomMargin = dp(10);
        root.addView(timelinePanel, timelineParams);
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
        if ((request != PICK_VIDEO && request != PICK_EXTRA_VIDEO) || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) {}
        if (request == PICK_VIDEO) { timelineClips.clear(); selectedClipIndex = -1; play(uri, displayName(uri)); addClipToTimeline(uri, displayName(uri)); } else { addClipToTimeline(uri, displayName(uri)); }
    }


    private void chooseExtraVideo() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("video/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(pick, PICK_EXTRA_VIDEO); }
        catch (Exception e) { Toast.makeText(this, "Не удалось открыть выбор видео", Toast.LENGTH_SHORT).show(); }
    }

    private void addClipToTimeline(Uri uri, String name) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        int duration = 0;
        try {
            retriever.setDataSource(this, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value != null) duration = Integer.parseInt(value);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось прочитать видео", Toast.LENGTH_LONG).show();
            return;
        } finally { try { retriever.release(); } catch (Exception ignored) {} }
        if (duration < 500) {
            Toast.makeText(this, "Видео слишком короткое или не поддерживается", Toast.LENGTH_LONG).show();
            return;
        }
        timelineClips.add(new TimelineClip(uri, name, duration));
        selectTimelineClip(timelineClips.size() - 1);
    }

    private void selectTimelineClip(int index) {
        if (index < 0 || index >= timelineClips.size()) return;
        timelinePreviewActive = false; previewTimelineIndex = -1;
        selectedClipIndex = index;
        TimelineClip clip = timelineClips.get(index);
        currentUri = clip.uri;
        sourceDurationMs = clip.durationMs;
        trimStartMs = clip.startMs;
        trimEndMs = clip.endMs;
        previewTitle.setText(clip.name);
        if (trimStartBar != null) { trimStartBar.setMax(sourceDurationMs); trimStartBar.setProgress(trimStartMs); }
        if (trimEndBar != null) { trimEndBar.setMax(sourceDurationMs); trimEndBar.setProgress(trimEndMs); }
        updateTrimLabel();
        if (exportButton != null) exportButton.setVisibility(View.VISIBLE);
        if (videoView != null && videoView.isAvailable()) prepareVideo(clip.uri);
        renderTimeline();
    }

    private void saveSelectedClipRange() {
        if (selectedClipIndex >= 0 && selectedClipIndex < timelineClips.size()) {
            TimelineClip clip = timelineClips.get(selectedClipIndex);
            clip.startMs = trimStartMs;
            clip.endMs = trimEndMs;
            renderTimeline();
        }
    }

    private void renderTimeline() {
        if (timelineList == null) return;
        timelineList.removeAllViews();
        int total = 0;
        for (int i = 0; i < timelineClips.size(); i++) {
            TimelineClip clip = timelineClips.get(i);
            total += Math.max(0, clip.endMs - clip.startMs);
            final int index = i;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(5), dp(5), dp(5), dp(4));
            card.setBackground(gradient(i == selectedClipIndex ? PANEL_2 : PANEL,
                    i == selectedClipIndex ? PURPLE : Color.rgb(65, 55, 91), 10));
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap frame = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, clip.uri);
                frame = retriever.getFrameAtTime(clip.startMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception ignored) {
            } finally { try { retriever.release(); } catch (Exception ignored) {} }
            if (frame != null) thumb.setImageBitmap(frame);
            else thumb.setBackgroundColor(Color.rgb(42, 36, 62));
            card.addView(thumb, new LinearLayout.LayoutParams(dp(126), dp(66)));
            TextView caption = text((index + 1) + "  ·  " + formatTime(clip.endMs - clip.startMs), 10,
                    i == selectedClipIndex ? LILAC : WHITE, true);
            caption.setGravity(Gravity.CENTER);
            card.addView(caption, new LinearLayout.LayoutParams(-1, dp(23)));
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.CENTER);
            TextView left = text("‹", 18, i > 0 ? LILAC : MUTED, true);
            TextView right = text("›", 18, i < timelineClips.size() - 1 ? LILAC : MUTED, true);
            left.setGravity(Gravity.CENTER); right.setGravity(Gravity.CENTER);
            left.setOnClickListener(v -> moveTimelineClip(index, -1));
            right.setOnClickListener(v -> moveTimelineClip(index, 1));
            actions.addView(left, new LinearLayout.LayoutParams(dp(38), dp(25)));
            actions.addView(text("↕", 11, MUTED, false), new LinearLayout.LayoutParams(dp(24), dp(25)));
            actions.addView(right, new LinearLayout.LayoutParams(dp(38), dp(25)));
            card.addView(actions);
            card.setOnClickListener(v -> selectTimelineClip(index));
            card.setOnLongClickListener(v -> {
                if (timelineClips.size() <= 1) return true;
                timelineClips.remove(index);
                selectedClipIndex = Math.min(index, timelineClips.size() - 1);
                selectTimelineClip(selectedClipIndex);
                Toast.makeText(this, "Фрагмент удалён", Toast.LENGTH_SHORT).show();
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(140), dp(112));
            params.rightMargin = dp(7);
            timelineList.addView(card, params);
        }
        if (timelineSummary != null) timelineSummary.setText(timelineClips.size() + " клипа · " + formatTime(total)
                + " · светлая линия — курсор разделения");
        updateTimelinePlayhead();
    }

    private void updateTimelinePlayhead() {
        if (timelinePlayhead == null || timelineScroller == null || timelineClips.isEmpty()) return;
        int index = selectedClipIndex;
        int position = 0;
        if (timelinePlayer != null && timelinePreviewActive) {
            index = timelinePlayer.getCurrentMediaItemIndex();
            position = (int) timelinePlayer.getCurrentPosition();
        } else if (prepared && mediaPlayer != null) {
            position = mediaPlayer.getCurrentPosition();
        } else if (index >= 0 && index < timelineClips.size()) {
            position = timelineClips.get(index).startMs;
        }
        if (index < 0 || index >= timelineClips.size()) return;
        TimelineClip clip = timelineClips.get(index);
        float fraction = Math.max(0f, Math.min(1f,
                (float) (position - clip.startMs) / Math.max(1, clip.endMs - clip.startMs)));
        float contentX = dp(147) * index + dp(140) * fraction;
        timelinePlayhead.setTranslationX(contentX - timelineScroller.getScrollX());
        timelinePlayhead.setVisibility(View.VISIBLE);
    }

    private void moveTimelineClip(int from, int delta) {
        int to = from + delta;
        if (from < 0 || from >= timelineClips.size() || to < 0 || to >= timelineClips.size()) return;
        TimelineClip clip = timelineClips.remove(from);
        timelineClips.add(to, clip);
        selectedClipIndex = to;
        releaseTimelinePlayer();
        selectTimelineClip(to);
    }

    private void play(Uri uri, String name) {
        timelinePreviewActive = false; previewTimelineIndex = -1;
        currentUri = uri;
        sourceDurationMs = 0;
        trimStartMs = 0;
        trimEndMs = 0;
        updateTrimLabel();
        if (exportButton != null) exportButton.setVisibility(View.VISIBLE);
        if (videoView != null && previewCard != null) {
            FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
            videoView.setLayoutParams(full);
        }
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
            mediaPlayer.setOnVideoSizeChangedListener((mp, videoWidth, videoHeight) -> fitVideoToPreview(videoWidth, videoHeight));
            mediaPlayer.setOnPreparedListener(mp -> {
                prepared = true;
                previewTitle.setText("ВОСПРОИЗВЕДЕНИЕ");
                previewHint.setText(formatTime(mp.getDuration()) + "  ·  готово к просмотру");
                mediaController = null; // ASTRA draws its own controls over TextureView.
                if (seekBar != null) { seekBar.setMax(mp.getDuration()); seekBar.setProgress(0); }
                if (currentTimeLabel != null) currentTimeLabel.setText("00:00");
                if (durationLabel != null) durationLabel.setText(formatTime(mp.getDuration()));
                sourceDurationMs = mp.getDuration();
                if (timelinePreviewActive && previewTimelineIndex >= 0 && previewTimelineIndex < timelineClips.size()) {
                    TimelineClip active = timelineClips.get(previewTimelineIndex); trimStartMs = active.startMs; trimEndMs = active.endMs;
                } else if (selectedClipIndex >= 0 && selectedClipIndex < timelineClips.size()) {
                    TimelineClip selected = timelineClips.get(selectedClipIndex); trimStartMs = selected.startMs; trimEndMs = selected.endMs;
                } else { trimStartMs = 0; trimEndMs = sourceDurationMs; }
                if (trimStartBar != null) { trimStartBar.setMax(sourceDurationMs); trimStartBar.setProgress(0); }
                if (trimEndBar != null) { trimEndBar.setMax(sourceDurationMs); trimEndBar.setProgress(sourceDurationMs); }
                updateTrimLabel();
                if (playPauseButton != null) playPauseButton.setText("Ⅱ");
                applyPlaybackSpeed();
                if (timelinePreviewActive && previewTimelineIndex >= 0 && previewTimelineIndex < timelineClips.size()) mp.seekTo(timelineClips.get(previewTimelineIndex).startMs);
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

    private void fitVideoToPreview(int videoWidth, int videoHeight) {
        if (videoView == null || previewCard == null || videoWidth <= 0 || videoHeight <= 0) return;
        int availableWidth = previewCard.getWidth();
        int availableHeight = previewCard.getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) return;

        float videoRatio = (float) videoWidth / (float) videoHeight;
        float frameRatio = (float) availableWidth / (float) availableHeight;
        int fittedWidth;
        int fittedHeight;
        if (videoRatio > frameRatio) {
            fittedWidth = availableWidth;
            fittedHeight = Math.round(availableWidth / videoRatio);
        } else {
            fittedHeight = availableHeight;
            fittedWidth = Math.round(availableHeight * videoRatio);
        }
        FrameLayout.LayoutParams fitted = new FrameLayout.LayoutParams(fittedWidth, fittedHeight, Gravity.CENTER);
        videoView.setLayoutParams(fitted);
    }

    private void releasePlayer() {
        releaseTimelinePlayer();
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


    private void exportCurrentVideo() {
        if (currentUri == null || activeTransformer != null) {
            if (activeTransformer != null) Toast.makeText(this, "Экспорт уже выполняется", Toast.LENGTH_SHORT).show();
            return;
        }
        File movies = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (movies == null) { Toast.makeText(this, "Нет папки для экспорта", Toast.LENGTH_LONG).show(); return; }
        if (!movies.exists() && !movies.mkdirs()) { Toast.makeText(this, "Не удалось создать папку экспорта", Toast.LENGTH_LONG).show(); return; }
        activeExportFile = new File(movies, "ASTRA_" + System.currentTimeMillis() + ".mp4");
        exportButton.setEnabled(false);
        exportButton.setText("ЭКСПОРТ…");
        previewHint.setText("Подготовка экспорта…");
        try {
            if (sourceDurationMs <= 0 || trimEndMs <= trimStartMs) throw new IllegalStateException("Выбери корректный фрагмент");
            MediaItem.ClippingConfiguration clipping = new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs)
                    .setEndPositionMs(trimEndMs)
                    .build();
            MediaItem clippedItem = new MediaItem.Builder().setUri(currentUri).setClippingConfiguration(clipping).build();
            EditedMediaItem edited = new EditedMediaItem.Builder(clippedItem).build();
            activeTransformer = new Transformer.Builder(this)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition composition, ExportResult result) {
                            uiHandler.removeCallbacks(exportProgressUpdater);
                            activeTransformer = null;
                            publishExportToGallery(activeExportFile);
                        }
                        @Override public void onError(Composition composition, ExportResult result, ExportException exception) {
                            uiHandler.removeCallbacks(exportProgressUpdater);
                            activeTransformer = null;
                            if (activeExportFile != null) activeExportFile.delete();
                            exportButton.setEnabled(true);
                            exportButton.setText("↧   СОХРАНИТЬ КОПИЮ");
                            previewHint.setText("Ошибка экспорта: " + exception.getErrorCodeName());
                            Toast.makeText(VideoStudioActivity.this, "Не удалось экспортировать видео", Toast.LENGTH_LONG).show();
                        }
                    }).build();
            if (timelineClips.size() > 1) {
                ArrayList<EditedMediaItem> items = new ArrayList<>();
                for (TimelineClip clip : timelineClips) {
                    MediaItem.ClippingConfiguration range = new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build();
                    MediaItem item = new MediaItem.Builder().setUri(clip.uri).setClippingConfiguration(range).build();
                    items.add(new EditedMediaItem.Builder(item).build());
                }
                EditedMediaItemSequence sequence = EditedMediaItemSequence.withAudioAndVideoFrom(items);
                Composition composition = new Composition.Builder(sequence).build();
                activeTransformer.start(composition, activeExportFile.getAbsolutePath());
            } else {
                activeTransformer.start(edited, activeExportFile.getAbsolutePath());
            }
            uiHandler.post(exportProgressUpdater);
        } catch (Exception e) {
            activeTransformer = null;
            if (activeExportFile != null) activeExportFile.delete();
            exportButton.setEnabled(true);
            exportButton.setText("↧   СОХРАНИТЬ КОПИЮ");
            previewHint.setText("Не удалось начать экспорт");
            Toast.makeText(this, "Ошибка запуска экспорта: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void publishExportToGallery(File file) {
        if (file == null || !file.exists() || file.length() == 0) { finishExportUi(false, "Файл экспорта не найден"); return; }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ASTRA");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }
            android.net.Uri destination = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new java.io.IOException("Не удалось создать запись в галерее");
            try (FileInputStream input = new FileInputStream(file); OutputStream output = getContentResolver().openOutputStream(destination)) {
                if (output == null) throw new java.io.IOException("Не удалось открыть файл галереи");
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            } catch (Exception copyError) {
                getContentResolver().delete(destination, null, null);
                throw copyError;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues(); ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(destination, ready, null, null);
            }
            file.delete();
            finishExportUi(true, "Готово! Видео сохранено в Галерею → Movies/ASTRA");
        } catch (Exception e) {
            finishExportUi(false, "Экспорт готов, перенос в Галерею не удался: " + e.getMessage());
        }
    }

    private void finishExportUi(boolean success, String message) {
        exportButton.setEnabled(true);
        exportButton.setText("↧   СОХРАНИТЬ КОПИЮ");
        previewHint.setText(message);
        Toast.makeText(this, success ? "Видео сохранено в Галерею" : message, Toast.LENGTH_LONG).show();
        scanAstraExports();
    }

    private void updateTrimLabel() {
        if (trimRangeLabel != null) trimRangeLabel.setText(formatTime(trimStartMs) + " — " + formatTime(trimEndMs)
                + "   ·   " + formatTime(Math.max(0, trimEndMs - trimStartMs)));
    }

    private void cyclePlaybackSpeed() {
        playbackSpeedIndex = (playbackSpeedIndex + 1) % playbackSpeeds.length;
        applyPlaybackSpeed();
    }

    private void applyPlaybackSpeed() {
        float speed = playbackSpeeds[playbackSpeedIndex];
        if (speedButton != null) speedButton.setText("СКОРОСТЬ  " + String.format(java.util.Locale.US, "%.2f", speed).replaceAll("0+$", "").replaceAll("\\.$", "") + "×");
        if (prepared && mediaPlayer != null) {
            try {
                mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(speed));
            } catch (Exception e) {
                Toast.makeText(this, "Эта скорость не поддерживается устройством", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void togglePlayback() {
        if (timelinePlayer != null) {
            if (timelinePlayer.isPlaying()) {
                timelinePlayer.pause();
                playPauseButton.setText("▶");
            } else {
                if (timelinePlayer.getPlaybackState() == Player.STATE_ENDED) {
                    timelinePlayer.seekTo(0, 0);
                    timelinePlayer.prepare();
                }
                timelinePlayer.play();
                playPauseButton.setText("Ⅱ");
                uiHandler.removeCallbacks(progressUpdater);
                uiHandler.post(progressUpdater);
            }
            return;
        }
        if (prepared && mediaPlayer != null && mediaPlayer.isPlaying()) { mediaPlayer.pause(); playPauseButton.setText("▶"); return; }
        if (timelineClips.size() > 1) { startTimelinePreview(); return; }
        if (!prepared || mediaPlayer == null) return;
        mediaPlayer.start(); playPauseButton.setText("Ⅱ");
        uiHandler.removeCallbacks(progressUpdater); uiHandler.post(progressUpdater);
    }

    private void startTimelinePreview() {
        if (timelineClips.isEmpty() || !videoView.isAvailable()) return;
        releasePlayer();
        timelinePreviewActive = true; previewTimelineIndex = 0; selectedClipIndex = 0;
        try {
            timelinePlayer = new ExoPlayer.Builder(this).build();
            timelinePlayer.setVideoTextureView(videoView);
            timelinePlayer.addListener(new Player.Listener() {
                @Override public void onMediaItemTransition(MediaItem item, int reason) {
                    int index = timelinePlayer.getCurrentMediaItemIndex();
                    if (index >= 0 && index < timelineClips.size()) {
                        previewTimelineIndex = index; selectedClipIndex = index;
                        TimelineClip clip = timelineClips.get(index);
                        previewTitle.setText("МОНТАЖ · " + (index + 1) + "/" + timelineClips.size());
                        currentUri = clip.uri; sourceDurationMs = clip.durationMs;
                        trimStartMs = clip.startMs; trimEndMs = clip.endMs;
                        renderTimeline();
                    }
                }
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        previewHint.setText("Воспроизведение всей монтажной шкалы");
                    } else if (state == Player.STATE_ENDED) {
                        int current = timelinePlayer.getCurrentMediaItemIndex();
                        if (current >= 0 && current < timelineClips.size() - 1) {
                            // Safety fallback: if a clipped item reports ENDED before the playlist advances,
                            // explicitly continue with the next clip.
                            timelinePlayer.seekToNextMediaItem();
                            timelinePlayer.play();
                            return;
                        }
                        timelinePreviewActive = false;
                        previewHint.setText("Просмотр всего монтажа завершён · нажми ▶ для повтора");
                        if (playPauseButton != null) playPauseButton.setText("▶");
                    }
                }
            });
            ArrayList<MediaItem> playlist = new ArrayList<>();
            for (TimelineClip clip : timelineClips) {
                MediaItem.ClippingConfiguration clipping = new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build();
                playlist.add(new MediaItem.Builder().setUri(clip.uri).setClippingConfiguration(clipping).build());
            }
            timelinePlayer.setMediaItems(playlist); timelinePlayer.prepare(); timelinePlayer.play();
            renderTimeline(); uiHandler.removeCallbacks(progressUpdater); uiHandler.post(progressUpdater);
        } catch (Exception e) {
            timelinePreviewActive = false; previewHint.setText("Не удалось запустить монтаж: " + e.getMessage());
            releaseTimelinePlayer();
        }
    }

    private void releaseTimelinePlayer() {
        if (timelinePlayer != null) { try { timelinePlayer.release(); } catch (Exception ignored) {} timelinePlayer = null; }
        timelinePreviewActive = false;
    }

    private void splitSelectedClipAtPlayhead() {
        if (selectedClipIndex < 0 || selectedClipIndex >= timelineClips.size()) {
            Toast.makeText(this, "Сначала выбери фрагмент", Toast.LENGTH_SHORT).show(); return;
        }
        TimelineClip original = timelineClips.get(selectedClipIndex);
        int position;
        if (timelinePlayer != null && timelinePreviewActive) {
            int playingIndex = timelinePlayer.getCurrentMediaItemIndex();
            if (playingIndex >= 0 && playingIndex < timelineClips.size()) selectedClipIndex = playingIndex;
            original = timelineClips.get(selectedClipIndex);
            position = original.startMs + (int) timelinePlayer.getCurrentPosition();
        } else if (prepared && mediaPlayer != null) {
            position = mediaPlayer.getCurrentPosition();
        } else {
            Toast.makeText(this, "Сначала запусти монтаж и установи курсор", Toast.LENGTH_SHORT).show(); return;
        }
        position = Math.max(original.startMs + 500, Math.min(original.endMs - 500, position));
        if (position <= original.startMs || position >= original.endMs) {
            Toast.makeText(this, "Слишком близко к краю фрагмента", Toast.LENGTH_SHORT).show(); return;
        }
        TimelineClip second = new TimelineClip(original.uri, original.name, original.durationMs);
        second.startMs = position; second.endMs = original.endMs; original.endMs = position;
        timelineClips.add(selectedClipIndex + 1, second);
        int next = selectedClipIndex + 1; releaseTimelinePlayer(); selectTimelineClip(next);
        if (timelineScroller != null) timelineScroller.post(() -> timelineScroller.smoothScrollTo(Math.max(0, dp(147) * next - dp(40)), 0));
        Toast.makeText(this, "Фрагмент разделён на две части", Toast.LENGTH_SHORT).show();
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
        String name = slash >= 0 ? last.substring(slash + 1) : last;
        return name.startsWith("video:") ? "Видеофрагмент" : name;
    }

    private void scanAstraExports() {
        recentFiles.clear();
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
        uiHandler.removeCallbacks(exportProgressUpdater);
        if (activeTransformer != null) { try { activeTransformer.cancel(); } catch (Exception ignored) {} activeTransformer = null; }
        releasePlayer();
        videoView = null;
        super.onDestroy();
    }
}
