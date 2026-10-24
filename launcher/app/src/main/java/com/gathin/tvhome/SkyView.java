package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;

/**
 * A full-screen gradient that follows the clock: night blues, a warm horizon at sunset, a soft
 * glow that travels with the sun. Off by default (config.xml, sky_intensity = 0); a theme turns
 * it on with {@code "sky_intensity": 60}.
 *
 * Like the ambient glow, the backdrop is one small bitmap (480x270) stretched over the screen:
 * a gradient repainted at full resolution every minute is exactly the CPU cost the ambient glow
 * was rewritten to avoid on weak GPUs. It is redrawn once a minute and never animated, so the
 * cost is a single full-screen draw per minute.
 */
public class SkyView extends View {

    /** Full-screen and faded with setAlpha: without this every redraw of Home goes through an offscreen layer. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private static final int TEX_W = 480;
    private static final int TEX_H = 270;

    /** Opacity of the whole backdrop, 0 to 255, from sky_intensity. */
    private int intensity;
    private int lastMinute = -1;
    private Bitmap texture;
    private BitmapDrawable drawable;

    public SkyView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /** {@code percent} of sky_intensity: 0 hides the backdrop, 100 covers the wallpaper. */
    public void setIntensity(int percent) {
        intensity = Math.max(0, Math.min(100, percent));
        setAlpha(intensity / 100f);
        setVisibility(intensity == 0 ? GONE : VISIBLE);
        if (intensity > 0) render(true);
    }

