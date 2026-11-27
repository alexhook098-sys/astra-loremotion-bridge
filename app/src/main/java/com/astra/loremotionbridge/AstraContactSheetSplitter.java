package com.astra.loremotionbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Splits supported 5x4, 4x5, 4x4 or 2x5 ASTRA contact sheets into normalized, ordered frames. */
public final class AstraContactSheetSplitter {
    private static final int DEFAULT_COLUMNS = 5;
    private static final int DEFAULT_ROWS = 4;
    private static final int ALT_COLUMNS = 4;
    private static final int ALT_ROWS = 5;
    private static final int SQUARE_COLUMNS = 4;
    private static final int SQUARE_ROWS = 4;
    private static final int STORY_COLUMNS = 2;
    private static final int STORY_ROWS = 5;
    public static final int FRAME_WIDTH = 576;
    public static final int FRAME_HEIGHT = 896;
    private static final int CELL_INSET = 2;

    private AstraContactSheetSplitter() {}

    public static File split(Context context, Uri source) throws Exception {
        Bitmap sheet;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IllegalArgumentException("Не удалось открыть изображение");
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            // Decode stream once for bounds, then reopen for pixels.
            BitmapFactory.decodeStream(in, null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                throw new IllegalArgumentException("Файл не является изображением");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        // Bound memory on low-RAM phones before decoding a large contact sheet.
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            int sample = 1;
            while ((bounds.outWidth / sample) * (bounds.outHeight / sample) > 6_000_000 ||
                    Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 3072) {
                sample *= 2;
            }
            options.inSampleSize = sample;
        }
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            sheet = BitmapFactory.decodeStream(in, null, options);
        }
        if (sheet == null) throw new IllegalArgumentException("Не удалось декодировать изображение");
        try {
            // Select the closest supported grid by expected sheet aspect ratio.
            // Portrait cells are normalized to FRAME_WIDTH × FRAME_HEIGHT after splitting.
            double sheetRatio = (double) sheet.getWidth() / sheet.getHeight();
            int[][] grids = {
                    {DEFAULT_COLUMNS, DEFAULT_ROWS},
                    {ALT_COLUMNS, ALT_ROWS},
                    {SQUARE_COLUMNS, SQUARE_ROWS},
                    // The 2×5 storyboards use near-square panels in the supplied examples.
                    {STORY_COLUMNS, STORY_ROWS}
            };
            int columns = DEFAULT_COLUMNS;
            int rows = DEFAULT_ROWS;
            double bestError = Double.POSITIVE_INFINITY;
            for (int[] grid : grids) {
                double expectedRatio = (grid[0] == STORY_COLUMNS && grid[1] == STORY_ROWS)
                        ? (double) STORY_COLUMNS / STORY_ROWS
                        : (double) grid[0] * FRAME_WIDTH / (grid[1] * FRAME_HEIGHT);
                double error = Math.abs(Math.log(sheetRatio / expectedRatio));
                if (error < bestError) {
                    bestError = error;
                    columns = grid[0];
                    rows = grid[1];
                }
            }
            int cellW = sheet.getWidth() / columns;
            int cellH = sheet.getHeight() / rows;
            if (cellW < 8 || cellH < 8) throw new IllegalArgumentException("Контактный лист слишком маленький");
            // Give every processing request its own frame directory. This prevents
            // One Click, Cinematic and Story Motion jobs from deleting each other's frames.
            File jobsRoot = new File(context.getCacheDir(), "astra_motion_jobs");
            if (!jobsRoot.exists() && !jobsRoot.mkdirs())
                throw new IllegalStateException("Не удалось создать рабочую папку обработки");
            File dir = new File(jobsRoot, "frames_" + System.nanoTime());
            if (!dir.mkdirs())
                throw new IllegalStateException("Не удалось создать папку кадров текущего запуска");
            for (int i = 0; i < rows * columns; i++) {
                int col = i % columns, row = i / columns;
                int left = col * cellW + CELL_INSET;
                int top = row * cellH + CELL_INSET;
                int right = (col == columns - 1 ? sheet.getWidth() : (col + 1) * cellW) - CELL_INSET;
                int bottom = (row == rows - 1 ? sheet.getHeight() : (row + 1) * cellH) - CELL_INSET;
                if (right <= left || bottom <= top) throw new IllegalArgumentException("Некорректная сетка контактного листа 5×4, 4×5, 4×4 или 2×5");
                Bitmap cell = Bitmap.createBitmap(sheet, left, top, right-left, bottom-top);
                Bitmap frame = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(frame);
                canvas.drawColor(Color.BLACK);
                float scale = Math.min((float) FRAME_WIDTH / cell.getWidth(),
                                       (float) FRAME_HEIGHT / cell.getHeight());
                int w = Math.max(1, Math.round(cell.getWidth() * scale));
                int h = Math.max(1, Math.round(cell.getHeight() * scale));
                int x = (FRAME_WIDTH - w) / 2, y = (FRAME_HEIGHT - h) / 2;
                canvas.drawBitmap(cell, null, new Rect(x, y, x+w, y+h), null);
                cell.recycle();
                File output = new File(dir, String.format(java.util.Locale.US, "frame_%04d.png", i+1));
                try (FileOutputStream out = new FileOutputStream(output)) {
                    if (!frame.compress(Bitmap.CompressFormat.PNG, 100, out))
                        throw new IllegalStateException("Ошибка сохранения кадра " + (i+1));
                } finally { frame.recycle(); }
            }
            return dir;
        } finally { sheet.recycle(); }
    }
}
