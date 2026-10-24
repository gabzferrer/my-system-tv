package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Random;

/**
 * sky_scene: soft clouds drifting slowly across the sky, after the HTML prototype's "Apple Weather" layers.
 *
 * Each cloud is drawn once into a small white bitmap (a cluster of radial puffs) and shown by an ImageView that is
 * stretched and tinted for the time of day; moving it is only a change of translationX, so the GPU recomposites a
 * few textured quads and nothing is redrawn. The drift ticks at 20 fps and stops whenever Home is not in front or
 * a backdrop covers the sky. reduced_motion leaves the clouds where they are.
 */
final class SkyClouds {

    private static final int SPRITE_W = 320, SPRITE_H = 128;
    private static final long TICK_MS = 50;
    /** Three depths, as in the prototype: far clouds are small, faint and slow; near ones large and quicker. */
    private static final int[] PER_LAYER = {3, 3, 2};

    private final FrameLayout box;
    private final boolean still;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ImageView[] clouds;
    private float[] speeds;   // px per second
    private boolean running;
    private int tint = 0xFFFFFFFF;
    private float alpha = 0.8f;

    SkyClouds(FrameLayout box, boolean reducedMotion) {
        this.box = box;
        this.still = reducedMotion;
        box.setVisibility(View.VISIBLE);
        box.post(this::build);
    }

    private void build() {
        int w = box.getWidth(), h = box.getHeight();
        if (w == 0 || h == 0) {
            box.post(this::build);
            return;
        }
        Random r = new Random(27);
        int total = 0;
        for (int n : PER_LAYER) total += n;
        clouds = new ImageView[total];
        speeds = new float[total];
        int i = 0;
        for (int layer = 0; layer < PER_LAYER.length; layer++) {
            for (int k = 0; k < PER_LAYER[layer]; k++, i++) {
                ImageView v = new ImageView(box.getContext());
                v.setImageBitmap(sprite(r));
                v.setScaleType(ImageView.ScaleType.FIT_XY);
                v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                float scale = 0.40f + layer * 0.15f + r.nextFloat() * 0.10f;   // of the screen width
                int cw = Math.round(w * scale), ch = Math.round(cw * SPRITE_H / (float) SPRITE_W);
                box.addView(v, new FrameLayout.LayoutParams(cw, ch));
                v.setTranslationX(r.nextFloat() * (w + cw) - cw);
                v.setTranslationY((0.02f + r.nextFloat() * 0.32f + layer * 0.06f) * h);
                v.setTag(0.45f + layer * 0.2f);   // this depth's share of the overall alpha
                speeds[i] = (3f + layer * 4f + r.nextFloat() * 3f) * box.getResources().getDisplayMetrics().density;
                clouds[i] = v;
            }
        }
        apply();
        if (running) schedule();
    }

    /**
     * One cloud: a cluster of white radial puffs, brightest a little above its centre. Every puff stays wholly inside
     * the bitmap (its gradient fades to nothing at its edge), so no puff is cut off in a straight line.
     */
    private static Bitmap sprite(Random r) {
        Bitmap b = Bitmap.createBitmap(SPRITE_W, SPRITE_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int puffs = 9 + r.nextInt(6);
        for (int i = 0; i < puffs; i++) {
            float rad = SPRITE_H * (0.20f + r.nextFloat() * 0.18f);
            float x = SPRITE_W * (0.22f + r.nextFloat() * 0.56f);
            float y = SPRITE_H * (0.5f + (r.nextFloat() - 0.45f) * 0.3f);
            x = Math.max(rad + 2, Math.min(SPRITE_W - rad - 2, x));
            y = Math.max(rad + 2, Math.min(SPRITE_H - rad - 2, y));
            int a = Math.round(255 * (0.35f + r.nextFloat() * 0.25f));
            p.setShader(new RadialGradient(x - rad * 0.2f, y - rad * 0.25f, rad,
                    new int[]{(a << 24) | 0xFFFFFF, ((a / 2) << 24) | 0xFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, rad, p);
        }
        return b;
    }

    /** The time of day's cloud colour and strength (SkyView.cloudColor / cloudAlpha). */
    void setTint(int color, float strength) {
        tint = color;
        alpha = strength;
        apply();
    }

    private void apply() {
        if (clouds == null) return;
        PorterDuffColorFilter filter = new PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY);
        for (ImageView v : clouds) {
            v.setColorFilter(filter);
            v.setAlpha(alpha * (float) v.getTag());
        }
    }

    void start() {
        if (running) return;
        running = true;
        if (clouds != null) schedule();
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    private void schedule() {
        handler.removeCallbacks(tick);
        if (!still) handler.postDelayed(tick, TICK_MS);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || clouds == null) return;
            int w = box.getWidth();
            float dt = TICK_MS / 1000f;
            for (int i = 0; i < clouds.length; i++) {
                ImageView v = clouds[i];
                float x = v.getTranslationX() + speeds[i] * dt;
                if (x > w) x = -v.getWidth();
                v.setTranslationX(x);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };
}
