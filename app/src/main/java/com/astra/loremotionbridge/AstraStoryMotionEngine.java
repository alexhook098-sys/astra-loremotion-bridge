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
import java.util.concurrent.TimeUnit;

/**
 * Independent RIFE pipeline for ASTRA STORY MOTION.
 * Does not call or modify AstraMotionEngine.
 *
 * Required APK assets:
 * assets/story_motion/rife-ncnn-vulkan
 * assets/story_motion/model/flownet.bin
 * assets/story_motion/model/flownet.param
 */
public final class AstraStoryMotionEngine {
    private static final int SCENES = 4;
    private static final int KEYFRAMES_PER_SCENE = 5;
    private static final int INTERMEDIATES_PER_PAIR = 3;
    private static final int OUTPUT_FPS = 24;
    private static final int OUTPUT_FRAMES = SCENES * (KEYFRAMES_PER_SCENE +
            (KEYFRAMES_PER_SCENE - 1) * INTERMEDIATES_PER_PAIR);

    private AstraStoryMotionEngine() {}

    public static Uri createVideo(Context context, Uri contactSheet) throws Exception {
        File sourceFrames = AstraContactSheetSplitter.split(context, contactSheet);
        File job = new File(context.getCacheDir(), "astra_story_motion_" + System.nanoTime());
        File timeline = new File(job, "timeline");
        File output = new File(job, "story_motion.mp4");
        try {
            if (!job.mkdirs() || !timeline.mkdirs())
                throw new IllegalStateException("Не удалось создать рабочую папку ASTRA STORY MOTION");

            File runtime = prepareRuntime(context);
            File rife = new File(runtime, "rife-ncnn-vulkan");
            File model = new File(runtime, "rife-v4.6");
            File ffmpeg = new File(context.getApplicationInfo().nativeLibraryDir, "libffmpeg.so");
            if (!ffmpeg.isFile() || !ffmpeg.canExecute())
                throw new IllegalStateException("Встроенный FFmpeg не найден или недоступен для запуска");

            int outputIndex = 1;
            for (int scene = 0; scene < SCENES; scene++) {
                File[] keys = new File[KEYFRAMES_PER_SCENE];
                for (int k = 0; k < KEYFRAMES_PER_SCENE; k++) {
                    int sourceIndex = scene * KEYFRAMES_PER_SCENE + k + 1;
                    keys[k] = new File(sourceFrames,
                            String.format(Locale.US, "frame_%04d.png", sourceIndex));
                    if (!keys[k].isFile())
                        throw new IllegalStateException("Не найден ключевой кадр " + sourceIndex);
                }

                // Each scene starts with its own first keyframe. No interpolation crosses scene boundaries.
                copyFrame(keys[0], new File(timeline,
                        String.format(Locale.US, "frame_%04d.png", outputIndex++)));

                for (int pair = 0; pair < KEYFRAMES_PER_SCENE - 1; pair++) {
                    for (int step = 1; step <= INTERMEDIATES_PER_PAIR; step++) {
                        double time = step / 4.0d;
                        File intermediate = new File(job, String.format(Locale.US,
                                "scene_%d_pair_%d_t%d.png", scene + 1, pair + 1, step));
                        try {
                            runRife(rife, model, keys[pair], keys[pair + 1], intermediate, time, 0);
                        } catch (Exception gpuFailure) {
                            // Vulkan may be unavailable on a device; retry this pair on CPU.
                            if (intermediate.exists()) intermediate.delete();
                            try {
                                runRife(rife, model, keys[pair], keys[pair + 1],
                                        intermediate, time, -1);
                            } catch (Exception cpuFailure) {
                                cpuFailure.addSuppressed(gpuFailure);
                                throw new IllegalStateException("RIFE не смог обработать сцену " +
                                        (scene + 1) + ", пару кадров " + (pair + 1), cpuFailure);
                            }
                        }
                        if (!intermediate.isFile() || intermediate.length() < 128)
                            throw new IllegalStateException("RIFE не создал промежуточный кадр");
                        copyFrame(intermediate, new File(timeline,
                                String.format(Locale.US, "frame_%04d.png", outputIndex++)));
                        intermediate.delete();
                    }
                    copyFrame(keys[pair + 1], new File(timeline,
                            String.format(Locale.US, "frame_%04d.png", outputIndex++)));
                }
            }

            if (outputIndex - 1 != OUTPUT_FRAMES)
                throw new IllegalStateException("Неверное число кадров STORY MOTION: " +
                        (outputIndex - 1) + " вместо " + OUTPUT_FRAMES);

            runFfmpeg(ffmpeg, timeline, output);
            if (!output.isFile() || output.length() < 1024)
                throw new IllegalStateException("FFmpeg не создал итоговое видео");
            return publishVideo(context, output);
        } finally {
            deleteTree(sourceFrames);
            deleteTree(job);
        }
    }

