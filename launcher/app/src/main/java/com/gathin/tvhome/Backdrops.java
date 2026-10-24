package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * My System TV: the two full-screen picture layers behind Home's tiles and their vignette, shared by the showcase
 * (ShowcaseController) and app_backdrops (MainActivity). A new picture cross-fades in on the layer not showing;
 * pictures are decoded on one loader thread and the last few kept.
 */
final class Backdrops {

    private final Activity activity;
    private final ImageView a, b;
    private final View vignetteView;
    @Nullable private final SkyClouds skyClouds;
    /** Which layer shows the current picture. */
    private boolean onA;
    final android.util.LruCache<String, Bitmap> cache = new android.util.LruCache<>(3);
    final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newSingleThreadExecutor();
    private Bitmap vignette;

    Backdrops(Activity activity, @Nullable SkyClouds skyClouds) {
        this.activity = activity;
        this.skyClouds = skyClouds;
        a = activity.findViewById(R.id.backdropA);
        b = activity.findViewById(R.id.backdropB);
        vignetteView = activity.findViewById(R.id.backdropVignette);
        // The showcase (design A) uses the backdrop layers too, so the vignette is always made.
        vignetteView.setBackground(vignetteDrawable());
    }

    /** Both layers are faded out. */
    boolean clear() {
        return a.getAlpha() == 0f && b.getAlpha() == 0f;
    }

    /**
     * Fades this picture in over the one showing, in ms; withVignette fades the vignette layer in too (the showcase's
     * pictures carry their own, so it goes off at once). after runs when the picture is fully up.
     */
    void crossFade(Bitmap bmp, long ms, boolean withVignette, Runnable after) {
        ImageView in = onA ? b : a;
        ImageView out = onA ? a : b;
        onA = !onA;
        in.animate().cancel();
        out.animate().cancel();
        in.setImageBitmap(bmp);
        in.animate().alpha(1f).setDuration(ms).withEndAction(after).start();
        out.animate().alpha(0f).setDuration(ms).start();
        vignetteView.animate().cancel();
        if (withVignette) vignetteView.animate().alpha(1f).setDuration(ms).start();
        else vignetteView.setAlpha(0f);
        if (skyClouds != null) skyClouds.stop();   // hidden under the picture: no need to move them
    }

    /** Both layers and the vignette fade away, back to the background. */
    void fadeOut(long ms) {
        for (View v : new View[]{a, b, vignetteView}) {
            v.animate().cancel();
            v.animate().alpha(0f).setDuration(ms).start();
        }
    }

    /** The theme's backdrops/<package>.jpg (or .png) for this app, or null when it has none. */
    @Nullable
    File backdropFile(String pkg) {
        File dir = Config.dir(activity);
        if (dir == null) return null;
        for (String ext : new String[]{".jpg", ".jpeg", ".png", ".webp"}) {
            File f = new File(new File(dir, "backdrops"), pkg + ext);
            if (f.isFile()) return f;
        }
        return null;
    }

    @Nullable
    static Bitmap decodeBackdrop(File f, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= maxW) o.inSampleSize *= 2;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** An app's artwork by URI: https (read into memory, at most 6 MB) or content / android.resource. */
    @Nullable
    Bitmap decodeBackdropUri(String uri, int maxW) {
        byte[] data;
        try {
            java.io.InputStream in;
            java.net.HttpURLConnection http = null;
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                http = (java.net.HttpURLConnection) new java.net.URL(uri).openConnection();
                http.setConnectTimeout(8000);
                http.setReadTimeout(10000);
                in = http.getInputStream();
            } else {
                in = activity.getContentResolver().openInputStream(Uri.parse(uri));
            }
            if (in == null) return null;
            try (java.io.InputStream s = in) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = s.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 6 * 1024 * 1024) return null;
                }
                data = out.toByteArray();
            } finally {
                if (http != null) http.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= maxW) o.inSampleSize *= 2;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    /**
     * The showcase's picture, made once on the loader thread: the vignette painted into it (no full-screen shade layer
     * on every frame) and the result moved to the GPU there (Config.HARDWARE), so showing it doesn't stall a frame
     * uploading 4 MB; that cost up to 100 ms on the first look at each title.
     */
    Bitmap showcasePicture(Bitmap decoded) {
        try {
            Bitmap work = decoded.copy(Bitmap.Config.ARGB_8888, true);
            if (work == null) return decoded;
            Canvas c = new Canvas(work);
            Bitmap shade = vignetteBitmap();
            c.drawBitmap(shade, null, new Rect(0, 0, work.getWidth(), work.getHeight()), new Paint(Paint.FILTER_BITMAP_FLAG));
            Bitmap gpu = work.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return work;
            work.recycle();
            decoded.recycle();
            return gpu;
        } catch (RuntimeException | OutOfMemoryError e) {
            return decoded;
        }
    }

    /** vignetteDrawable's small bitmap, made once and shared. */
    private synchronized Bitmap vignetteBitmap() {
        if (vignette == null) vignette = ((android.graphics.drawable.BitmapDrawable) vignetteDrawable()).getBitmap();
        return vignette;
    }

    /**
     * Darkens the edges and the bottom over a picture, as the prototype's vignette does. One small stretched bitmap.
     * My System TV: plain black; the navy it had read as a blue band over light videos.
     */
    private android.graphics.drawable.Drawable vignetteDrawable() {
        int w = 480, h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        p.setShader(new android.graphics.RadialGradient(w * 0.5f, h * 0.4f, w * 0.62f,
                new int[]{0x00000000, 0x00000000, 0x99000000}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x59000000, 0x14000000, 0x14000000, 0xD9000000}, new float[]{0f, 0.3f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(activity.getResources(), bmp);
        d.setGravity(Gravity.FILL);
        return d;
    }
}
