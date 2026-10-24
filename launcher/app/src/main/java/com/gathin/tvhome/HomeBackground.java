package com.gathin.tvhome;

import android.animation.TimeInterpolator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

/**
 * My System TV: what lies under Home's tiles. The chosen background (Fondos) plays as a video (VideoBackdrop) or
 * shows as the drawn sky with its clouds; the all-apps view lays a blurred, darkened still of it over the screen. While
 * an opaque layer covers the screen (that still, or the showcase's picture) the background under it is neither played
 * nor painted.
 */
final class HomeBackground {

    private final Activity activity;
    private final View root;
    private final SkyView sky;
    @Nullable private final SkyClouds skyClouds;
    /** skyClouds' view, or null without them. */
    @Nullable private final View clouds;
    /** The clouds' own alpha (sky_intensity), restored after a fade. */
    private final float cloudsAlpha;
    private final boolean motion;
    final VideoBackdrop video;
    /** The all-apps view's dark veil, faded in only when there is no blurred still. */
    private final View allAppsScrim;
    /** The all-apps view's blurred still of the background. */
    private final ImageView allAppsBlur;
    /** The video is up and opaque: the sky under it is hidden. */
    private boolean videoCovers;
    /** The all-apps blur is fully up: nothing under it is painted. */
    private boolean allAppsCovered;
    /** The showcase's picture covers the screen. */
    private boolean showcaseCovered;
    private boolean allApps;
    /** The all-apps blur made ahead of the key press; null when none is ready. */
    @Nullable private Bitmap preparedBlur;

    HomeBackground(Activity activity, View root, SkyView sky, @Nullable SkyClouds skyClouds, float cloudsAlpha,
                   boolean motion) {
        this.activity = activity;
        this.root = root;
        this.sky = sky;
        this.skyClouds = skyClouds;
        this.clouds = skyClouds != null ? activity.findViewById(R.id.skyClouds) : null;
        this.cloudsAlpha = cloudsAlpha;
        this.motion = motion;
        allAppsScrim = activity.findViewById(R.id.allAppsScrim);
        allAppsBlur = activity.findViewById(R.id.allAppsBlur);
        video = new VideoBackdrop(activity.findViewById(R.id.videoBg), activity.findViewById(R.id.videoStill), motion);
        // Under an opaque video the sky and its clouds were still painted on every frame; hide them while covered.
        video.setCoverListener(covered -> {
            videoCovers = covered;
            if (covered) fadeSkyOut();
            else showSky(!allAppsCovered && !showcaseCovered);
        });
    }

    /**
     * Shows the chosen background (Fondos): its video, or the drawn sky with its clouds, which start moving only when
     * cloudsMayMove (Home is up and no app backdrop hides them). The vignette is baked into the videos themselves
     * (videos/<id>.mp4): a full-screen shade layer cost the GPU a fill on every frame.
     */
    void applyFondo(boolean cloudsMayMove) {
        Fondos.Fondo fondo = Fondos.current(activity);
        video.setSource(fondo.video);
        if (skyClouds != null) {
            if (fondo.video != null) skyClouds.stop();
            else if (cloudsMayMove) skyClouds.start();
        }
    }

    boolean hasVideo() {
        return video.hasVideo();
    }

    /** The video is on screen: the sky drawn over its layer fades away, then stops being drawn. */
    private void fadeSkyOut() {
        if (sky.getVisibility() != View.VISIBLE) return;
        if (!motion) {
            showSky(false);
            return;
        }
        sky.animate().cancel();
        sky.animate().alpha(0f).setDuration(700).withEndAction(() -> {
            if (videoCovers) showSky(false);
        }).start();
        if (clouds != null) clouds.animate().alpha(0f).setDuration(700).start();
    }

    private void showSky(boolean shown) {
        int vis = shown ? View.VISIBLE : View.INVISIBLE;
        sky.animate().cancel();
        sky.setAlpha(1f);
        sky.setVisibility(vis);
        if (clouds != null) {
            clouds.animate().cancel();
            clouds.setAlpha(cloudsAlpha);   // fadeSkyOut took it to 0
            clouds.setVisibility(vis);
        }
    }

    /** While the blurred still covers the screen, the layers under it (video, sky) are not painted at all. */
    private void setAllAppsCovered(boolean covered) {
        if (covered == allAppsCovered) return;
        allAppsCovered = covered;
        updateCovered();
    }

    /** The showcase's picture covers the screen (its cross-fade finished), or no longer does. */
    void setShowcaseCovered(boolean covered) {
        if (covered == showcaseCovered) return;
        showcaseCovered = covered;
        updateCovered();
    }

