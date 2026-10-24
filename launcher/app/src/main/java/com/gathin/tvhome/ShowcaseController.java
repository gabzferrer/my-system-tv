package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * My System TV: the showcase, design A (the Control Center's Modo A + B). While a dock app has the focus, its
 * "Continue watching" (or else its recommendations) fills the screen: the title's picture through the backdrop layers
 * (Backdrops), and the text and buttons over the dock (Showcase).
 */
final class ShowcaseController implements Showcase.Host {

    interface Host {
        void openContent(AppContent.Item item);

        /** Down from the buttons: back to the dock tile. */
        void focusDockTile();

        void hideHero();

        /** The showcase went away: the drawn sky's clouds may move again. */
        void onShowcaseGone();
    }

    private final Activity activity;
    private final Host host;
    private final Backdrops backdrops;
    private final HomeBackground background;
    private final View clockBlock;
    private final boolean motion;
    private final float density;
    final Showcase showcase;
    /** What the apps publish to the programme guide; null until MainActivity reads it. */
    @Nullable private AppContent appContent;
    private boolean active;
    private String pkg;
    /** The dock tile's name, shown over the title. */
    private String label;
    private String image;
    private final java.util.Map<String, Integer> index = new java.util.HashMap<>();
    /** The dock's top edge in root coordinates, for placing the showcase's text over it. */
    private float dockTop;

    ShowcaseController(Activity activity, Host host, FrameLayout root, View above, Backdrops backdrops,
                       HomeBackground background, View clockBlock, boolean motion) {
        this.activity = activity;
        this.host = host;
        this.backdrops = backdrops;
        this.background = background;
        this.clockBlock = clockBlock;
        this.motion = motion;
        density = activity.getResources().getDisplayMetrics().density;
        // The showcase's text and buttons, over the backdrop and under the header.
        showcase = new Showcase(activity, this, motion);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        slp.setMarginStart(Math.round(52 * density));   // 60dp, less Showcase's 8dp padding
        root.addView(showcase.view, root.indexOfChild(above) + 1, slp);
        // Its bottom stays over the dock whatever the title's length: a two-line title grows it upwards.
        showcase.view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (active && b - t != ob - ot) place();
        });
    }

    void setContent(@Nullable AppContent content) {
        appContent = content;
    }

    boolean isActive() {
        return active;
    }

    boolean owns(View v) {
        return showcase.owns(v);
    }

    void focusPlay() {
        showcase.focusPlay();
    }

    @Override
    public void onShowcasePlay() {
        AppContent.Item it = item();
        if (it != null) host.openContent(it);
    }

    @Override
    public void onShowcaseNext() {
        if (pkg == null) return;
        index.put(pkg, index.getOrDefault(pkg, 0) + 1);
        bind();
    }

    @Override
    public void onShowcaseDown() {
        host.focusDockTile();
    }

    /** What the showcase cycles through for an app: its Continue watching, or else its recommendations. */
    private List<AppContent.Item> list(String pkg) {
        List<AppContent.Item> cw = appContent.continueWatchingFor(pkg);
        return cw.isEmpty() ? appContent.recommendationsFor(pkg) : cw;
    }

    @Nullable
    private AppContent.Item item() {
        if (pkg == null || appContent == null) return null;
        List<AppContent.Item> items = list(pkg);
        if (items.isEmpty()) return null;
        return items.get(index.getOrDefault(pkg, 0) % items.size());
    }

    /**
     * A dock tile took the focus: this app's showcase (label is the tile's name) when it has something to show, else
     * none. MainActivity calls it only while Modo A is on and the dock layout is up.
     */
    void update(@Nullable String app, String tileLabel, float dockTopInRoot) {
        if (app == null || appContent == null || list(app).isEmpty()) {
            hide();
            return;
        }
        host.hideHero();
        boolean fresh = !active;
        active = true;
        pkg = app;
        label = tileLabel;
        bind();
        // The text sits over the dock, as the big app name did.
        dockTop = dockTopInRoot;
        place();
        showcase.view.post(this::place);
        if (fresh) {
            showcase.show(true);
            clockBlock.animate().cancel();
            clockBlock.animate().alpha(0f).setDuration(motion ? 300 : 0).start();
        }
    }

    private void place() {
        if (showcase.view.getHeight() > 0) showcase.view.setY(dockTop - 46 * density - showcase.view.getHeight());   // 54dp clear of the dock, less the 8dp padding
    }

    private void bind() {
        AppContent.Item it = item();
        if (it == null) {
            hide();
            return;
        }
        List<AppContent.Item> items = list(pkg);
        int i = index.getOrDefault(pkg, 0) % items.size();
        showcase.bind(label, it, i, items.size());
        showArt(it.image);
    }

    /** The title's picture over the whole screen, through the backdrop layers' cross-fade. */
    private void showArt(@Nullable String uri) {
        if (uri == null || uri.equals(image)) return;
        image = uri;
        Bitmap cached = backdrops.cache.get(uri);
        if (cached != null) {
            crossFade(cached);
            return;
        }
        int maxW = activity.getResources().getDisplayMetrics().widthPixels;
        backdrops.loader.execute(() -> {
            String big = fullScreenArt(uri);
            Bitmap decoded = ContinueRow.decode(activity, big, maxW);
            if (decoded == null && !big.equals(uri)) decoded = ContinueRow.decode(activity, uri, maxW);
            Bitmap b = decoded == null ? null : backdrops.showcasePicture(decoded);
            activity.runOnUiThread(() -> {
                if (b == null || activity.isDestroyed()) return;
                backdrops.cache.put(uri, b);
                if (active && uri.equals(image)) crossFade(b);
            });
        });
    }

    /** The showcase's pictures carry their vignette, so the vignette layer stays off. */
    private void crossFade(Bitmap b) {
        // Shorter than the 650 ms of app backdrops: fewer heavy frames.
        backdrops.crossFade(b, motion ? 400 : 0, false, () -> {
            if (active) background.setShowcaseCovered(true);
        });
    }

    /**
     * The same picture at full-screen size, where the app's image server takes the size in its address: HBO Max
     * (discomax, ?w=600) and Disney+ (bamgrid ripcut, width=544&height=306) publish small cards that looked pixelated
     * over the whole screen, while Apple TV and Prime Video already send 1920x1080. Anything else is left as it is.
     */
    private static String fullScreenArt(String uri) {
        if (uri.contains("discomax.com")) return uri.replaceAll("([?&])w=\\d+", "$1w=1920");
        if (uri.contains("bamgrid.com/ripcut-delivery")) {
            return uri.replaceAll("([?&])width=\\d+", "$1width=1920").replaceAll("([?&])height=\\d+", "$1height=1080");
        }
        return uri;
    }

    void hide() {
        if (!active) return;
        active = false;
        pkg = null;
        image = null;
        background.setShowcaseCovered(false);   // the video plays again under the picture as it fades
        showcase.show(false);
        clockBlock.animate().cancel();
        clockBlock.animate().alpha(1f).setDuration(motion ? 300 : 0).start();
        backdrops.fadeOut(motion ? 450 : 0);
        host.onShowcaseGone();
    }
}
