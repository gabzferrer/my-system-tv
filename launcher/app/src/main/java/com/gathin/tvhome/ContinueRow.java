package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * My System TV: the "Seguir viendo" row of the all-apps view, between the dock and the apps. Every app's
 * "Continue watching" (AppContent), most recent first, as cards the size of the app tiles, one per grid column, with
 * the app's name, a progress bar and the title and episode under it. MainActivity places it, fades it with the rows
 * and handles Up and Down out of it; Left and Right stay inside it.
 */
final class ContinueRow {

    interface Host {
        /** A card took the focus. */
        void onContinueFocused(int index);

        /** Up or Down on the card in this column (0-based, counted from the row's first visible card). */
        void onContinueVertical(boolean up, int column);

        /** The card was chosen. */
        void onContinueOpen(AppContent.Item item);
    }

    final LinearLayout view;
    private final RecyclerView list;
    private final LinearLayoutManager layout;
    private final Context ctx;
    private final Host host;
    private final float density;
    private final boolean animate;
    private List<AppContent.Item> items = new ArrayList<>();
    private int cellW, cardW, cardH;

    private static final LruCache<String, Bitmap> art = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final ExecutorService loader = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> appNames = new HashMap<>();

    ContinueRow(Context ctx, Host host, boolean animate) {
        this.ctx = ctx;
        this.host = host;
        this.animate = animate;
        density = ctx.getResources().getDisplayMetrics().density;
        view = new LinearLayout(ctx);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setClipChildren(false);
        view.setClipToPadding(false);
        TextView heading = new TextView(ctx);
        heading.setText(R.string.continue_watching);
        heading.setTextColor(0xFFFFFFFF);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        heading.setTypeface(UiFont.get(ctx, R.string.font_medium), android.graphics.Typeface.BOLD);
        heading.setShadowLayer(8 * density, 0, density, 0x80000000);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22));
        hp.setMarginStart(dp(10));
        view.addView(heading, hp);
        list = new RecyclerView(ctx);
        layout = new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false) {
            /** A screen's worth of cards either side is laid out ahead, so a held arrow always finds the next one. */
            @Override
            protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extra) {
                extra[0] = list.getWidth();
                extra[1] = list.getWidth();
            }

            /** The row scrolls itself in whole cards (moveTo); the stock scroll-to-show left it between columns. */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull android.graphics.Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                return false;
            }
        };
        list.setLayoutManager(layout);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setItemAnimator(null);
        list.setAdapter(new Cards());
        view.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    void setItems(List<AppContent.Item> fresh) {
        items = new ArrayList<>(fresh.subList(0, Math.min(20, fresh.size())));
        firstShown = 0;
        target = -1;
        list.getAdapter().notifyDataSetChanged();
        list.scrollToPosition(0);
    }

    int size() {
        return items.size();
    }

    /** One card per grid column, the size of the app tiles. */
    void setSizes(int cellWidth, int cardWidth, int cardHeight) {
        if (cellWidth == cellW && cardWidth == cardW && cardHeight == cardH) return;
        cellW = cellWidth;
        cardW = cardWidth;
        cardH = cardHeight;
        list.getAdapter().notifyDataSetChanged();
    }

    /** The row's height: the heading, the cards and the two lines of text under them. */
    int height() {
        return dp(22) + cardH + dp(52);
    }

    /** Focuses the card in this column of the row as it is scrolled now. */
    void focusColumn(int column) {
        if (items.isEmpty()) return;
        moveTo(Math.min(items.size() - 1, firstShown + column), true);
    }

    /** The first card in the row's first column: the row scrolls in whole cards, so the cards line up with the grid. */
    private int firstShown;
    /** Where the focus is going, ahead of the layout while an arrow is held; -1 when it has arrived. */
    private int target = -1;

    /**
     * Moves the focus to this card, scrolling the row by whole cards when it would leave the screen: one smooth step
     * for a press, an immediate one while the arrow is held (repeat), so the steps never pile up.
     */
    private void moveTo(int index, boolean immediate) {
        if (index < 0 || index >= items.size() || cellW == 0) return;
        target = index;
        int visible = Math.max(1, list.getWidth() / cellW);
        if (index > firstShown + visible - 1) firstShown = index - visible + 1;
        else if (index < firstShown) firstShown = index;
        int dx = firstShown * cellW - list.computeHorizontalScrollOffset();
        if (dx != 0) {
            if (immediate || !animate) {
                list.stopScroll();
                list.scrollBy(dx, 0);
            } else {
                list.smoothScrollBy(dx, 0, new DecelerateInterpolator(2f), 220);
            }
        }
        focusWhenLaidOut(index, 0);
    }

    private void focusWhenLaidOut(int index, int tries) {
        RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(index);
        if (h != null) {
            h.itemView.findViewById(R.id.card).requestFocus();
            if (target == index) target = -1;
            return;
        }
        if (tries < 5) list.post(() -> focusWhenLaidOut(index, tries + 1));
    }

    boolean hasFocus() {
        return list.hasFocus();
    }

    /**
     * While the row is faded out (the dock has the focus) its cards take no focus, or Up from the dock found them
     * instead of the top bar.
     */
    void setFocusEnabled(boolean on) {
        // The list itself never takes the focus: blocked, a RecyclerView would otherwise take it in its cards' place.
        list.setFocusable(false);
        list.setDescendantFocusability(on ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private String appName(String pkg) {
        String name = appNames.get(pkg);
        if (name != null) return name;
        try {
            PackageManager pm = ctx.getPackageManager();
            name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            name = pkg;
        }
        appNames.put(pkg, name);
        return name;
    }

    /** A card's art, decoded small for the card (about 480 px wide) and cached; https or content URIs. */
    static void load(Context ctx, String uri, int width, ImageView into) {
        into.setTag(uri);
        if (uri == null) {
            into.setImageDrawable(null);
            return;
        }
        String key = uri + "@" + width;
        Bitmap cached = art.get(key);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        into.setImageDrawable(null);
        Context app = ctx.getApplicationContext();
        loader.execute(() -> {
            Bitmap b = decode(app, uri, width);
            into.post(() -> {
                if (b == null) return;
                art.put(key, b);
                if (uri.equals(into.getTag())) {
                    into.setAlpha(0f);
                    into.setImageBitmap(b);
                    into.animate().alpha(1f).setDuration(200).start();
                }
            });
        });
    }

    static Bitmap decode(Context ctx, String uri, int width) {
        byte[] data;
        try {
            InputStream in;
            HttpURLConnection http = null;
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                http = (HttpURLConnection) new URL(uri).openConnection();
                http.setConnectTimeout(8000);
                http.setReadTimeout(10000);
                in = http.getInputStream();
            } else {
                in = ctx.getContentResolver().openInputStream(android.net.Uri.parse(uri));
            }
            if (in == null) return null;
            try (InputStream s = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
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
        while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private final class Cards extends RecyclerView.Adapter<Cards.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final FrameLayout card;
            final ImageView image;
            final TextView badge, title, sub;
            final View bar, fill, shine;

            Holder(FrameLayout cell) {
                super(cell);
                card = (FrameLayout) cell.getChildAt(0);
                image = (ImageView) card.getChildAt(0);
                badge = (TextView) card.getChildAt(1);
                bar = card.getChildAt(2);
                fill = ((ViewGroup) bar).getChildAt(0);
                shine = card.getChildAt(3);
                LinearLayout words = (LinearLayout) cell.getChildAt(1);
                title = (TextView) words.getChildAt(0);
                sub = (TextView) words.getChildAt(1);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout cell = new FrameLayout(ctx);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);

            FrameLayout card = new FrameLayout(ctx);
            card.setId(R.id.card);
            card.setFocusable(true);
            card.setClickable(true);
            card.setBackgroundColor(0xFF1B1D26);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), AppAdapter.cornerRadius(v.getWidth(), density));
                }
            });
            card.setClipToOutline(true);
            ImageView image = new ImageView(ctx);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView badge = new TextView(ctx);
            badge.setTextColor(0xFFFFFFFF);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
            badge.setTypeface(UiFont.get(ctx, R.string.font_medium), android.graphics.Typeface.BOLD);
            badge.setSingleLine(true);
            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setColor(0x99000000);
            pillBg.setCornerRadius(dp(10));
            badge.setBackground(pillBg);
            badge.setPadding(dp(6), dp(2), dp(6), dp(2));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            bp.setMargins(dp(7), dp(6), 0, 0);
            card.addView(badge, bp);

            FrameLayout bar = new FrameLayout(ctx);
            GradientDrawable track = new GradientDrawable();
            track.setColor(0x59FFFFFF);
            track.setCornerRadius(dp(2));
            bar.setBackground(track);
            View fill = new View(ctx);
            GradientDrawable fillBg = new GradientDrawable();
            fillBg.setColor(0xFFFFFFFF);
            fillBg.setCornerRadius(dp(2));
            fill.setBackground(fillBg);
            bar.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3),
                    Gravity.BOTTOM);
            barLp.setMargins(dp(9), 0, dp(9), dp(7));
            card.addView(bar, barLp);

            // The same light band that crosses an app tile when it takes the focus (focus_shine).
            View shine = new View(ctx);
            shine.setBackgroundResource(R.drawable.shine_sweep);
            shine.setVisibility(View.INVISIBLE);
            card.addView(shine, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // The focus ring, as on the app tiles: a white rim over the art while focused.
            GradientDrawable ring = new GradientDrawable();
            ring.setStroke(dp(2), 0xF2FFFFFF);
            ring.setCornerRadius(dp(14));
            StateListDrawable fg = new StateListDrawable();
            fg.addState(new int[]{android.R.attr.state_focused}, ring);
            card.setForeground(fg);

            cell.addView(card, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

            LinearLayout words = new LinearLayout(ctx);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(ctx);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            title.setTypeface(UiFont.get(ctx, R.string.font_medium), android.graphics.Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView sub = new TextView(ctx);
            sub.setTextColor(0xB3FFFFFF);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            sub.setTypeface(UiFont.get(ctx, R.string.font_regular));
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(title);
            words.addView(sub);
            cell.addView(words, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            return new Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            AppContent.Item it = items.get(position);
            h.itemView.setLayoutParams(new RecyclerView.LayoutParams(cellW, height() - dp(22)));
            FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) h.card.getLayoutParams();
            cp.width = cardW;
            cp.height = cardH;
            cp.topMargin = dp(8);
            h.card.setLayoutParams(cp);
            FrameLayout.LayoutParams wp = (FrameLayout.LayoutParams) ((View) h.title.getParent()).getLayoutParams();
            wp.width = cardW;
            wp.topMargin = dp(8) + cardH + dp(9);
            ((View) h.title.getParent()).setLayoutParams(wp);

            load(ctx, it.image, Math.max(cardW * 2, 480), h.image);
            h.badge.setText(appName(it.pkg));
            float p = it.progress();
            h.bar.setVisibility(p > 0 ? View.VISIBLE : View.GONE);
            h.bar.post(() -> {
                ViewGroup.LayoutParams fl = h.fill.getLayoutParams();
                fl.width = Math.round(h.bar.getWidth() * Math.max(0f, p));
                h.fill.setLayoutParams(fl);
            });
            h.title.setText(it.title);
            h.sub.setText(it.episode != null ? it.episode : appName(it.pkg));

            h.card.setOnClickListener(v -> host.onContinueOpen(it));
            h.card.setOnFocusChangeListener((v, focused) -> {
                float s = focused ? 1.1f : 1f;
                if (animate) {
                    v.animate().scaleX(s).scaleY(s).translationZ(focused ? 8 * density : 0f)
                            .setDuration(focused ? 220 : 170).setInterpolator(new DecelerateInterpolator(1.5f)).start();
                } else {
                    v.setScaleX(s);
                    v.setScaleY(s);
                }
                // Both lines step down together, clear of the grown card.
                ((View) h.title.getParent()).animate().translationY(focused ? 7 * density : 0f).setDuration(200).start();
                if (focused) {
                    int pos = h.getBindingAdapterPosition();
                    host.onContinueFocused(pos);
                    if (animate) sweep(h.shine, v.getWidth());
                }
            });
            h.card.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = h.getBindingAdapterPosition();
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    host.onContinueVertical(keyCode == KeyEvent.KEYCODE_DPAD_UP, Math.max(0, pos - firstShown));
                    return true;
                }
                // Left and Right are all handled here, from where the focus is going rather than where the layout
                // has got to, so a held arrow neither skips a card nor escapes the row; its ends hold the focus.
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int from = target >= 0 ? target : pos;
                    int to = from + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    if (to >= 0 && to < items.size()) {
                        moveTo(to, event.getRepeatCount() > 0);
                        v.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                                ? android.view.SoundEffectConstants.NAVIGATION_RIGHT
                                : android.view.SoundEffectConstants.NAVIGATION_LEFT);
                    }
                    return true;
                }
                return false;
            });
        }

        private void sweep(View shine, int width) {
            shine.animate().cancel();
            float band = width * 0.6f;
            shine.setScaleX(0.6f);
            shine.setTranslationX(-width * 0.5f - band);
            shine.setAlpha(1f);
            shine.setVisibility(View.VISIBLE);
            shine.animate().translationX(width * 0.5f + band).setStartDelay(90).setDuration(650)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .withEndAction(() -> shine.setVisibility(View.INVISIBLE)).start();
        }

        @Override
        public int getItemCount() {
            return cellW == 0 ? 0 : items.size();
        }
    }
}
