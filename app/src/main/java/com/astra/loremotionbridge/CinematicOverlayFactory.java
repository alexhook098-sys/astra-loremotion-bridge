package com.astra.loremotionbridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import java.util.Random;

/** Small bitmap overlays shared by the preview and Media3 export paths. */
final class CinematicOverlayFactory {
    private CinematicOverlayFactory() {}

    static Bitmap createVignette(int width, int height, int strength) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        float amount = Math.max(0f, Math.min(1f, strength / 100f));
        float radius = (float) Math.hypot(width * 0.5f, height * 0.5f);
        int edgeAlpha = Math.round(190f * amount);
        int[] colors = new int[] {
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            Color.argb(Math.round(edgeAlpha * 0.38f), 0, 0, 0),
            Color.argb(edgeAlpha, 0, 0, 0)
        };
        float[] stops = new float[] {0f, 0.48f, 0.78f, 1f};
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new RadialGradient(width * 0.5f, height * 0.5f,
                radius, colors, stops, Shader.TileMode.CLAMP));
        new Canvas(bitmap).drawRect(0, 0, width, height, paint);
        return bitmap;
    }

    static Bitmap createGrain(int width, int height, int strength) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        float amount = Math.max(0f, Math.min(1f, strength / 100f));
        int alpha = Math.round(72f * amount);
        int[] pixels = new int[width * height];
        Random random = new Random(0x41535452414cL);
        for (int i = 0; i < pixels.length; i++) {
            int value = random.nextBoolean() ? 255 : 0;
            int variedAlpha = Math.round(alpha * (0.35f + random.nextFloat() * 0.65f));
            pixels[i] = Color.argb(variedAlpha, value, value, value);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }
}
