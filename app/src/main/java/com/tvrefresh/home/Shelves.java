package com.tvrefresh.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * The films and series shelves, shared by Browse and the bottom of Home: poster rows per UAE service, broken up
 * by bands of large cards that say more, trending this week (ranked) and new this month.
 */
final class Shelves {
    static final int POSTER_W = 100, POSTER_H = 150;     // dp: one grid column wide, 2:3
    static final int CARD_H = 212, TREND_W = 436, NEW_W = 324;
    private static final int GAP = 12;
    private static final int CARDS = 8;     // large cards in a band, each with a logo to fetch

    /** Where the colours come from: run(apply) now, and again whenever the look changes. */
    interface Look {
        Palette pal();

        void themed(Runnable apply);
    }

    static final class Section {
        final String label;
        final List<Title> titles;
        final int kind;     // 0 posters, 1 trending cards, 2 new cards

        Section(String label, List<Title> titles, int kind) {
            this.label = label;
            this.titles = titles;
            this.kind = kind;
        }
    }

    static final class Data {
        final List<Section> sections = new ArrayList<>();
        final Map<String, String> logos = new HashMap<>();
    }

    /** Off the main thread. type is "all", "movie" or "tv". */
    static Data load(Activity a, Tmdb tmdb, ExecutorService net, String type) throws Exception {
        List<Tmdb.Provider> providers = tmdb.providers();
        Future<List<Title>> trending = net.submit(() -> tmdb.trending(type));
        Future<List<Title>> fresh = net.submit(() -> tmdb.fresh(providers, type));
        List<Future<List<Title>>> rows = new ArrayList<>();
        for (Tmdb.Provider p : providers) rows.add(net.submit(() -> type.equals("all") ? tmdb.popularOn(p) : tmdb.popularOn(p, type)));
        List<Title> trend = trending.get(), news = fresh.get();
        trend = trend.subList(0, Math.min(CARDS, trend.size()));
        news = news.subList(0, Math.min(CARDS, news.size()));
        Map<String, Future<String>> marks = new HashMap<>();
        for (List<Title> band : Arrays.asList(trend, news)) {
            for (Title x : band) if (!marks.containsKey(x.type + x.id)) marks.put(x.type + x.id, net.submit(() -> tmdb.logo(x.type, x.id)));
        }
        List<Section> posters = new ArrayList<>();
        for (int i = 0; i < providers.size(); i++) {
            try {
                List<Title> titles = rows.get(i).get();
                if (!titles.isEmpty()) posters.add(new Section(a.getString(R.string.on_service, providers.get(i).label), titles, 0));
            } catch (Exception e) {
                Log.w("Home.Shelves", providers.get(i).label + ": " + e);
            }
        }
        Data d = new Data();
        // Large cards, two poster rows, large cards again, then the rest.
        if (!trend.isEmpty()) d.sections.add(new Section(a.getString(R.string.trending), trend, 1));
        for (int i = 0; i < Math.min(2, posters.size()); i++) d.sections.add(posters.get(i));
        if (!news.isEmpty()) d.sections.add(new Section(a.getString(R.string.new_this_month), news, 2));
        for (int i = 2; i < posters.size(); i++) d.sections.add(posters.get(i));
        for (Map.Entry<String, Future<String>> e : marks.entrySet()) {
            try {
                String path = e.getValue().get();
                if (path != null) d.logos.put(e.getKey(), path);
            } catch (Exception ignored) {
                // the name shows instead
            }
        }
        return d;
    }

    private final Activity a;
    private final Tiles tiles;
    private final Images images;
    private final Look look;
    private final int inset;    // dp from the screen's left edge to the first tile

    Shelves(Activity a, Tiles tiles, Images images, Look look, int inset) {
        this.a = a;
        this.tiles = tiles;
        this.images = images;
        this.look = look;
        this.inset = inset;
    }

    /** Adds a row per section to list, then TMDB's credit. */
    void fill(LinearLayout list, Data d) {
        for (Section s : d.sections) {
            List<View> items = new ArrayList<>();
            for (int i = 0; i < s.titles.size(); i++) {
                Title t = s.titles.get(i);
                items.add(s.kind == 0 ? poster(t) : card(t, s.kind, i, d.logos.get(t.type + t.id)));
            }
            int w = s.kind == 0 ? POSTER_W : s.kind == 1 ? TREND_W : NEW_W;
            int h = s.kind == 0 ? POSTER_H : CARD_H;
            list.addView(row(s.label, items, tiles.dp(w), tiles.dp(h)));
        }
        TextView credit = tiles.label(8);
        credit.setAllCaps(false);
        credit.setSingleLine(false);
        credit.setText(R.string.attribution);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(tiles.dp(inset + 2), tiles.dp(26), tiles.dp(inset), 0);
        list.addView(credit, lp);
        look.themed(() -> credit.setTextColor(Palette.alpha(look.pal().ink, 0.35f)));
    }

