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
 * 5x4 contact sheet -> gentle S-curve camera move -> motion-compensated
 * interpolation -> 55 frames at 24 FPS (about 2.3 seconds).
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
            File[] frameFiles = frames.listFiles((dir, name) ->
                    name.startsWith("frame_") && name.endsWith(".png"));
            if (frameFiles == null || frameFiles.length != INPUT_FRAMES)
                throw new IllegalStateException("Для Cinematic Motion нужны 20 кадров из листа 5×4");

            File ffmpeg = new File(context.getApplicationInfo().nativeLibraryDir, "libffmpeg.so");
            if (!ffmpeg.isFile() || !ffmpeg.canExecute())
                throw new IllegalStateException("Встроенный FFmpeg не найден или недоступен для запуска");

            // The camera uses a restrained S-curve: a gradual push-in with a tiny
            // lateral drift. No frame is stretched; the crop stays centered.
            String inputRate = String.format(Locale.US, "%.8f",
                    INPUT_FRAMES / DURATION_SECONDS);
            String camera = "zoompan=" +
                    "z='1+0.045*(0.5-0.5*cos(PI*on/19))':" +
                    "x='iw/2-(iw/zoom/2)+3*sin(PI*on/19)':" +
                    "y='ih/2-(ih/zoom/2)-2*sin(PI*on/19)':" +
                    "d=1:s=576x896:fps=" + inputRate;
            String interpolation = "minterpolate=fps=24:mi_mode=mci:" +
                    "mc_mode=aobmc:me_mode=bidir:vsbmc=1";
            String filter = camera + "," + interpolation +
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