package com.astra.loremotionbridge;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/** Runs FFmpeg from Android nativeLibraryDir, where packaged native code is executable. */
public final class AstraMotionEngine {
    private static final int FPS = 20;
    private static final int OUTPUT_FRAMES = 30;
    private static final double DURATION_SECONDS = 1.5;

    private AstraMotionEngine() {}

    public static Uri createVideo(Context context, Uri reference) throws Exception {
        File frames = AstraContactSheetSplitter.split(context, reference);
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libffmpeg.so");
        if (!binary.isFile() || !binary.canExecute())
            throw new IllegalStateException("Встроенный FFmpeg не найден в nativeLibraryDir или недоступен для запуска");
        File temp = new File(context.getCacheDir(), "astra_motion_" + System.currentTimeMillis() + ".mp4");
        if (temp.exists()) temp.delete();

        File[] frameFiles = frames.listFiles((dir, name) ->
                name.startsWith("frame_") && name.endsWith(".png"));
        int inputFrameCount = frameFiles == null ? 0 : frameFiles.length;
        if (inputFrameCount < 2)
            throw new IllegalStateException("Недостаточно кадров для создания видео");
        // Keep every supported grid (16 or 20 frames) stretched across the same 1.5-second source interval.
        String inputRate = String.format(Locale.US, "%.6f", inputFrameCount / DURATION_SECONDS);
        String filter = "minterpolate=fps=20:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1," +
                "tpad=stop_mode=clone:stop_duration=1,trim=end_frame=30,setpts=N/(20*TB)";
        Process process = new ProcessBuilder(
                binary.getAbsolutePath(),
                "-hide_banner", "-loglevel", "error", "-y",
                "-framerate", inputRate, "-start_number", "1",
                "-i", new File(frames, "frame_%04d.png").getAbsolutePath(),
                "-vf", filter,
                "-frames:v", String.valueOf(OUTPUT_FRAMES),
                "-r", String.valueOf(FPS),
                "-an", "-c:v", "mpeg4", "-q:v", "3",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                "-f", "mp4", temp.getAbsolutePath()
        ).redirectErrorStream(true).start();

        StringBuilder log = new StringBuilder();
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (log.length() < 12000) log.append(new String(buffer, 0, count, "UTF-8"));
            }
        }
        int exit = process.waitFor();
        if (exit != 0 || !temp.isFile() || temp.length() < 1024) {
            temp.delete();
            throw new IllegalStateException("FFmpeg завершился с кодом " + exit + ": " + log);
        }

        Uri saved = publishVideo(context, temp);
        temp.delete();
        return saved;
    }

    private static Uri publishVideo(Context context, File source) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "ASTRA_MOTION_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ASTRA");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }
        Uri collection = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        Uri uri = context.getContentResolver().insert(collection, values);
        if (uri == null) throw new IllegalStateException("Не удалось создать видео в галерее");
        boolean complete = false;
        try (InputStream in = new FileInputStream(source);
             java.io.OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("Не удалось записать видео в галерею");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.flush();
            complete = true;
        } finally {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues finish = new ContentValues();
                finish.put(MediaStore.Video.Media.IS_PENDING, 0);
                if (complete) context.getContentResolver().update(uri, finish, null, null);
                else context.getContentResolver().delete(uri, null, null);
            } else if (!complete) context.getContentResolver().delete(uri, null, null);
        }
        return uri;
    }
}
