package com.gzero64;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;

/** Cached artwork in the same order as TouchLayout.IDS. */
final class TouchControlArtwork {
    private static final int[] RESOURCES = {
        R.drawable.touch_pause, R.drawable.touch_gas, R.drawable.touch_brake, R.drawable.touch_boost,
        R.drawable.touch_stick_base, R.drawable.touch_drift_left, R.drawable.touch_drift_right, R.drawable.touch_camera
    };

    private final Bitmap[] images = new Bitmap[RESOURCES.length];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private final LightingColorFilter pressed = new LightingColorFilter(0xFFFFFFFF, 0x00202020);
    private final Bitmap knob;

    TouchControlArtwork(Resources resources) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        for (int i = 0; i < images.length; i++) {
            images[i] = BitmapFactory.decodeResource(resources, RESOURCES[i], options);
        }
        knob = BitmapFactory.decodeResource(resources, R.drawable.touch_stick_knob, options);
    }

    /** The analog stick's knob, centred on (x, y). */
    void drawKnob(Canvas canvas, float x, float y, float size, int alpha) {
        destination.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
        paint.setAlpha(alpha);
        paint.setColorFilter(null);
        canvas.drawBitmap(knob, null, destination, paint);
    }

    void draw(Canvas canvas, int index, RectF bounds, boolean down, int alpha) {
        Bitmap image = images[index];
        float scale = Math.min(bounds.width() / image.getWidth(),
                bounds.height() / image.getHeight());
        float halfWidth = image.getWidth() * scale / 2;
        float halfHeight = image.getHeight() * scale / 2;
        destination.set(bounds.centerX() - halfWidth, bounds.centerY() - halfHeight,
                bounds.centerX() + halfWidth, bounds.centerY() + halfHeight);
        paint.setAlpha(down ? 255 : alpha);
        paint.setColorFilter(down ? pressed : null);
        canvas.drawBitmap(image, null, destination, paint);
    }
}
