package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Content the apps themselves publish to the TV's programme guide (TvProvider): the "Continue watching" row
 * (watch_next_program) and the rows of their preview channels (preview_program). This is what the stock Google
 * TV home showed per app; with READ_TV_LISTINGS (a runtime permission, "TV listings") a launcher may read every
 * row an app marked searchable. Read off the UI thread when Home comes to the front; a report of what was found is
 * written to the app's files folder (content-report.txt) so it can be checked over ADB.
 */
final class AppContent {

    /** One programme: what a Seguir viendo card, the showcase and the backdrop show. */
    static final class Item {
        final String pkg;
        final String title;
        final String description;
        /** "T2 E5 · Name of the episode", or null for a film. */
        final String episode;
        final String image;   // poster or thumbnail URI: https, content or android.resource
        final long position, duration, lastEngaged;
        /** Opens the programme where it was left (the app's own deep link), or null. */
        final String intentUri;
        final boolean continueWatching;
        /** For a channel's programme, the channel's name ("Recomendaciones para ti"); null for Continue watching. */
        String channel;

        Item(String pkg, String title, String description, String episode, String image, long position, long duration,
             long lastEngaged, String intentUri, boolean continueWatching) {
            this.pkg = pkg;
            this.title = title;
            this.description = description;
            this.episode = episode;
            this.image = image;
            this.position = position;
            this.duration = duration;
            this.lastEngaged = lastEngaged;
            this.intentUri = intentUri;
            this.continueWatching = continueWatching;
        }

        /** How far along, 0..1, or -1 when the app didn't say. */
        float progress() {
            return duration > 0 && position > 0 ? Math.min(1f, position / (float) duration) : -1f;
        }
    }

    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final Uri CHANNELS = Uri.parse("content://android.media.tv/channel");
    private static final String[] WATCH_NEXT_COLUMNS = {"package_name", "title", "short_description",
            "poster_art_uri", "thumbnail_uri", "season_display_number", "episode_display_number", "episode_title",
            "last_playback_position_millis", "duration_millis", "last_engagement_time_utc_millis", "intent_uri",
            "watch_next_type", "poster_art_aspect_ratio", "thumbnail_aspect_ratio"};
    private static final String[] PREVIEW_COLUMNS = {"package_name", "title", "short_description",
            "poster_art_uri", "thumbnail_uri", "channel_id", "intent_uri", "poster_art_aspect_ratio",
            "thumbnail_aspect_ratio"};

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Map<String, List<Item>> byPackage = new HashMap<>();
    private volatile List<Item> continueWatching = new ArrayList<>();
    private volatile Map<String, List<Item>> recommendations = new HashMap<>();
    private boolean loading;