    /** Picks up a new time of day; cheap and safe to call every minute. */
    public void update() {
        if (intensity == 0 || getVisibility() != VISIBLE) return;
        render(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (intensity > 0) render(true);
    }

    /** Today's sunrise and sunset at the configured place, or null without one (fixed reference day). */
    @Nullable
    private int[] sunTimes() {
        String lat = getContext().getString(R.string.latitude);
        String lon = getContext().getString(R.string.longitude);
        if (lat.isEmpty() || lon.isEmpty()) return null;
        try {
            return Sun.riseAndSet(LocalDate.now(), Double.parseDouble(lat), Double.parseDouble(lon),
                    ZoneId.systemDefault());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- sky_scene: stars, a moon and a sun drawn at full resolution over the stretched gradient ----

    private boolean scene;
    /** 1 at night, 0 in full day, in between around sunrise and sunset. */
    private float night;
    /** The sun's way across the day, 0 at sunrise to 1 at sunset; outside 0..1 it is below the horizon. */
    private float dayProgress;
    private int cloudColor = 0xFFFFFFFF;
    private float cloudAlpha = 0.8f;
    private final Paint scenePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path crescent = new android.graphics.Path();
    private final android.graphics.Path cut = new android.graphics.Path();
    /** x, y (0..1 of the view), radius in dp and brightness of each star; fixed, so the stars never jump. */
    private float[] stars;

    /** sky_scene on or off (config.xml); the clouds are SkyClouds, tinted with cloudColor and cloudAlpha. */
    public void setScene(boolean on) {
        scene = on;
        if (on && stars == null) {
            java.util.Random r = new java.util.Random(7);
            stars = new float[140 * 4];
            for (int i = 0; i < stars.length; i += 4) {
                stars[i] = r.nextFloat();
                stars[i + 1] = r.nextFloat() * 0.62f;
                stars[i + 2] = 0.5f + r.nextFloat() * 1.1f;
                stars[i + 3] = 0.2f + r.nextFloat() * 0.8f;
            }
        }
        invalidate();
    }

    int cloudColor() {
        return cloudColor;
    }

    float cloudAlpha() {
        return cloudAlpha;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!scene || intensity == 0) return;
        int w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        scenePaint.setShader(null);
        if (night > 0f && stars != null) {
            for (int i = 0; i < stars.length; i += 4) {
                scenePaint.setColor(Color.argb(Math.round(255 * night * stars[i + 3] * 0.85f), 255, 255, 255));
                canvas.drawCircle(stars[i] * w, stars[i + 1] * h, stars[i + 2] * d, scenePaint);
            }
            drawMoon(canvas, w * 0.74f, h * 0.30f, 26 * d, night);
        }
        if (night < 1f && dayProgress > -0.05f && dayProgress < 1.05f) {
            float x = w * (0.12f + 0.76f * dayProgress);
            float y = h * 0.66f - (float) Math.sin(Math.PI * Math.max(0f, Math.min(1f, dayProgress))) * h * 0.46f;
            drawSun(canvas, x, y, 34 * d, 1f - night);
        }
    }

    private void drawSun(Canvas canvas, float x, float y, float r, float strength) {
        int a = Math.round(255 * strength);
        scenePaint.setShader(new RadialGradient(x, y, r * 5.5f,
                new int[]{Color.argb(Math.round(a * 0.75f), 255, 240, 190), Color.argb(Math.round(a * 0.25f), 255, 205, 120),
                        Color.argb(0, 255, 180, 80)},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, r * 5.5f, scenePaint);
        scenePaint.setShader(null);
        scenePaint.setColor(Color.argb(a, 255, 250, 230));
        canvas.drawCircle(x, y, r, scenePaint);
    }

    /** A crescent: the disc minus a second disc offset up and to the right, so the cut shows the sky behind it. */
    private void drawMoon(Canvas canvas, float x, float y, float r, float strength) {
        int a = Math.round(255 * strength);
        scenePaint.setShader(new RadialGradient(x, y, r * 4.5f,
                new int[]{Color.argb(Math.round(a * 0.30f), 255, 255, 255), Color.argb(Math.round(a * 0.07f), 200, 220, 255),
                        Color.argb(0, 0, 0, 0)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, r * 4.5f, scenePaint);
        scenePaint.setShader(null);
        crescent.reset();
        crescent.addCircle(x, y, r, android.graphics.Path.Direction.CW);
        cut.reset();
        cut.addCircle(x + r * 0.32f, y - r * 0.2f, r * 0.88f, android.graphics.Path.Direction.CW);
        crescent.op(cut, android.graphics.Path.Op.DIFFERENCE);
        scenePaint.setColor(Color.argb(Math.round(a * 0.92f), 245, 248, 255));
        canvas.drawPath(crescent, scenePaint);
    }

    /** night and dayProgress for this minute, against the real sunrise and sunset when a place is set. */
    private void updateScene(int minute, int[] sun) {
        int rise = sun != null ? sun[0] : Sky.REF_SUNRISE;
        int set = sun != null ? sun[1] : Sky.REF_SUNSET;
        dayProgress = (minute - rise) / (float) Math.max(1, set - rise);
        // Full night from 50 minutes after sunset to 50 minutes before sunrise, full day from 40 minutes after
        // sunrise to 40 minutes before sunset, a straight ramp in between.
        float n;
        if (minute < rise - 50 || minute > set + 50) n = 1f;
        else if (minute < rise + 40) n = (rise + 40 - minute) / 90f;
        else if (minute > set - 40) n = (minute - (set - 40)) / 90f;
        else n = 0f;
        night = Math.max(0f, Math.min(1f, n));
        // Clouds: near white by day, warm at the edges of the day, a dim blue-grey at night.
        int day = 0xFFF2F7FF;
        int dusk = 0xFFE8B8C0;
        int dark = 0xFF5A6488;
        float edge = 1f - Math.abs(night - 0.5f) * 2f;   // 1 halfway through dusk or dawn
        int lit = Sky.lerpColor(day, dusk, Math.max(0f, edge));
        cloudColor = Sky.lerpColor(lit, dark, night);
        cloudAlpha = 0.85f - 0.4f * night;
    }

    private void render(boolean force) {
        Calendar now = Calendar.getInstance();
        int minute = Sky.minuteOfDay(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        if (!force && minute == lastMinute) return;
        lastMinute = minute;

        int[] sun = sunTimes();
        Sky.Palette sky = sun == null ? Sky.at(minute) : Sky.at(minute, sun[0], sun[1]);
        updateScene(minute, sun);
        if (texture == null) texture = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(texture);
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0f, 0f, 0f, TEX_H,
                new int[]{sky.top, sky.mid, sky.bottom},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, TEX_W, TEX_H, paint);

        if (sky.sun > 0) {
            // A warm core low on the horizon, where the sun (or its afterglow) sits.
            int warm = Sky.lerpColor(sky.bottom, 0xFFFFD9A0, 0.65f);
            paint.setShader(new RadialGradient(
                    sky.sunX * TEX_W, TEX_H * 0.80f, TEX_W * 0.55f,
                    new int[]{(sky.sun << 24) | (warm & 0x00FFFFFF), warm & 0x00FFFFFF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, TEX_W, TEX_H, paint);
        }

        if (drawable == null) {
            drawable = new BitmapDrawable(getResources(), texture);
            drawable.setGravity(Gravity.FILL);
            setBackground(drawable);
        }
        invalidate();
    }
}