    /** A labelled row that scrolls sideways; the ends hold focus so it never jumps to another row. */
    View row(String label, List<View> items, int w, int h) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setPadding(0, tiles.dp(14), 0, tiles.dp(4));
        TextView l = tiles.label(10.5f);
        l.setText(label);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = tiles.dp(inset + 4);
        box.addView(l, llp);
        look.themed(() -> l.setTextColor(look.pal().dim()));
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFocusable(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipToPadding(false);
        hs.setClipChildren(false);
        hs.setPadding(tiles.dp(inset), 0, tiles.dp(inset), 0);
        LinearLayout line = new LinearLayout(a);
        line.setClipChildren(false);
        line.setClipToPadding(false);
        line.setPadding(0, tiles.dp(12), 0, tiles.dp(10));
        for (View v : items) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMarginEnd(tiles.dp(GAP));
            line.addView(v, lp);
        }
        int n = line.getChildCount();
        if (n > 0) {
            line.getChildAt(0).setNextFocusLeftId(line.getChildAt(0).getId());
            line.getChildAt(n - 1).setNextFocusRightId(line.getChildAt(n - 1).getId());
        }
        hs.addView(line);
        box.addView(hs);
        return box;
    }

    /** The titles saved to My List as a poster row, or null when there are none. */
    View myListRow(List<Title> saved) {
        if (saved.isEmpty()) return null;
        List<View> items = new ArrayList<>();
        for (Title t : saved) items.add(poster(t));
        return row(a.getString(R.string.my_list), items, tiles.dp(POSTER_W), tiles.dp(POSTER_H));
    }

    FrameLayout poster(Title t) {
        FrameLayout tile = tiles.tile(14, true);
        Tiles.clip(tile);
        ImageView img = photo(tile);
        images.load(Tmdb.image("w342", t.poster != null ? t.poster : t.backdrop), img, tiles.dp(POSTER_W), tiles.dp(POSTER_H));
        tile.setContentDescription(t.name);
        tile.setOnClickListener(v -> {
            Tiles.press(v);
            openDetail(t);
        });
        look.themed(() -> Tiles.fill(tile, look.pal().surface));
        return tile;
    }

    /** A large card: the photo, a chip (its rank, or NEW), the logo, the facts and two lines of the story. */
    private FrameLayout card(Title t, int kind, int index, String logo) {
        boolean wide = kind == 1;
        int w = wide ? TREND_W : NEW_W;
        FrameLayout tile = tiles.tile(false, true);
        Tiles.clip(tile);
        ImageView img = photo(tile);
        images.load(Tmdb.image("w780", t.backdrop), img, tiles.dp(w), tiles.dp(CARD_H));
        View scrim = new View(a);
        scrim.setBackground(new LayerDrawable(new Drawable[] {
                new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xE6000000, 0x99000000, 0x00000000}),
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0x80000000, 0x00000000})}));
        tile.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView c = tiles.chip(wide ? a.getString(R.string.rank_this_week, index + 1) : a.getString(R.string.chip_new));
        if (!wide) {
            ((GradientDrawable) c.getBackground()).setColor(Palette.ACCENT);
            c.setTextColor(0xFFFFFFFF);
        }
        tile.addView(c, Tiles.at(Gravity.TOP | Gravity.START, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                tiles.dp(14), tiles.dp(14), 0, 0));

        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        if (logo != null) {
            ImageView mark = new ImageView(a);
            mark.setAdjustViewBounds(true);
            mark.setMaxWidth(tiles.dp(wide ? 200 : 170));
            mark.setMaxHeight(tiles.dp(48));
            mark.setScaleType(ImageView.ScaleType.FIT_START);
            words.addView(mark, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            images.load(Tmdb.image("w500", logo), mark, tiles.dp(200), tiles.dp(48));
        } else {
            TextView name = tiles.text(tiles.medium, 18);
            name.setTextColor(0xFFFFFFFF);
            name.setText(t.name);
            words.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView meta = tiles.label(8.5f);
        meta.setTextColor(0xCCFFFFFF);
        meta.setText(meta(t, !wide));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = tiles.dp(8);
        words.addView(meta, mlp);
        if (t.overview != null) {
            TextView story = tiles.text(tiles.regular, 11.5f);
            story.setTextColor(0xB3FFFFFF);
            story.setSingleLine(false);
            story.setMaxLines(2);
            story.setLineSpacing(tiles.dp(2), 1f);
            story.setText(t.overview);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(tiles.dp(w - 36), ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = tiles.dp(6);
            words.addView(story, slp);
        }
        tile.addView(words, Tiles.at(Gravity.BOTTOM | Gravity.START, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                tiles.dp(16), 0, tiles.dp(16), tiles.dp(14)));
        tile.setContentDescription(t.name);
        tile.setOnClickListener(v -> {
            Tiles.press(v);
            openDetail(t);
        });
        look.themed(() -> Tiles.fill(tile, look.pal().surface));
        return tile;
    }

    /** "SERIES · 2025 · ★ 8.1", or with the release day for new things: "FILM · OUT 12 OCT · ★ 7.1". */
    private String meta(Title t, boolean released) {
        List<String> m = new ArrayList<>();
        m.add(a.getString(t.isMovie() ? R.string.film : R.string.series));
        if (released && t.date != null) {
            try {
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                m.add(a.getString(R.string.out_on, new SimpleDateFormat("d MMM", Locale.US).format(in.parse(t.date))));
            } catch (java.text.ParseException e) {
                m.add(t.year);
            }
        } else if (!t.year.isEmpty()) {
            m.add(t.year);
        }
        if (t.rating > 0) m.add(String.format(Locale.US, "★ %.1f", t.rating));
        return TextUtils.join("  ·  ", m);
    }

    private ImageView photo(FrameLayout tile) {
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return iv;
    }

    private void openDetail(Title t) {
        a.startActivity(new Intent(a, DetailActivity.class)
                .putExtra(DetailActivity.TYPE, t.type)
                .putExtra(DetailActivity.ID, t.id)
                .putExtra(DetailActivity.NAME, t.name)
                .putExtra(DetailActivity.BACKDROP, t.backdrop)
                .putExtra(DetailActivity.POSTER, t.poster));
    }

    /** The first tile that takes focus inside v, or null. */
    static View firstTile(View v) {
        if (v.isFocusable() && v.getTag(R.id.card) != null) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = firstTile(g.getChildAt(i));
                if (c != null) return c;
            }
        }
        return null;
    }
}
