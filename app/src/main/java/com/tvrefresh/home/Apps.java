package com.tvrefresh.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The TV's apps, in Home's order, and their tiles: Home's dock, All apps, and Search use the same. */
final class Apps {
    /** First among the apps, in this order. Apps not installed yet are skipped until they are. */
    private static final List<String> FAVOURITES = Arrays.asList(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.apple.atve.androidtv.appletv",
            "com.google.android.youtube.tvkids");
    /** Never shown as a tile. Live TV is on the Inputs page, and one press of the remote's Source button away. */
    private static final Set<String> HIDDEN = new HashSet<>(Collections.singletonList("com.tcl.tv"));

    /**
     * Brand glyphs for the apps this TV has, after the "App icon customization in Nothing OS 5.0" idea: one plain
     * circle behind every app, and on it only the brand's mark in the brand's colour, no plate of its own. A glyph
     * listed with true is drawn in ink instead (Apple's is black or white).
     */
    private static final Map<String, Object[]> GLYPHS = new HashMap<>();
    static {
        GLYPHS.put("com.netflix.ninja", new Object[] {R.drawable.glyph_netflix, false});
        GLYPHS.put("com.google.android.youtube.tv", new Object[] {R.drawable.glyph_youtube, false});
        GLYPHS.put("com.google.android.youtube.tvkids", new Object[] {R.drawable.glyph_youtubekids, false});
        GLYPHS.put("com.google.android.youtube.tvmusic", new Object[] {R.drawable.glyph_youtubemusic, false});
        GLYPHS.put("com.apple.atve.androidtv.appletv", new Object[] {R.drawable.glyph_appletv, true});
        GLYPHS.put("com.android.vending", new Object[] {R.drawable.glyph_googleplay, false});
        GLYPHS.put("org.videolan.vlc", new Object[] {R.drawable.glyph_vlc, false});
        GLYPHS.put("com.spotify.tv.android", new Object[] {R.drawable.glyph_spotify, false});
    }

    static final class App {
        final String pkg, label;
        final ActivityInfo info;

        App(String pkg, String label, ActivityInfo info) {
            this.pkg = pkg;
            this.label = label;
            this.info = info;
        }
    }

    private final Activity a;
    private final Tiles tiles;

    Apps(Activity a, Tiles tiles) {
        this.a = a;
        this.tiles = tiles;
    }

    /** Every TV app: the favourites first, then the rest by name. */
    List<App> load() {
        PackageManager pm = a.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> found = new LinkedHashMap<>();
        for (ResolveInfo r : pm.queryIntentActivities(query, 0)) {
            String p = r.activityInfo.packageName;
            if (p.equals(a.getPackageName()) || HIDDEN.contains(p) || found.containsKey(p)) continue;
            found.put(p, new App(p, String.valueOf(r.loadLabel(pm)), r.activityInfo));
        }
        List<App> apps = new ArrayList<>();
        for (String p : FAVOURITES) if (found.containsKey(p)) apps.add(found.remove(p));
        List<App> rest = new ArrayList<>(found.values());
        Collator collator = Collator.getInstance();
        rest.sort((x, y) -> collator.compare(x.label, y.label));
        apps.addAll(rest);
        return apps;
    }

    /** A circle with the app's glyph; an app without one gets its adaptive icon's foreground, or its initial. */
    FrameLayout tile(App app, Shelves.Look look) {
        FrameLayout t = tiles.tile(false, true);
        FrameLayout plate = new FrameLayout(a);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        plate.setBackground(disc);
        plate.setClipToOutline(true);
        t.addView(plate, Tiles.at(Gravity.TOP | Gravity.START, tiles.dp(44), tiles.dp(44), tiles.dp(15), tiles.dp(15), 0, 0));
        Object[] glyph = GLYPHS.get(app.pkg);
        View mark;
        boolean inked = false;
        if (glyph != null) {
            ImageView g = new ImageView(a);
            g.setImageResource((Integer) glyph[0]);
            inked = (Boolean) glyph[1];
            plate.addView(g, new FrameLayout.LayoutParams(tiles.dp(22), tiles.dp(22), Gravity.CENTER));
            mark = g;
        } else {
            Drawable icon = app.info.loadIcon(a.getPackageManager());
            if (icon instanceof AdaptiveIconDrawable) {
                ImageView g = new ImageView(a);
                g.setImageDrawable(((AdaptiveIconDrawable) icon).getForeground());
                // The foreground is drawn for 108 dp with the artwork in the middle 72: let the circle crop the rest.
                plate.addView(g, new FrameLayout.LayoutParams(tiles.dp(66), tiles.dp(66), Gravity.CENTER));
                mark = g;
            } else {
                TextView initial = tiles.label(17);
                initial.setText(app.label.substring(0, 1));
                initial.setGravity(Gravity.CENTER);
                plate.addView(initial, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                mark = initial;
                inked = true;
            }
        }
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        TextView name = tiles.text(tiles.medium, 13);
        name.setText(app.label);
        t.addView(name, Tiles.at(Gravity.BOTTOM | Gravity.START, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                tiles.dp(15), 0, tiles.dp(10), tiles.dp(14)));
        t.setContentDescription(app.label);
        t.setOnFocusChangeListener((v, f) -> {
            tiles.focus(v, f);
            // The glyph grows a little inside its circle, a beat after the tile lifts.
            mark.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setStartDelay(f ? 60 : 0)
                    .setDuration(f ? 420 : 220).setInterpolator(Tiles.EASE).start();
        });
        t.setOnClickListener(v -> {
            Tiles.press(v);
            launch(app.pkg);
        });
        t.setOnLongClickListener(v -> {
            start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)));
            return true;
        });
        boolean ink = inked;
        look.themed(() -> {
            Palette pal = look.pal();
            Tiles.fill(t, pal.surface);
            disc.setColor(pal.plate);
            name.setTextColor(pal.ink);
            if (!ink) return;
            if (mark instanceof ImageView) ((ImageView) mark).setImageTintList(ColorStateList.valueOf(pal.ink));
            else ((TextView) mark).setTextColor(pal.ink);
        });
        return t;
    }

    void launch(String pkg) {
        PackageManager pm = a.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) start(i);
    }

    private void start(Intent i) {
        try {
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w("Home.Apps", "could not open " + i + ": " + e);
        }
    }
}
