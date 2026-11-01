package com.tvrefresh.home;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search: type with the TV's keyboard or say it, and apps, films and series appear as you go. With nothing typed,
 * what is trending.
 */
public class SearchActivity extends TileActivity {
    private static final int TOP = 124;         // dp above the results: the field and the voice circle
    private static final long PAUSE = 350;      // ms of no typing before searching
    private static final int VOICE = 1;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Tmdb tmdb;
    private Apps appList;
    private List<Apps.App> apps = new ArrayList<>();
    private Shelves shelves;
    private Shelves.Look look;
    private EditText field;
    private MainActivity.Pager pager;
    private LinearLayout list;
    private int generation;
    private final Runnable search = () -> search(field.getText().toString().trim());

    @Override
    protected void onCreate(Bundle state) {
        tmdb = new Tmdb(this);
        super.onCreate(state);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> follow(now));
        search("");
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    void build() {
        appList = new Apps(this, tiles);
        apps = appList.load();
        look = new Shelves.Look() {
            @Override public Palette pal() {
                return pal;
            }

            @Override public void themed(Runnable apply) {
                apply.run();
            }
        };
        shelves = new Shelves(this, tiles, Images.get(this), look, X0);

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

        // The field: a pill with the dot magnifier, the TV's keyboard opens on it.
        FrameLayout pill = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(30));
        bg.setColor(pal.surface);
        pill.setBackground(bg);
        DotMatrixView glass = new DotMatrixView(this, dp(DotMatrixView.ICON_PITCH));
        glass.setPattern(DotMatrixView.SEARCH, false);
        glass.setColors(pal.ink, 0);
        pill.addView(glass, Tiles.at(Gravity.CENTER_VERTICAL | Gravity.START, WRAP, WRAP, dp(22), 0, 0, 0));
        field = new EditText(this);
        field.setBackground(null);
        field.setTypeface(tiles.light);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        field.setTextColor(pal.ink);
        field.setHintTextColor(pal.dim());
        field.setHint(R.string.search_hint);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setSoundEffectsEnabled(false);
        pill.addView(field, Tiles.at(Gravity.CENTER_VERTICAL, MATCH, WRAP, dp(64), 0, dp(20), 0));
        root.addView(pill, Tiles.at(Gravity.TOP | Gravity.START, dp(span(7)), dp(60), dp(X0), dp(44), 0, 0));
        field.setOnFocusChangeListener((v, f) -> bg.setStroke(f ? dp(Tiles.RING) : 0, Palette.ACCENT));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(search);
                handler.postDelayed(search, PAUSE);
            }
        });
        field.setOnEditorActionListener((v, action, e) -> {
            handler.removeCallbacks(search);
            search.run();
            hideKeyboard();
            View first = Shelves.firstTile(list);
            if (first != null) first.requestFocus();
            return true;
        });
        placed.add(pill);

        // Voice, when the TV can listen (Google's speech service).
        Intent listen = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        if (listen.resolveActivity(getPackageManager()) != null) {
            FrameLayout mic = tiles.tile(true, true);
            Tiles.fill(mic, Palette.ACCENT);
            tiles.ring(mic, pal.ink);
            DotMatrixView m = new DotMatrixView(this, dp(2.6f));
            m.setPattern(DotMatrixView.MIC, false);
            m.setColors(0xFFFFFFFF, 0);
            mic.addView(m, Tiles.at(Gravity.CENTER, WRAP, WRAP, 0, 0, 0, 0));
            mic.setContentDescription(getString(R.string.voice));
            mic.setOnClickListener(v -> {
                Tiles.press(v);
                try {
                    startActivityForResult(listen, VOICE);
                } catch (RuntimeException e) {
                    Log.w("Home.Search", "voice: " + e);
                }
            });
            root.addView(mic, Tiles.at(Gravity.TOP | Gravity.START, dp(60), dp(60), dp(X0 + 7 * (CELL + GAP)), dp(44), 0, 0));
            placed.add(mic);
        }
        field.requestFocus();
        field.post(() -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(field, 0));
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE || result != RESULT_OK || data == null) return;
        ArrayList<String> said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (said == null || said.isEmpty()) return;
        field.setText(said.get(0));
        field.setSelection(field.length());
        handler.removeCallbacks(search);
        search.run();
    }

    @Override
    public void onBackPressed() {
        if (pager.getScrollY() > 0 || !field.hasFocus()) {
            pager.glideTo(0);
            field.requestFocus();
        } else {
            super.onBackPressed();
        }
    }

    private void hideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    private void search(String query) {
        int gen = ++generation;
        List<Apps.App> matched = new ArrayList<>();
        String q = query.toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) for (Apps.App a : apps) if (a.label.toLowerCase(Locale.ROOT).contains(q)) matched.add(a);
        io.execute(() -> {
            List<Title> found;
            try {
                found = q.isEmpty() ? tmdb.trending("all") : tmdb.search(query);
            } catch (Exception e) {
                Log.w("Home.Search", e.toString());
                found = new ArrayList<>();
            }
            List<Title> titles = found;
            handler.post(() -> {
                if (gen == generation && !isDestroyed()) show(query, matched, titles);
            });
        });
    }

    private void show(String query, List<Apps.App> matched, List<Title> titles) {
        list.removeAllViews();
        if (!matched.isEmpty()) {
            List<View> items = new ArrayList<>();
            for (Apps.App a : matched) items.add(appList.tile(a, look));
            list.addView(shelves.row(getString(R.string.search_apps), items, dp(CELL), dp(CELL)));
        }
        if (query.isEmpty()) {
            List<View> items = new ArrayList<>();
            for (Title t : titles) items.add(shelves.poster(t));
            if (!items.isEmpty()) list.addView(shelves.row(getString(R.string.trending_now), items, dp(Shelves.POSTER_W), dp(Shelves.POSTER_H)));
        } else {
            List<View> films = new ArrayList<>(), series = new ArrayList<>();
            for (Title t : titles) (t.isMovie() ? films : series).add(shelves.poster(t));
            // The row with the best match comes first.
            boolean seriesFirst = !titles.isEmpty() && !titles.get(0).isMovie();
            View f = films.isEmpty() ? null : shelves.row(getString(R.string.film_plural), films, dp(Shelves.POSTER_W), dp(Shelves.POSTER_H));
            View r = series.isEmpty() ? null : shelves.row(getString(R.string.series), series, dp(Shelves.POSTER_W), dp(Shelves.POSTER_H));
            for (View row : seriesFirst ? new View[] {r, f} : new View[] {f, r}) if (row != null) list.addView(row);
            if (matched.isEmpty() && titles.isEmpty()) {
                TextView none = label(10, pal.dim());
                none.setText(getString(R.string.no_results, query));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
                lp.setMargins(dp(X0 + 4), dp(24), 0, 0);
                list.addView(none, lp);
            }
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            v.setAlpha(0f);
            v.animate().alpha(1f).setStartDelay(i * 60L).setDuration(360).setInterpolator(Tiles.EASE).start();
        }
        pager.scrollTo(0, 0);
    }

    /** Results glide up under the field as focus goes down them; back on the field, back to the top. */
    private void follow(View now) {
        if (now == null || pager == null) return;
        if (now == field) {
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
