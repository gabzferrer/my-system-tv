package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * My System TV: the showcase over a focused dock app (design A, the Control Center's Modo A + B), after the tvOS top
 * shelf and the Apple TV app's home. What you were watching in that app fills the screen (MainActivity cross-fades its
 * picture in the backdrop layers); this is the text and the buttons over it: the app, the title, the episode and how
 * far along, ▶ Continuar and › for the app's next title, with dots for where you are.
 */
final class Showcase {

    interface Host {
        void onShowcasePlay();

        void onShowcaseNext();

        /** Down from the buttons: back to the dock tile. */
        void onShowcaseDown();
    }

    final LinearLayout view;
    private final TextView kicker, title, meta, play;
    private final FrameLayout bar;
    private final View fill;
    private final ImageView next;
    private final LinearLayout dots;
    private final float density;
    private final boolean animate;

    /**
     * A pill's text colours: a colour selector the layouts keep in drawable/ (pill_text and its kin), read the way
     * android:textColor reads it there.
     */
    @android.annotation.SuppressLint("ResourceType")
    static android.content.res.ColorStateList pillText(Context ctx, int res) {
        return ctx.getColorStateList(res);
    }

    Showcase(Context ctx, Host host, boolean animate) {
        this.animate = animate;
        density = ctx.getResources().getDisplayMetrics().density;
        view = new LinearLayout(ctx);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
        view.setClipChildren(false);
        view.setClipToPadding(false);
        // Room for the focused buttons' 8% zoom: Home clips each child to its own bounds, so without it the edges of
        // ▶ Continuar and the arrow were cut off. MainActivity takes the same 8dp off the margin.
        view.setPadding(dp(8), dp(8), dp(8), dp(8));

        kicker = text(ctx, 12, false, 0xFFFFFFFF);
        // Light and white, like the title under it.
        kicker.setTypeface(UiFont.get(ctx, R.string.font_light));
        kicker.setLetterSpacing(0.16f);
        kicker.setAllCaps(true);
        view.addView(kicker);

        title = text(ctx, 52, false, 0xFFFFFFFF);
        // Light, like the clock and the big app name: chosen over the bold first version.
        title.setTypeface(UiFont.get(ctx, R.string.font_light));
        title.setLetterSpacing(-0.01f);
        title.setShadowLayer(20 * density, 0, 3 * density, 0x80000000);
        title.setMaxLines(2);
        title.setLineSpacing(0, 0.92f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(6);
        view.addView(title, tp);

        LinearLayout metaRow = new LinearLayout(ctx);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        meta = text(ctx, 13, false, 0xD9FFFFFF);
        meta.setMaxWidth(dp(380));
        metaRow.addView(meta);
        bar = new FrameLayout(ctx);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x47FFFFFF);
        track.setCornerRadius(dp(2));
        bar.setBackground(track);
        fill = new View(ctx);
        GradientDrawable f = new GradientDrawable();
        f.setColor(0xFFFFFFFF);
        f.setCornerRadius(dp(2));
        fill.setBackground(f);
        bar.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(120), dp(3));
        bp.setMarginStart(dp(9));
        metaRow.addView(bar, bp);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(9);
        view.addView(metaRow, mp);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setClipChildren(false);
        play = text(ctx, 13, true, 0);
        play.setText("▶  " + ctx.getString(R.string.showcase_continue));
        play.setTextColor(pillText(ctx, R.drawable.pill_text));
        play.setGravity(Gravity.CENTER);
        play.setPadding(dp(14), 0, dp(16), 0);
        play.setBackground(button(false));
        play.setFocusable(true);
        play.setClickable(true);
        play.setOnClickListener(v -> host.onShowcasePlay());
        buttons.addView(play, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        next = new ImageView(ctx);
        next.setImageResource(R.drawable.ic_chevron_right);
        next.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF111318, 0xFFFFFFFF}));
        next.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        next.setPadding(dp(8), dp(8), dp(8), dp(8));
        next.setBackground(button(true));
        next.setFocusable(true);
        next.setClickable(true);
        next.setContentDescription(ctx.getString(R.string.showcase_next));
        next.setOnClickListener(v -> host.onShowcaseNext());
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(36), dp(36));
        np.setMarginStart(dp(9));
        buttons.addView(next, np);
        dots = new LinearLayout(ctx);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.setMarginStart(dp(12));
        buttons.addView(dots, dotsLp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(14);
        view.addView(buttons, blp);

        View.OnFocusChangeListener grow = (v, focused) -> v.animate().scaleX(focused ? 1.08f : 1f)
                .scaleY(focused ? 1.08f : 1f).setDuration(150).start();
        play.setOnFocusChangeListener(grow);
        next.setOnFocusChangeListener(grow);
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                host.onShowcaseDown();
                return true;
            }
            // The buttons' ends hold the focus, so it doesn't jump to a far corner of the screen.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v == play) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v == next;
        };
        play.setOnKeyListener(keys);
        next.setOnKeyListener(keys);
    }

    /** Shows this title: index and count place the dots; count 1 hides the arrow. */
    void bind(String appName, AppContent.Item item, int index, int count) {
        Runnable fillIn = () -> {
            Context c = kicker.getContext();
            // Continue watching, or else what the app recommends (YouTube publishes nothing to resume).
            String source = item.continueWatching ? c.getString(R.string.continue_watching)
                    : item.channel != null ? item.channel : c.getString(R.string.showcase_recommended);
            kicker.setText(appName + " · " + source);
            title.setText(item.title);
            String line = item.continueWatching
                    ? (item.episode != null ? item.episode : c.getString(R.string.showcase_continue))
                    : item.description;
            meta.setText(line);
            meta.setVisibility(line == null || line.trim().isEmpty() ? View.GONE : View.VISIBLE);
            play.setText("▶  " + c.getString(item.continueWatching ? R.string.showcase_continue : R.string.showcase_watch));
            float p = item.progress();
            bar.setVisibility(p > 0 ? View.VISIBLE : View.GONE);
            bar.post(() -> {
                ViewGroup.LayoutParams lp = fill.getLayoutParams();
                lp.width = Math.round(bar.getWidth() * Math.max(0f, p));
                fill.setLayoutParams(lp);
            });
            next.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
            dots.removeAllViews();
            for (int i = 0; count > 1 && i < Math.min(count, 8); i++) {
                View d = new View(view.getContext());
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(i == index ? 0xFFFFFFFF : 0x59FFFFFF);
                d.setBackground(g);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(5), dp(5));
                if (i > 0) lp.setMarginStart(dp(5));
                dots.addView(d, lp);
            }
        };
        if (!animate || view.getVisibility() != View.VISIBLE || view.getAlpha() == 0f) {
            fillIn.run();
            return;
        }
        // From one title to the next: a short slide and fade, as the Apple TV app's arrow does.
        title.animate().cancel();
        title.animate().alpha(0f).translationX(-8 * density).setDuration(160).withEndAction(() -> {
            fillIn.run();
            title.setTranslationX(8 * density);
            title.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        }).start();
    }

    void show(boolean on) {
        view.animate().cancel();
        if (on) {
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(1f).setDuration(animate ? 420 : 0).start();
        } else {
            view.animate().alpha(0f).setDuration(animate ? 220 : 0)
                    .withEndAction(() -> view.setVisibility(View.GONE)).start();
        }
    }

    void focusPlay() {
        play.requestFocus();
    }

    boolean owns(View v) {
        return v == play || v == next;
    }

    private Drawable button(boolean oval) {
        GradientDrawable idle = new GradientDrawable();
        idle.setShape(oval ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!oval) idle.setCornerRadius(dp(18));
        // Black glass, like the dock under it; white while focused.
        idle.setColor(0x73000000);
        idle.setStroke(Math.max(1, dp(0.75f)), 0x4DFFFFFF);
        GradientDrawable focused = new GradientDrawable();
        focused.setShape(oval ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!oval) focused.setCornerRadius(dp(18));
        focused.setColor(0xFFFFFFFF);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, idle);
        return s;
    }

    private TextView text(Context ctx, float sp, boolean bold, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(ctx, bold ? R.string.font_medium : R.string.font_regular),
                bold ? Typeface.BOLD : Typeface.NORMAL);
        if (color != 0) t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (sp < 20) t.setSingleLine(true);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