    AppContent(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Re-reads the guide in the background, then runs {@code done} on the UI thread. */
    void refresh(Runnable done) {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            Map<String, List<Item>> fresh = new HashMap<>();
            Map<String, List<Item>> recs = new HashMap<>();
            List<Item> cw = new ArrayList<>();
            StringBuilder report = new StringBuilder();
            Map<Long, String> channels = readChannels(report);
            readWatchNext(fresh, cw, report);
            readPreview(fresh, recs, channels, report);
            // Most recent first, as the stock row orders it.
            Collections.sort(cw, (a, b) -> Long.compare(b.lastEngaged, a.lastEngaged));
            writeReport(cw, report);
            main.post(() -> {
                byPackage = fresh;
                continueWatching = cw;
                recommendations = recs;
                loading = false;
                if (done != null) done.run();
            });
        }, "app-content").start();
    }

    /** The programmes an app published: its "Continue watching" entries first, then its channel rows. */
    List<Item> itemsFor(String pkg) {
        List<Item> items = byPackage.get(pkg);
        return items != null ? items : new ArrayList<>();
    }

    /** Every app's "Continue watching", most recently watched first. */
    List<Item> continueWatching() {
        return continueWatching;
    }

    /** What this app recommends on its channels (YouTube's "Recomendaciones para ti", "Tendencias"), up to 12. */
    List<Item> recommendationsFor(String pkg) {
        List<Item> items = recommendations.get(pkg);
        return items != null ? items : new ArrayList<>();
    }

    /** Only this app's "Continue watching", most recent first. */
    List<Item> continueWatchingFor(String pkg) {
        List<Item> out = new ArrayList<>();
        for (Item i : continueWatching) if (i.pkg.equals(pkg)) out.add(i);
        return out;
    }

    private Map<Long, String> readChannels(StringBuilder report) {
        Map<Long, String> names = new HashMap<>();
        try (Cursor c = ctx.getContentResolver().query(CHANNELS,
                new String[]{"_id", "package_name", "display_name", "type"}, null, null, null)) {
            if (c == null) return names;
            report.append(CHANNELS).append(": ").append(c.getCount()).append(" rows\n");
            while (c.moveToNext()) {
                names.put(c.getLong(0), c.getString(2));
                report.append("  canal ").append(c.getLong(0)).append(' ').append(c.getString(1)).append(" «")
                        .append(c.getString(2)).append("» ").append(c.getString(3)).append('\n');
            }
        } catch (Exception e) {
            report.append(CHANNELS).append(": ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
        }
        return names;
    }

    private void readWatchNext(Map<String, List<Item>> into, List<Item> cw, StringBuilder report) {
        ContentResolver cr = ctx.getContentResolver();
        try (Cursor c = cr.query(WATCH_NEXT, WATCH_NEXT_COLUMNS, null, null, null)) {
            if (c == null) {
                report.append(WATCH_NEXT).append(": no answer\n");
                return;
            }
            report.append(WATCH_NEXT).append(": ").append(c.getCount()).append(" rows\n");
            while (c.moveToNext()) {
                String pkg = c.getString(0);
                String title = c.getString(1);
                if (pkg == null || title == null || title.trim().isEmpty()) continue;
                String image = landscape(c.getString(3), c.isNull(13) ? -1 : c.getInt(13), c.getString(4),
                        c.isNull(14) ? -1 : c.getInt(14));
                Item item = new Item(pkg, title.trim(), c.getString(2), episode(c.getString(5), c.getString(6), c.getString(7)),
                        image, c.getLong(8), c.getLong(9), c.getLong(10), c.getString(11), true);
                cw.add(item);
                List<Item> list = into.get(pkg);
                if (list == null) into.put(pkg, list = new ArrayList<>());
                if (list.size() < 12) list.add(item);
            }
        } catch (Exception e) {
            report.append(WATCH_NEXT).append(": ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
        }
    }

    private void readPreview(Map<String, List<Item>> into, Map<String, List<Item>> recs, Map<Long, String> channels,
                             StringBuilder report) {
        Map<String, Integer> perChannel = new HashMap<>();
        try (Cursor c = ctx.getContentResolver().query(PREVIEW, PREVIEW_COLUMNS, null, null, null)) {
            if (c == null) {
                report.append(PREVIEW).append(": no answer\n");
                return;
            }
            report.append(PREVIEW).append(": ").append(c.getCount()).append(" rows\n");
            while (c.moveToNext()) {
                String pkg = c.getString(0);
                String title = c.getString(1);
                if (pkg == null || title == null || title.trim().isEmpty()) continue;
                String channel = pkg + " «" + channels.get(c.getLong(5)) + "»";
                perChannel.put(channel, perChannel.getOrDefault(channel, 0) + 1);
                String image = landscape(c.getString(3), c.isNull(7) ? -1 : c.getInt(7), c.getString(4),
                        c.isNull(8) ? -1 : c.getInt(8));
                Item item = new Item(pkg, title.trim(), c.getString(2), null, image, 0, 0, 0, c.getString(6), false);
                item.channel = channels.get(c.getLong(5));
                List<Item> list = into.get(pkg);
                if (list == null) into.put(pkg, list = new ArrayList<>());
                if (list.size() < 12) list.add(item);
                List<Item> rec = recs.get(pkg);
                if (rec == null) recs.put(pkg, rec = new ArrayList<>());
                if (rec.size() < 12 && image != null) rec.add(item);
            }
        } catch (Exception e) {
            report.append(PREVIEW).append(": ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
        }
        report.append("programas por canal:\n");
        for (Map.Entry<String, Integer> e : perChannel.entrySet()) {
            report.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
    }

    /**
     * The wide picture for a card or the showcase: the thumbnail or the poster, whichever the app marked 16:9, 3:2 or
     * 4:3 (TvContract's aspect ratios 0-2), the thumbnail first; failing that whichever there is.
     */
    private static String landscape(String poster, int posterRatio, String thumb, int thumbRatio) {
        boolean hasPoster = poster != null && !poster.isEmpty(), hasThumb = thumb != null && !thumb.isEmpty();
        if (hasThumb && thumbRatio >= 0 && thumbRatio <= 2) return thumb;
        if (hasPoster && posterRatio >= 0 && posterRatio <= 2) return poster;
        return hasThumb ? thumb : hasPoster ? poster : null;
    }

    /** "T2 E5 · The episode", "E5", or null when the app gave neither. */
    private static String episode(String season, String number, String name) {
        StringBuilder s = new StringBuilder();
        if (season != null && !season.isEmpty()) s.append('T').append(season).append(' ');
        if (number != null && !number.isEmpty()) s.append('E').append(number);
        String head = s.toString().trim();
        if (name != null && !name.trim().isEmpty()) return head.isEmpty() ? name.trim() : head + " · " + name.trim();
        return head.isEmpty() ? null : head;
    }

    private void writeReport(List<Item> cw, StringBuilder report) {
        File dir = Config.dir(ctx);
        if (dir == null) return;
        report.append("\nseguir viendo (").append(cw.size()).append("), más reciente primero:\n");
        for (Item i : cw) {
            report.append("  ").append(i.pkg).append(" | ").append(i.title)
                    .append(i.episode != null ? " | " + i.episode : "")
                    .append(" | ").append(Math.round(i.progress() * 100)).append("% | visto ").append(i.lastEngaged)
                    .append(i.intentUri != null ? " | enlace" : " | sin enlace")
                    .append(" | ").append(i.image).append('\n');
        }
        try (FileWriter w = new FileWriter(new File(dir, "content-report.txt"))) {
            w.write(report.toString());
        } catch (Exception ignored) {
        }
    }
}
