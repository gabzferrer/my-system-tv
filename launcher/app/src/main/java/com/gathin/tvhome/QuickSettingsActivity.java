package com.gathin.tvhome;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
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
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The panel the remote's Settings button (HomeKeyService, settings_key_panel) and the top bar's Settings button open:
 * My System TV's Control Center, as in the preview. Glass modules on a four-column grid:
 *
 * <pre>
 *   [ Wi-Fi     ][ Fondo pill       ]
 *   [  (tall)   ][Organiz.][Modo    ]
 *   [ Bluetooth ][ Ajustes pill     ]
 *   [  (tall)   ][ Launcher pill    ]
 * </pre>
 *
 * Fondo turns the panel into the background picker (Fondos); Back returns to the grid. Translucent and in a task
 * of its own, so the app behind keeps running and Back returns to it. Pressing Settings again closes the panel.
 */
public final class QuickSettingsActivity extends Activity {

    /** The grid, in dp: four 42dp columns and 6dp gaps across the panel's 186dp. */
    private static final int COL = 42, GAP = 6, ROW = 46;
    private static final int BLUE = 0xFF0A84FF, INK = 0xFF111318;

    private float density;
    private FrameLayout panel;
    private final Map<String, View> items = new HashMap<>();
    private boolean picking;
    /** The opening animation ran: going back from the picker or flipping a switch rebuilds without it. */
    private boolean unfolded;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        density = getResources().getDisplayMetrics().density;
        View scrim = findViewById(R.id.scrim);
        scrim.setOnClickListener(v -> finish());
        // After the Apple TV app's sidebar: the screen darkens towards the panel while it unfolds.
        // Only over the right 60% of the screen: the gradient is clear beyond it, and the panel's window paid a
        // full-screen fill for it on every frame.
        View dim = new View(this);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{0x8C000000, 0x33000000, 0x00000000}));
        dim.setAlpha(0f);
        ((FrameLayout) scrim).addView(dim, 0, new FrameLayout.LayoutParams(
                Math.round(getResources().getDisplayMetrics().widthPixels * 0.6f), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END));
        dim.animate().alpha(1f).setDuration(280).start();
        panel = findViewById(R.id.panel);
        panel.setOnClickListener(v -> { });   // a click on the panel itself does not close it
        showGrid("wifi");
    }

    // ---- the grid ----

    private void showGrid(String focus) {
        picking = false;
        panel.removeAllViews();
        items.clear();

        Boolean bt = bluetoothOn();
        big("wifi", 0, 0, R.drawable.ic_wifi, getString(R.string.wifi), wifiStatus(),
                SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        big("bt", 0, 2, R.drawable.ic_bluetooth, getString(R.string.quick_bluetooth),
                bt == null ? getString(R.string.settings) : getString(bt ? R.string.cc_on : R.string.cc_off),
                SystemSettings.intent(this, Settings.ACTION_BLUETOOTH_SETTINGS));
        View fondo = pill("fondo", 2, 0, 2, R.drawable.ic_picture, getString(R.string.cc_fondo),
                Fondos.current(this).name, null);
        fondo.setOnClickListener(v -> showPicker());
        round("organizar", 2, 1, R.drawable.ic_swap_vert, getString(R.string.cc_reorder),
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_REORDER));
        // Where Imagen was: the switch for the showcase (design A). Off, Home shows only the Seguir viendo row
        // (Modo B); on, the circle turns blue and a focused dock app also fills the screen (Modo A + B).
        boolean vitrina = Fondos.prefs(this).getBoolean(Fondos.VITRINA, false);
        round("modo", 3, 1, R.drawable.ic_picture, getString(vitrina ? R.string.cc_mode_ab : R.string.cc_mode_b), null);
        View modo = items.get("modo");
        if (vitrina) {
            GradientDrawable on = new GradientDrawable();
            on.setShape(GradientDrawable.OVAL);
            on.setColor(BLUE);
            GradientDrawable focusedOn = new GradientDrawable();
            focusedOn.setShape(GradientDrawable.OVAL);
            focusedOn.setColor(0xF7FAFAFC);
            StateListDrawable lit = new StateListDrawable();
            lit.addState(new int[]{android.R.attr.state_focused}, focusedOn);
            lit.addState(new int[]{}, on);
            modo.setBackground(lit);
        }
        modo.setOnClickListener(v -> {
            Fondos.prefs(this).edit().putBoolean(Fondos.VITRINA, !vitrina).apply();
            showGrid("modo");   // the label and the colour change in place; Home picks it up on its own
        });
        pill("ajustes", 2, 2, 2, R.drawable.ic_settings, getString(R.string.settings), null,
                SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        pill("launcher", 2, 3, 2, R.drawable.ic_home_settings, getString(R.string.cc_launcher), null,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));

        // The arrows: each move goes to the module the eye expects, not the nearest edge.
        link("wifi", null, "fondo", null, "bt");
        link("fondo", "wifi", null, null, "organizar");
        link("organizar", "wifi", "modo", "fondo", "ajustes");
        link("modo", "organizar", null, "fondo", "ajustes");
        link("bt", null, "ajustes", "wifi", null);
        link("ajustes", "bt", null, "organizar", "launcher");
        link("launcher", "bt", null, "ajustes", null);
        View first = items.get(focus);
        if (first != null) first.requestFocus();
        if (!unfolded) {
            unfolded = true;
            unfold();
        }
    }

    /** First opening only: the modules drop in from under the gear one after another, as the sidebar's rows do. */
    private void unfold() {
        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        for (int i = 0; i < panel.getChildCount(); i++) {
            View v = panel.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(-18 * density);
            v.setScaleX(0.92f);
            v.setScaleY(0.92f);
            float end = v.isFocused() ? 1.04f : 1f;   // the focused module ends at its focus zoom
            // withLayer: each module is drawn once into a layer and only that moves and fades; without it every
            // frame redrew every fading module through an offscreen buffer (about 28 ms of GPU a frame on this TV).
            v.animate().alpha(1f).translationY(0f).scaleX(end).scaleY(end).setStartDelay(i * 28L).setDuration(340)
                    .setInterpolator(ease).withLayer().start();
        }
    }

    private int x(int col) {
        return dp(col * (COL + GAP));
    }

    private int y(int row) {
        return dp(row * (ROW + GAP));
    }

    private int width(int cols) {
        return dp(cols * COL + (cols - 1) * GAP);
    }

    private int height(int rows, int fromRow) {
        return dp(rows * ROW + (rows - 1) * GAP);
    }

    /** Wi-Fi and Bluetooth: a tall module two columns wide, the icon at the top, name and state at the bottom. */
    private void big(String id, int col, int row, int icon, String title, String status, Intent target) {
        LinearLayout v = module(id, LinearLayout.VERTICAL, glass(14, false));
        v.setPadding(dp(9), dp(8), dp(9), dp(8));
        v.addView(disc(icon, 25, 13));
        View spring = new View(this);
        v.addView(spring, new LinearLayout.LayoutParams(0, 0, 1f));
        v.addView(text(title, 10.5f, true, null));
        v.addView(text(status, 8f, false, "status"));
        v.setOnClickListener(c -> open(target));
        place(v, col, row, 2, 2);
    }

    /** Fondo, Ajustes, Launcher: a capsule with the icon disc and the name (and state) beside it. */
    private View pill(String id, int col, int row, int cols, int icon, String title, String status, Intent target) {
        LinearLayout v = module(id, LinearLayout.HORIZONTAL, glass(23, false));
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(7), 0, dp(7), 0);
        v.addView(disc(icon, 22, 11.5f));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setDuplicateParentStateEnabled(true);
        words.addView(text(title, 10.5f, true, null));
        if (status != null) words.addView(text(status, 8f, false, "status"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(5));
        v.addView(words, lp);
        if (target != null) v.setOnClickListener(c -> open(target));
        place(v, col, row, cols, 1);
        return v;
    }

    /** The small shortcuts: a glass circle with the icon, its name underneath. */
    private void round(String id, int col, int row, int icon, String title, Intent target) {
        FrameLayout cell = new FrameLayout(this);
        cell.setClipChildren(false);
        ImageView circle = new ImageView(this);
        circle.setImageResource(icon);
        circle.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{INK, 0xFFFFFFFF}));
        circle.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        circle.setPadding(dp(9), dp(9), dp(9), dp(9));
        circle.setBackground(glass(0, true));
        circle.setFocusable(true);
        circle.setClickable(true);
        circle.setContentDescription(title);
        circle.setOnClickListener(c -> open(target));
        circle.setOnFocusChangeListener((c, f) -> c.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f)
                .setDuration(140).start());
        circle.setId(View.generateViewId());
        items.put(id, circle);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        cell.addView(circle, clp);
        TextView label = text(title, 7f, false, null);
        label.setTextColor(0xFFFFFFFF);
        label.setShadowLayer(4 * density, 0, density / 2, 0x99000000);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(COL + GAP + 8), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        llp.topMargin = dp(36);
        cell.addView(label, llp);
        place(cell, col, row, 1, 1);
    }

    private LinearLayout module(String id, int orientation, Drawable background) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(orientation);
        v.setBackground(background);
        v.setFocusable(true);
        v.setClickable(true);
        v.setId(View.generateViewId());
        v.setOnFocusChangeListener((c, f) -> c.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f)
                .setDuration(140).start());
        items.put(id, v);
        return v;
    }

    private void place(View v, int col, int row, int cols, int rows) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width(cols), height(rows, row));
        lp.leftMargin = x(col);
        lp.topMargin = y(row);
        panel.addView(v, lp);
    }

    private void link(String id, String left, String right, String up, String down) {
        View v = items.get(id);
        if (v == null) return;
        // Nowhere to go stays put, rather than leaving the panel; except Up from the top row, which closes the panel
        // and leaves the focus on the top bar's buttons, as Back does.
        if (up == null) {
            v.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) finish();
                return true;
            });
        }
        v.setNextFocusLeftId(left == null ? v.getId() : items.get(left).getId());
        v.setNextFocusRightId(right == null ? v.getId() : items.get(right).getId());
        v.setNextFocusUpId(up == null ? v.getId() : items.get(up).getId());
        v.setNextFocusDownId(down == null ? v.getId() : items.get(down).getId());
    }

    /** The white disc with a blue icon; blue with a white icon while its module is focused. */
    private ImageView disc(int icon, float sizeDp, float iconDp) {
        ImageView d = new ImageView(this);
        d.setImageResource(icon);
        d.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.round((sizeDp - iconDp) / 2f * density);
        d.setPadding(pad, pad, pad, pad);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, oval(BLUE));
        bg.addState(new int[]{}, oval(0xFFFFFFFF));
        d.setBackground(bg);
        d.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFFFFFFFF, BLUE}));
        d.setDuplicateParentStateEnabled(true);
        d.setElevation(2 * density);
        d.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return d;
    }

    private TextView text(String s, float sp, boolean bold, String tag) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTag(tag);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setDuplicateParentStateEnabled(true);
        t.setTextColor(Showcase.pillText(this, bold ? R.drawable.pill_text : R.drawable.pill_text_dim));
        try {
            t.setTypeface(UiFont.get(this, bold ? R.string.font_medium : R.string.font_regular),
                    bold ? Typeface.BOLD : Typeface.NORMAL);
        } catch (Exception ignored) {
            // the system font is fine
        }
        if (!bold) t.setPadding(0, Math.round(density), 0, 0);
        return t;
    }

    /**
     * Liquid glass without a blur (this TV draws none behind a window): a faint dark base so white text reads over a
     * bright video, a white sheen across it and a light rim. Solid white while focused.
     */
    private Drawable glass(float radiusDp, boolean oval) {
        GradientDrawable base = shape(oval, radiusDp);
        base.setColor(0x2E0C0E16);
        GradientDrawable sheen = shape(oval, radiusDp);
        sheen.setOrientation(GradientDrawable.Orientation.TL_BR);
        sheen.setColors(new int[]{0x4DFFFFFF, 0x1AFFFFFF, 0x29FFFFFF});
        sheen.setStroke(Math.max(1, Math.round(density * 0.75f)), 0x59FFFFFF);
        GradientDrawable focused = shape(oval, radiusDp);
        focused.setColor(0xF7FAFAFC);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, new LayerDrawable(new Drawable[]{base, sheen}));
        return s;
    }

    private GradientDrawable shape(boolean oval, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(oval ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!oval) g.setCornerRadius(radiusDp * density);
        return g;
    }

    private static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---- the background picker ----

    /** Fondo: the backgrounds this TV has, two across, each with its still; the chosen one carries a check. */
    private void showPicker() {
        picking = true;
        panel.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(glass(16, false));
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView title = text(getString(R.string.cc_fondo), 12f, true, null);
        title.setTextColor(0xFFFFFFFF);
        box.addView(title);
        TextView hint = text(getString(R.string.cc_fondo_hint), 8f, false, null);
        hint.setTextColor(0xBFFFFFFF);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(2);
        hlp.bottomMargin = dp(6);
        box.addView(hint, hlp);

        List<Fondos.Fondo> all = Fondos.available(this);
        String current = Fondos.current(this).id;
        View focus = null;
        LinearLayout row = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
                if (i > 0) rlp.topMargin = dp(5);
                box.addView(row, rlp);
            }
            Fondos.Fondo f = all.get(i);
            View thumb = thumb(f, f.id.equals(current));
            if (i < 2) {
                // Up from the first row goes back to the grid, on the Fondo pill.
                thumb.setOnKeyListener((view, keyCode, event) -> {
                    if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;
                    if (event.getAction() == KeyEvent.ACTION_DOWN) showGrid("fondo");
                    return true;
                });
            }
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i % 2 == 1) tlp.setMarginStart(dp(5));
            row.addView(thumb, tlp);
            if (f.id.equals(current)) focus = thumb;
        }
        if (all.size() % 2 == 1 && row != null) row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
        panel.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (focus != null) focus.requestFocus();
    }

    private View thumb(Fondos.Fondo f, boolean chosen) {
        FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setClickable(true);
        float r = 10 * density;
        t.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        t.setClipToOutline(true);
        ImageView still = new ImageView(this);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (f.still != null) {
            still.setImageBitmap(BitmapFactory.decodeFile(f.still.getPath()));
        } else {
            // The sky: the night colours of the drawn one.
            still.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF05070F, 0xFF141634, 0xFF3A2148, 0xFF6A3A4A}));
        }
        t.addView(still, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView name = text(f.name + (chosen ? "  ✓" : ""), 8f, true, null);
        name.setTextColor(0xFFFFFFFF);
        name.setShadowLayer(5 * density, 0, density, 0xCC000000);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        nlp.setMargins(dp(6), 0, dp(4), dp(5));
        t.addView(name, nlp);
        // The focus ring: a white rim over the still.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(r);
        ring.setStroke(dp(2), 0xFFFFFFFF);
        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(r);
        rim.setStroke(Math.max(1, dp(0.75f)), 0x40FFFFFF);
        StateListDrawable fg = new StateListDrawable();
        fg.addState(new int[]{android.R.attr.state_focused}, ring);
        fg.addState(new int[]{}, rim);
        t.setForeground(fg);
        t.setOnClickListener(v -> {
            Fondos.choose(this, f.id);
            showPicker();   // the check moves; Home behind switches on its own
        });
        return t;
    }

    @Override
    public void onBackPressed() {
        if (picking) showGrid("fondo");
        else super.onBackPressed();
    }

    // ---- lifecycle and system state ----

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MainActivity.panelOver();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    /** The Wi-Fi network's name, "Ethernet", or offline. */
    private String wifiStatus() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (ethernet) return getString(R.string.ethernet);
            if (!wifi) return getString(R.string.offline);
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            String ssid = info != null ? info.getSSID() : null;
            if (ssid != null) {
                ssid = ssid.replace("\"", "").trim();
                if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) return ssid;
            }
            return getString(R.string.cc_on);
        } catch (Exception e) {
            return getString(R.string.wifi);
        }
    }

    /** Whether Bluetooth is on, or null when this TV will not say. */
    private Boolean bluetoothOn() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter == null ? null : adapter.isEnabled();
        } catch (Exception e) {
            return null;
        }
    }

    private void open(Intent target) {
        MainActivity.panelLeaving();
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
