package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.function.Consumer;

/**
 * My System TV: Home's video background (Fondos), muted and looping. It plays into a SurfaceView, a layer of its
 * own under Home's window that the TV's display hardware blends in: drawn in a TextureView it cost the GPU about
 * 11 ms on every frame. The view sits at the bottom of Home; the sky drawn over it fades away once the video shows.
 * The player holds a hardware decoder, so Home releases it whenever it stops (an app or the screensaver in front)
 * and starts it again on the way back, with the last frame (still) over the new surface until the video runs again.
 */
final class VideoBackdrop implements SurfaceHolder.Callback {

    private final SurfaceView view;
    /** The last frame, shown over the new (black) surface on the way back to Home until the video draws again. */
    private final ImageView still;
    private final boolean animate;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private File source;
    @Nullable private MediaPlayer player;
    @Nullable private Surface surface;
    private boolean wanted;
    private boolean prepared;
    /** A frame of the current source is on the surface. */
    private boolean rendering;
    /** hold(true): stay on the current frame (something opaque covers it). */
    private boolean held;
    /** setAway(true): Home is paused, an app or the screensaver is coming up. */
    private boolean away;
    /**
     * The player's slow calls (opening the file, reset and release) run here, in order, so they never hold up the main
     * thread, which also answers the remote's buttons for the key helpers (InputKeyService, HomeKeyService).
     */
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Told when the video shows (true: the sky over it can go) or stops showing (false: bring the sky back). */
    @Nullable private Consumer<Boolean> coverListener;

    VideoBackdrop(SurfaceView view, ImageView still, boolean animate) {
        this.view = view;
        this.still = still;
        this.animate = animate;
        view.getHolder().addCallback(this);
    }

    /** The video to show, or null for none (the sky stays). Takes effect at once if playing. */
    void setSource(@Nullable File file) {
        if (file == null ? source == null : file.equals(source)) return;
        source = file;
        if (wanted) {
            release();
            if (file == null) shown(false);
            else open();
        }
    }

    boolean hasVideo() {
        return source != null;
    }

    /** MainActivity fades the sky over the video away (true) or brings it back (false). */
    void setCoverListener(@Nullable Consumer<Boolean> listener) {
        coverListener = listener;
    }

    void start() {
        wanted = true;
        if (player == null) open();
    }

    /** Releases the player and its decoder; the surface goes with the window, the still covers the way back. */
    void stop() {
        wanted = false;
        release();
    }

    /**
     * The frame on screen, this small, for the all-apps view's blurred still (MainActivity), read back from the
     * video layer without stopping the UI; null (to done) when there is no frame or the TV refuses the copy.
     */
    void snapshot(int width, int height, Consumer<Bitmap> done) {
        if (source == null || !rendering || surface == null || !surface.isValid()) {
            done.accept(null);
            return;
        }
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(view, b, result -> done.accept(result == PixelCopy.SUCCESS ? b : null), main);
        } catch (RuntimeException e) {
            done.accept(null);
        }
    }

    /** Holds the video on its frame while something opaque covers it, so it costs nothing there. */
    void hold(boolean on) {
        held = on;
        apply();
    }

    /**
     * Pauses the video the moment Home is left (an app or the screensaver opening), before Home stops and the player
     * is released; false plays it again (back on Home, or only the Control Center over it). Leaving, the frame on
     * screen is kept as the still for the way back.
     */
    void setAway(boolean on) {
        away = on;
        apply();
        if (on) keepStill();
    }

    private void keepStill() {
        snapshot(960, 540, b -> {
            if (b == null) return;
            still.setImageBitmap(b);   // shown only if the surface goes (surfaceDestroyed)
        });
    }

    private void apply() {
        MediaPlayer p = player;
        if (p == null || !prepared) return;
        boolean play = !held && !away;
        try {
            if (!play && p.isPlaying()) p.pause();
            else if (play && !p.isPlaying()) p.start();
        } catch (IllegalStateException ignored) {
            // released meanwhile
        }
    }

    private void open() {
        if (!wanted || source == null || surface == null || player != null) return;
        // Made here, so its callbacks come back on the main thread; the file and the surface are set on the worker.
        MediaPlayer p = new MediaPlayer();
        player = p;
        prepared = false;
        String path = source.getPath();
        Surface target = surface;
        p.setOnPreparedListener(mp -> {
            if (mp != player) return;
            prepared = true;
            if (!held && !away) mp.start();
        });
        p.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && mp == player) {
                rendering = true;
                shown(true);
            }
            return false;
        });
        p.setOnErrorListener((mp, what, extra) -> {
            if (mp == player) {
                release();
                shown(false);
            }
            return true;
        });
        worker.execute(() -> {
            try {
                p.setDataSource(path);
                p.setSurface(target);
                p.setLooping(true);
                p.setVolume(0f, 0f);
                p.prepareAsync();
            } catch (Exception | Error e) {
                main.post(() -> {
                    if (p == player) release();
                });
            }
        });
    }

    /** Lets go of the player (and its hardware decoder) on the worker, after anything still queued for it. */
    private void release() {
        MediaPlayer p = player;
        player = null;
        prepared = false;
        if (p == null) return;
        worker.execute(() -> {
            try {
                p.reset();
                p.release();
            } catch (Exception ignored) {
                // already gone
            }
        });
    }

    /** The video is on screen (the sky and the still can go) or not (the sky comes back). */
    private void shown(boolean on) {
        Consumer<Boolean> l = coverListener;
        if (l != null) l.accept(on);
        if (on && still.getVisibility() == View.VISIBLE) {
            still.animate().cancel();
            if (animate) {
                still.animate().alpha(0f).setDuration(350).withEndAction(() -> {
                    still.setVisibility(View.INVISIBLE);
                    still.setImageDrawable(null);
                }).start();
            } else {
                still.setVisibility(View.INVISIBLE);
                still.setImageDrawable(null);
            }
        }
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        surface = holder.getSurface();
        open();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        release();
        surface = null;
        rendering = false;
        // The new surface starts black: the last frame covers it until the video draws, or else the sky does.
        if (still.getDrawable() != null) {
            still.animate().cancel();
            still.setAlpha(1f);
            still.setVisibility(View.VISIBLE);
        } else {
            shown(false);
        }
    }
}
