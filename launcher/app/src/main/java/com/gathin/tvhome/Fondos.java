package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * My System TV: Home's backgrounds, as in the preview. The videos live in the theme folder's videos/ (pushed
 * over adb, each with a still of the same name for the Control Center's picker); "sky" is the drawn sky that was
 * there before. The choice is kept in shared preferences, so the Control Center can change it while Home listens.
 */
final class Fondos {

    static final String PREFS = "fondo";
    static final String KEY = "id";
    static final String SKY = "sky";
    static final String DEFAULT = "gg-sunset";
    /** The Control Center's Modo A + B: the showcase over a focused dock app (MainActivity, Showcase). */
    static final String VITRINA = "vitrina";

    /** id, name: the order the picker lists them in. */
    private static final String[][] ALL = {
            {"gg-sunset", "Atardecer"},
            {"gg-night", "Puente de noche"},
            {"sequoia", "Sequoia"},
            {"noche", "Noche"},
            {"utah", "Utah"},
            {SKY, "Cielo"},
    };

    static final class Fondo {
        final String id;
        final String name;
        /** The video, or null for the sky. */
        @Nullable final File video;
        /** The picker's still, or null. */
        @Nullable final File still;

        Fondo(String id, String name, @Nullable File video, @Nullable File still) {
            this.id = id;
            this.name = name;
            this.video = video;
            this.still = still;
        }
    }

    private Fondos() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The backgrounds this TV has: the videos that were pushed, then the sky. */
    static List<Fondo> available(Context ctx) {
        File dir = Config.dir(ctx);
        File videos = dir == null ? null : new File(dir, "videos");
        List<Fondo> out = new ArrayList<>();
        for (String[] f : ALL) {
            if (SKY.equals(f[0])) {
                out.add(new Fondo(SKY, f[1], null, null));
                continue;
            }
            File video = videos == null ? null : new File(videos, f[0] + ".mp4");
            if (video == null || !video.isFile()) continue;
            File still = new File(videos, f[0] + ".jpg");
            out.add(new Fondo(f[0], f[1], video, still.isFile() ? still : null));
        }
        return out;
    }

    /** The chosen background, or the first one there is when the choice is gone. */
    static Fondo current(Context ctx) {
        List<Fondo> all = available(ctx);
        String id = prefs(ctx).getString(KEY, DEFAULT);
        for (Fondo f : all) if (f.id.equals(id)) return f;
        return all.get(0);
    }

    static void choose(Context ctx, String id) {
        prefs(ctx).edit().putString(KEY, id).apply();
    }
}