    private static File prepareRuntime(Context context) throws Exception {
        File runtime = new File(context.getFilesDir(), "story_motion_runtime");
        File model = new File(runtime, "model");
        if (!model.exists() && !model.mkdirs())
            throw new IllegalStateException("Не удалось создать папку модели RIFE");
        File binary = new File(runtime, "rife-ncnn-vulkan");
        File bin = new File(model, "flownet.bin");
        File param = new File(model, "flownet.param");
        copyAssetIfMissing(context, "story_motion/rife-ncnn-vulkan", binary);
        copyAssetIfMissing(context, "story_motion/rife-v4.6/flownet.bin", bin);
        copyAssetIfMissing(context, "story_motion/rife-v4.6/flownet.param", param);
        if (!binary.setExecutable(true, true) && !binary.canExecute())
            throw new IllegalStateException("Android не разрешил запуск встроенного RIFE");
        if (!binary.isFile() || !bin.isFile() || !param.isFile())
            throw new IllegalStateException("Комплект RIFE неполный в APK");
        return runtime;
    }

    private static void copyAssetIfMissing(Context context, String asset, File destination)
            throws Exception {
        if (destination.isFile() && destination.length() > 0) return;
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IllegalStateException("Не удалось создать папку ресурса RIFE");
        try (InputStream in = context.getAssets().open(asset);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.getFD().sync();
        } catch (Exception e) {
            throw new IllegalStateException("Ресурс отсутствует в APK: " + asset +
                    ". Сначала добавь бинарник RIFE и модель в app/src/main/assets/story_motion.", e);
        }
    }

    private static void runRife(File binary, File model, File first, File second,
                                File destination, double time, int gpu) throws Exception {
        Process process = new ProcessBuilder(
                binary.getAbsolutePath(),
                "-0", first.getAbsolutePath(),
                "-1", second.getAbsolutePath(),
                "-o", destination.getAbsolutePath(),
                "-s", String.format(Locale.US, "%.2f", time),
                "-m", model.getAbsolutePath(),
                "-g", String.valueOf(gpu)
        ).redirectErrorStream(true).start();
        String log = readProcess(process, 10, TimeUnit.MINUTES);
        if (process.exitValue() != 0 || !destination.isFile())
            throw new IllegalStateException("RIFE завершился с ошибкой (GPU " + gpu + "): " + log);
    }

    private static void runFfmpeg(File binary, File frames, File output) throws Exception {
        Process process = new ProcessBuilder(
                binary.getAbsolutePath(),
                "-hide_banner", "-loglevel", "error", "-y",
                "-framerate", String.valueOf(OUTPUT_FPS),
                "-start_number", "1",
                "-i", new File(frames, "frame_%04d.png").getAbsolutePath(),
                "-frames:v", String.valueOf(OUTPUT_FRAMES),
                "-r", String.valueOf(OUTPUT_FPS),
                "-an", "-c:v", "mpeg4", "-q:v", "3",
                "-pix_fmt", "yuv420p",
                "-f", "mp4", output.getAbsolutePath()
        ).redirectErrorStream(true).start();
        String log = readProcess(process, 10, TimeUnit.MINUTES);
        if (process.exitValue() != 0)
            throw new IllegalStateException("FFmpeg STORY MOTION завершился с ошибкой: " + log);
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
                            log.append(new String(buffer, 0, count, java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {}
        }, "astra-story-process-log");
        reader.start();
        if (!process.waitFor(timeout, unit)) {
            process.destroyForcibly();
            reader.join(2000);
            throw new IllegalStateException("Превышено время выполнения локального обработчика");
        }
        reader.join(2000);
        synchronized (log) { return log.toString(); }
    }

    private static void copyFrame(File source, File destination) throws Exception {
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
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
                "ASTRA_STORY_MOTION_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ASTRA/StoryMotion");
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