    private void updateCovered() {
        boolean covered = allAppsCovered || showcaseCovered;
        // The video layer itself stays (hiding it would drop its surface and the player); it only holds still, and
        // the display hardware, not the GPU, blends it.
        showSky(!covered && !videoCovers);
        video.hold(covered);
    }

    /**
     * The all-apps view's background, after tvOS: it goes soft and dark over ms; back on the dock it returns. The
     * blur is made once from a small still of the frame and faded in; the video holds still under it.
     */
    void setAllApps(boolean on, long ms, TimeInterpolator ease) {
        allApps = on;
        allAppsScrim.animate().cancel();
        allAppsBlur.animate().cancel();
        if (on) {
            // Made ahead while the dock has the focus (prepareBlur), so the key press doesn't wait for it.
            Bitmap still = preparedBlur != null ? preparedBlur : blurredBackground();
            preparedBlur = null;
            // The darkening is in the blurred still itself (blurred); only without one does the veil fade in.
            allAppsScrim.animate().alpha(still == null ? 0.4f : 0f).setDuration(ms).setInterpolator(ease).start();
            if (still != null) {
                allAppsBlur.setImageBitmap(still);
                allAppsBlur.animate().alpha(1f).setDuration(ms).setInterpolator(ease)
                        .withEndAction(() -> {
                            if (allApps) setAllAppsCovered(true);
                        }).start();
            }
        } else {
            setAllAppsCovered(false);
            allAppsScrim.animate().alpha(0f).setDuration(ms).setInterpolator(ease).start();
            allAppsBlur.animate().alpha(0f).setDuration(ms).setInterpolator(ease).start();
        }
    }

    private final Runnable prepareBlur = this::prepareBlur;

    private void prepareBlur() {
        if (allApps || activity.isFinishing()) return;
        if (!video.hasVideo()) {
            preparedBlur = blurredBackground();
            return;
        }
        video.snapshot(192, 108, frame -> {
            if (frame != null && !allApps && !activity.isFinishing()) preparedBlur = blurred(frame);
        });
    }

    /** Called as the dock takes the focus: the blur is made a moment later, while nothing moves. */
    void scheduleBlur() {
        root.removeCallbacks(prepareBlur);
        root.postDelayed(prepareBlur, 450);
    }

    /** The background as it is now (video frame or drawn sky), small and box-blurred; stretched it reads as a deep blur. */
    @Nullable
    private Bitmap blurredBackground() {
        // The video's frame comes ahead of time (prepareBlur, read back from its layer); here only the sky is drawn.
        if (video.hasVideo() && videoCovers) return null;
        int w = 192, h = 108;
        Bitmap b;
        try {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(w / (float) Math.max(1, root.getWidth()), h / (float) Math.max(1, root.getHeight()));
            sky.draw(c);
        } catch (RuntimeException e) {
            return null;
        }
        return blurred(b);
    }

    /** A small picture box-blurred three times each way; stretched over the screen it reads as a deep blur. */
    private static Bitmap blurred(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, w, h, 4, true);
            boxBlur(px, w, h, 4, false);
        }
        // Darkened as the all-apps veil (40% black) did, so the veil needn't be drawn over it on every frame.
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = 0xFF000000 | ((((c >> 16) & 0xFF) * 3 / 5) << 16) | ((((c >> 8) & 0xFF) * 3 / 5) << 8) | ((c & 0xFF) * 3 / 5);
        }
        Bitmap out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        b.recycle();
        return out;
    }

    /** One pass of a running-sum box blur along rows (horizontal) or columns, clamped at the edges. */
    private static void boxBlur(int[] px, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int[] line = new int[len];
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) line[i] = px[horizontal ? l * w + i : i * w + l];
            int ra = 0, ga = 0, ba = 0;
            for (int i = -r; i <= r; i++) {
                int c = line[Math.max(0, Math.min(len - 1, i))];
                ra += (c >> 16) & 0xFF;
                ga += (c >> 8) & 0xFF;
                ba += c & 0xFF;
            }
            int n = 2 * r + 1;
            for (int i = 0; i < len; i++) {
                px[horizontal ? l * w + i : i * w + l] = 0xFF000000 | (ra / n) << 16 | (ga / n) << 8 | (ba / n);
                int add = line[Math.min(len - 1, i + r + 1)], drop = line[Math.max(0, i - r)];
                ra += ((add >> 16) & 0xFF) - ((drop >> 16) & 0xFF);
                ga += ((add >> 8) & 0xFF) - ((drop >> 8) & 0xFF);
                ba += (add & 0xFF) - (drop & 0xFF);
            }
        }
    }
}
