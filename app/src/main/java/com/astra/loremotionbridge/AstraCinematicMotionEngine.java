package com.astra.loremotionbridge;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Independent cinematic variant of One Click Motion.
 * Keeps the original AstraMotionEngine pipeline untouched.
 *
 * 5x4 contact sheet -> blended frame transitions -> LIRA S-curve camera move
 * -> 55 frames at 24 FPS (about 2.3 seconds), without optical-flow warping.
 */
public final class AstraCinematicMotionEngine {
    private static final int INPUT_FRAMES = 20;
    private static final int OUTPUT_FPS = 24;
    private static final double DURATION_SECONDS = 2.3d;
    private static final int OUTPUT_FRAMES = 55;

    private AstraCinematicMotionEngine() {}

    public static Uri createVideo(Context context, Uri contactSheet) throws Exception {
        File frames = AstraContactSheetSplitter.split(context, contactSheet);
        File output = new File(context.getCacheDir(),
                "astra_cinematic_motion_" + System.nanoTime() + ".mp4");
        try {
            // Validate the exact numbered sequence before FFmpeg opens the image2 input.
            // A count-only check can miss a gap such as frame_0010.png.
            for (int i = 1; i <= INPUT_FRAMES; i++) {
                File frame = new File(frames, String.format(Locale.US, "frame_%04d.png", i));
                if (!frame.isFile() || frame.length() < 128) {
                    StringBuilder present = new StringBuilder();
                    File[] files = frames.listFiles();
                    if (files != null) {
                        for (File item : files) {
                            if (present.length() < 1500) present.append(item.getName())
                                    .append("(").append(item.length()).append("), ");
                        }
                    }
                    throw new IllegalStateException("Не найден кадр " + i + " из 20: " +
                            frame.getAbsolutePath() + ". Файлы в папке: " + present);
                }
            }

            File ffmpeg = new File(context.getApplicationInfo().nativeLibraryDir, "libffmpeg.so");
            if (!ffmpeg.isFile() || !ffmpeg.canExecute())
                throw new IllegalStateException("Встроенный FFmpeg не найден или недоступен для запуска");

            // LIRA Curves & Dynamic Camera 2.3:
            // 1) Preserve source aspect ratio in a 9:16 canvas (no stretching).
            // 2) Blend adjacent storyboard frames instead of optical-flow warping.
            // 3) Apply the camera move AFTER frame blending so the S-curve spans
            //    the complete 55-frame output, not just the 20 source images.
            // 4) Finish with an exact 55-frame / 24-FPS timeline.
            String inputRate = String.format(Locale.US, "%.8f",
                    (INPUT_FRAMES - 1) / DURATION_SECONDS);
            String camera = "zoompan=" +
                    "z='1+0.055*(0.5-0.5*cos(PI*on/54))':" +
                    "x='iw/2-(iw/zoom/2)+5*sin(PI*on/54)':" +
                    "y='ih/2-(ih/zoom/2)-2*sin(PI*on/54)':" +
                    "d=1:s=720x1280:fps=24";
            String interpolation = "minterpolate=fps=24:mi_mode=blend";
            // Use named filter options: this bundled FFmpeg parser rejects
            // positional pad arguments containing arithmetic expressions.
            String filter = "scale=w=720:h=1280:force_original_aspect_ratio=decrease," +
                    "pad=w=720:h=1280:x=(ow-iw)/2:y=(oh-ih)/2:color=black," +
                    interpolation + "," + camera +
                    ",trim=end_frame=" + OUTPUT_FRAMES + ",setpts=N/(24*TB)";

            Process process = new ProcessBuilder(
                    ffmpeg.getAbsolutePath(),
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-framerate", inputRate,
                    "-start_number", "1",
                    "-i", new File(frames, "frame_%04d.png").getAbsolutePath(),
                    "-vf", filter,
                    "-frames:v", String.valueOf(OUTPUT_FRAMES),
                    "-r", String.valueOf(OUTPUT_FPS),
                    "-an", "-c:v", "mpeg4", "-q:v", "3",
                    "-pix_fmt", "yuv420p",
                    // Avoid faststart: this Android FFmpeg build has had trouble
                    // reopening the output during the faststart second pass.
                    "-f", "mp4", output.getAbsolutePath()
            ).redirectErrorStream(true).start();

            String log = readProcess(process, 10, TimeUnit.MINUTES);
            if (process.exitValue() != 0 || !output.isFile() || output.length() < 1024)
                throw new IllegalStateException("FFmpeg Cinematic Motion завершился с ошибкой: " + log);

            return publishVideo(context, output);
        } finally {
            deleteTree(frames);
            if (output.exists()) output.delete();
        }
    }

    private static String readProcess(Process process, long timeout, TimeUnit unit)
            throws Exception {
        StringBuilder log = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    synchronized (log) {
                        if (log.length() < 12000)
                            log.append(new String(buffer, 0, count,
                                    java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {}
        }, "astra-cinematic-process-log");
        reader.start();
        if (!process.waitFor(timeout, unit)) {
            process.destroyForcibly();
            reader.join(2000);
            throw new IllegalStateException("Превышено время выполнения Cinematic Motion");
        }
        reader.join(2000);
        synchronized (log) { return log.toString(); }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static Uri publishVideo(Context context, File source) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME,
                "ASTRA_CINEMATIC_MOTION_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ASTRA/CinematicMotion");
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
            } else if (!complete) {
                context.getContentResolver().delete(uri, null, null);
            }
        }
        return uri;
    }
}