package com.tvrefresh.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A page inside Home (Clock, Weather, Browse): the same 8 x 4 grid of 100 dp cells, the look of the moment, Home's
 * sounds, and tiles that come in one after another.
 */
abstract class TileActivity extends Activity {
    static final int CELL = 100, GAP = 12, X0 = 38, Y0 = 52;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT, MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    final Handler handler = new Handler(Looper.getMainLooper());
    Tiles tiles;
    SharedPreferences prefs;
    Weather weather;
    Palette pal;
    FrameLayout root;
    final List<View> placed = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        tiles = new Tiles(this);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        weather = Weather.cached(prefs);
        pal = Palette.at(Palette.isNight(prefs, weather) ? 1f : 0f);
        getWindow().setBackgroundDrawable(new ColorDrawable(pal.bg));
        root = new FrameLayout(this);
        root.setClipChildren(false);
        setContentView(root);
        build();
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                tiles.enter(new ArrayList<>(placed));
            }
        });
    }

    /** Lays the page out; may run again (rebuild()) when fresh data arrives. */
    abstract void build();

    void rebuild() {
        View focused = getCurrentFocus();
        int index = focused == null ? -1 : placed.indexOf(focused);
        root.removeAllViews();
        placed.clear();
        build();
        if (index >= 0 && index < placed.size()) placed.get(index).requestFocus();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        Boolean handled = Sounds.navigate(this, e);
        return handled != null ? handled : super.dispatchKeyEvent(e);
    }

    static int span(int n) {
        return n * CELL + (n - 1) * GAP;
    }

    FrameLayout place(FrameLayout v, int col, int row, int cols, int rows) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(span(cols)), dp(span(rows)), Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(X0 + col * (CELL + GAP));
        lp.topMargin = dp(Y0 + row * (CELL + GAP));
        root.addView(v, lp);
        placed.add(v);
        return v;
    }

    /** A tile that only shows things: surface, or inverse for the one that leads the page. */
    FrameLayout card(boolean inverse, int col, int row, int cols, int rows) {
        FrameLayout t = place(tiles.tile(false, false), col, row, cols, rows);
        Tiles.fill(t, inverse ? pal.inverse : pal.surface);
        return t;
    }

    /** A tile you can press: a dot icon, a title and a small label under it. */
    FrameLayout action(String[] icon, String title, String note, int col, int row, int cols, Runnable click) {
        FrameLayout t = place(tiles.tile(false, true), col, row, cols, 1);
        Tiles.fill(t, pal.surface);
        DotMatrixView i = new DotMatrixView(this, dp(DotMatrixView.ICON_PITCH));
        i.setPattern(icon, false);
        i.setColors(pal.ink, 0);
        t.addView(i, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(16), dp(16), 0, 0));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = tiles.text(tiles.medium, 14);
        name.setText(title);
        name.setTextColor(pal.ink);
        // WRAP, not the vertical layout's default MATCH, which would shrink the title to the label's width.
        words.addView(name, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView small = label(8.5f, pal.dim());
        small.setText(note);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(5);
        words.addView(small, lp);
        t.addView(words, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(16), 0, dp(12), dp(14)));
        t.setContentDescription(title);
        t.setOnClickListener(v -> {
            Tiles.press(v);
            click.run();
        });
        return t;
    }

    TextView label(float sp, int color) {
        TextView t = tiles.label(sp);
        t.setTextColor(color);
        return t;
    }

    TextView text(android.graphics.Typeface face, float sp, int color) {
        TextView t = tiles.text(face, sp);
        t.setTextColor(color);
        return t;
    }

    boolean open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w("Home", "could not open " + i + ": " + e);
            return false;
        }
    }

    /** "6:26 am", Dubai time, the TV's 12 or 24 hour choice. */
    String time(int minutes) {
        java.util.Calendar c = Weather.now();
        c.set(java.util.Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(java.util.Calendar.MINUTE, minutes % 60);
        java.text.DateFormat f = android.text.format.DateFormat.getTimeFormat(this);
        f.setTimeZone(Weather.ZONE);
        return f.format(c.getTime()).toLowerCase(java.util.Locale.US);
    }

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
