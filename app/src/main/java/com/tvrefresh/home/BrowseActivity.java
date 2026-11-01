package com.tvrefresh.home;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Films and series on the UAE services, as shelves (see Shelves), with All, Films or Series at the top. */
public class BrowseActivity extends TileActivity {
    private static final String[] TYPES = {"all", "movie", "tv"};
    private static final int TOP = 104;     // dp above the scrolling part: the title and the filters

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newFixedThreadPool(4);
    private Tmdb tmdb;
    private Shelves shelves;
    private MainActivity.Pager pager;
    private LinearLayout list;
    private final List<View> chips = new ArrayList<>();
    private String type = "all";
    private int generation;
    private boolean shown;

    @Override
    protected void onCreate(Bundle state) {
        tmdb = new Tmdb(this);
        super.onCreate(state);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> follow(now));
        load();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (pager.getScrollY() > 0) {
            pager.glideTo(0);
            View first = Shelves.firstTile(list);
            if (first != null) first.requestFocus();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    void build() {
        shelves = new Shelves(this, tiles, Images.get(this), new Shelves.Look() {
            @Override public Palette pal() {
                return pal;
            }

            @Override public void themed(Runnable apply) {
                apply.run();
            }
        }, X0);

        // The list sits in a viewport that clips it: without that, rows scrolled up would draw over the header.
        FrameLayout viewport = new FrameLayout(this);
        pager = new MainActivity.Pager(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setPadding(0, 0, 0, dp(Y0));
        pager.addView(list, new FrameLayout.LayoutParams(MATCH, WRAP));
        viewport.addView(pager, new FrameLayout.LayoutParams(MATCH, MATCH));
        pager.cull(list);
        root.addView(viewport, Tiles.at(Gravity.TOP, MATCH, MATCH, 0, dp(TOP), 0, 0));
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {pal.bg, Palette.alpha(pal.bg, 0f)}));
        root.addView(fade, Tiles.at(Gravity.TOP, MATCH, dp(22), 0, dp(TOP), 0, 0));
        TextView wait = label(10, pal.dim());
        wait.setText(R.string.loading);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.setMargins(dp(X0 + 4), dp(24), 0, 0);
        list.addView(wait, lp);

        TextView title = text(tiles.light, 30, pal.ink);
        title.setLetterSpacing(-0.02f);
        title.setText(R.string.browse);
        root.addView(title, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(X0), dp(44), 0, 0));
        LinearLayout filters = new LinearLayout(this);
        int[] names = {R.string.browse_all, R.string.film_plural, R.string.series};
        for (int i = 0; i < TYPES.length; i++) {
            FrameLayout chip = tiles.tile(18, true);
            TextView l = label(9, pal.ink);
            l.setText(names[i]);
            chip.addView(l, Tiles.at(Gravity.CENTER, WRAP, WRAP, 0, 0, 0, 0));
            chip.setTag(l);
            String t = TYPES[i];
            chip.setOnClickListener(v -> {
                Tiles.press(v);
                if (!t.equals(type)) {
                    type = t;
                    paintChips();
                    load();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(92), dp(36));
            clp.leftMargin = dp(10);
            filters.addView(chip, clp);
            chips.add(chip);
        }
        paintChips();
        root.addView(filters, Tiles.at(Gravity.TOP | Gravity.END, WRAP, WRAP, 0, dp(44), dp(X0), 0));
        placed.add(title);
        placed.addAll(chips);
    }

    private void paintChips() {
        for (int i = 0; i < chips.size(); i++) {
            boolean on = TYPES[i].equals(type);
            Tiles.fill(chips.get(i), on ? pal.inverse : pal.surface);
            ((TextView) chips.get(i).getTag()).setTextColor(on ? pal.onInverse : pal.ink);
        }
    }

    private void load() {
        int gen = ++generation;
        String t = type;
        io.execute(() -> {
            try {
                Shelves.Data d = Shelves.load(this, tmdb, net, t);
                handler.post(() -> {
                    if (gen == generation && !isDestroyed()) show(d);
                });
            } catch (Exception e) {
                Log.w("Home.Browse", "titles: " + e);
            }
        });
    }

    private void show(Shelves.Data d) {
        boolean inList = getCurrentFocus() != null && within(list, getCurrentFocus());
        list.removeAllViews();
        shelves.fill(list, d);
        showMyList();
        // Rows fade up one after another.
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(dp(16));
            v.animate().alpha(1f).translationY(0).setStartDelay(Math.min(i, 4) * 70L).setDuration(560).setInterpolator(Tiles.EASE).start();
        }
        pager.scrollTo(0, 0);
        View first = Shelves.firstTile(list);
        // The first time, straight into the films; after a filter change, focus stays on the filter.
        if (first != null && (!shown || inList || getCurrentFocus() == null)) first.requestFocus();
        shown = true;
    }

    private View myListRow;
    private int myListShown = -1;

    /** My List at the top, filtered like the rest (films only under Films, series only under Series). */
    private void showMyList() {
        if (myListRow != null) list.removeView(myListRow);
        myListShown = MyList.version(prefs);
        List<Title> saved = new ArrayList<>();
        for (Title t : MyList.all(prefs)) if (type.equals("all") || type.equals(t.type)) saved.add(t);
        myListRow = shelves.myListRow(saved);
        if (myListRow != null) list.addView(myListRow, 0);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (myListShown >= 0 && MyList.version(prefs) != myListShown) showMyList();
    }

    /** The list glides so the focused row sits just under the filters; the filters bring it back to the top. */
    private void follow(View now) {
        if (now == null || pager == null) return;
        if (chips.contains(now)) {
            pager.glideTo(0);
            return;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (within(row, now)) {
                pager.glideTo(row.getTop());
                return;
            }
        }
    }

    private static boolean within(View parent, View v) {
        for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) if (p == parent) return true;
        return false;
    }
}
