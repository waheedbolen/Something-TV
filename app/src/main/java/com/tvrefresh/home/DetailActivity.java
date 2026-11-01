package com.tvrefresh.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One film or series, on the same grid as Home: its photo with the title's logo, a tile of facts, and tiles to
 * open it in a UAE service's app or play the trailer.
 */
public class DetailActivity extends Activity {
    static final String TYPE = "type", ID = "id", NAME = "name", BACKDROP = "backdrop", POSTER = "poster";
    private static final String TAG = "Home.Detail";
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final int CELL = 100, GAP = 12, X0 = 38, Y0 = 52;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT, MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private Tmdb tmdb;
    private Images images;
    private Tiles tiles;
    private Palette pal;
    private FrameLayout grid;
    private final List<View> placed = new ArrayList<>();

    private ImageView logo;
    private TextView name, kind, rating, meta, genres, overview, cast, streaming;
    private FrameLayout openTile, trailerTile, saveTile;
    private SharedPreferences prefs;
    // What My List keeps; the row's values at first, the full details' once they arrive.
    private String type, savedName, year, story, poster, backdrop;
    private int id;
    private double score;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        tmdb = new Tmdb(this);
        images = Images.get(this);
        tiles = new Tiles(this);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        pal = Palette.at(Palette.isNight(prefs, Weather.cached(prefs)) ? 1f : 0f);
        getWindow().setBackgroundDrawable(new ColorDrawable(pal.bg));
        grid = new FrameLayout(this);
        grid.setClipChildren(false);
        grid.setBackgroundColor(pal.bg);
        setContentView(grid);

        Intent in = getIntent();
        type = in.getStringExtra(TYPE);
        id = in.getIntExtra(ID, 0);
        savedName = in.getStringExtra(NAME);
        poster = in.getStringExtra(POSTER);
        backdrop = in.getStringExtra(BACKDROP);
        boolean movie = "movie".equals(type);
        buildPhoto(in.getStringExtra(NAME), movie);
        buildFacts();
        buildActions();
        images.load(Tmdb.image("w1280", in.getStringExtra(BACKDROP)), (ImageView) ((ViewGroup) placed.get(0)).getChildAt(0),
                dp(span(5)), dp(span(3)));

        grid.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                tiles.enter(new ArrayList<>(placed));
            }
        });

        io.execute(() -> {
            try {
                Tmdb.Details d = tmdb.details(type, id);
                List<Tmdb.Provider> providers = tmdb.providers();
                handler.post(() -> {
                    if (!isDestroyed()) show(d, providers);
                });
            } catch (IOException | JSONException e) {
                Log.w(TAG, type + "/" + id + ": " + e);
            }
        });
        io.execute(() -> {
            try {
                String path = tmdb.logo(type, id);
                Bitmap mark = path == null ? null : images.fetch(Tmdb.image("w500", path), dp(280), dp(90));
                if (mark != null) handler.post(() -> showLogo(mark));
            } catch (IOException | JSONException e) {
                Log.w(TAG, "logo: " + e);
            }
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        Boolean handled = Sounds.navigate(this, e);
        return handled != null ? handled : super.dispatchKeyEvent(e);
    }

    private static int span(int n) {
        return n * CELL + (n - 1) * GAP;
    }

    private FrameLayout place(FrameLayout v, int col, int row, int cols, int rows) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(span(cols)), dp(span(rows)), Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(X0 + col * (CELL + GAP));
        lp.topMargin = dp(Y0 + row * (CELL + GAP));
        grid.addView(v, lp);
        placed.add(v);
        return v;
    }

    private void buildPhoto(String title, boolean movie) {
        FrameLayout t = place(tiles.tile(false, false), 0, 0, 5, 3);
        Tiles.clip(t);
        Tiles.fill(t, pal.surface);
        ImageView photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        t.addView(photo, new FrameLayout.LayoutParams(MATCH, MATCH));
        View scrim = new View(this);
        scrim.setBackground(new LayerDrawable(new Drawable[] {
                new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xC0000000, 0x30000000, 0x00000000}),
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0x66000000, 0x00000000})}));
        t.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));
        kind = tiles.chip(getString(movie ? R.string.film : R.string.series));
        t.addView(kind, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(18), dp(18), 0, 0));
        logo = new ImageView(this);
        logo.setAdjustViewBounds(true);
        logo.setMaxWidth(dp(280));
        logo.setMaxHeight(dp(90));
        logo.setVisibility(View.GONE);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        t.addView(logo, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(24), 0, 0, dp(24)));
        name = tiles.text(tiles.medium, 28);
        name.setTextColor(0xFFFFFFFF);
        name.setSingleLine(false);
        name.setMaxLines(2);
        name.setMaxWidth(dp(420));
        name.setText(title);
        t.addView(name, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(24), 0, 0, dp(24)));
    }

    private void showLogo(Bitmap mark) {
        logo.setImageBitmap(mark);
        logo.setVisibility(View.VISIBLE);
        logo.setAlpha(0f);
        logo.setTranslationY(dp(8));
        logo.animate().alpha(1f).translationY(0).setDuration(520).setInterpolator(Tiles.EASE).start();
        name.animate().alpha(0f).setDuration(200).withEndAction(() -> name.setVisibility(View.GONE)).start();
    }

    private void buildFacts() {
        FrameLayout t = place(tiles.tile(false, false), 5, 0, 3, 4);
        Tiles.fill(t, pal.surface);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(16));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.BOTTOM);
        rating = tiles.text(tiles.light, 36);
        rating.setLetterSpacing(-0.03f);
        rating.setTextColor(pal.ink);
        top.addView(rating);
        TextView source = tiles.label(9);
        source.setText("TMDB");
        source.setTextColor(pal.dim());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(WRAP, WRAP);
        slp.setMargins(dp(8), 0, 0, dp(6));
        top.addView(source, slp);
        View star = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Palette.ACCENT);
        star.setBackground(dot);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dlp.setMargins(0, 0, 0, dp(26));
        dlp.weight = 0;
        View spring = new View(this);
        top.addView(spring, new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(star, dlp);
        box.addView(top);
        meta = tiles.label(9.5f);
        meta.setTextColor(pal.dim());
        box.addView(meta, margin(dp(10)));
        genres = tiles.text(tiles.medium, 13);
        genres.setTextColor(pal.ink);
        box.addView(genres, margin(dp(14)));
        overview = tiles.text(tiles.regular, 12.5f);
        overview.setTextColor(pal.dim());
        overview.setSingleLine(false);
        overview.setMaxLines(11);
        overview.setLineSpacing(dp(3), 1f);
        overview.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        box.addView(overview, margin(dp(8)));
        View spring2 = new View(this);
        box.addView(spring2, new LinearLayout.LayoutParams(1, 0, 1f));
        cast = tiles.text(tiles.regular, 11);
        cast.setTextColor(pal.dim());
        cast.setSingleLine(false);
        cast.setMaxLines(2);
        box.addView(cast);
        View rule = new View(this);
        rule.setBackgroundColor(pal.rule());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH, dp(1));
        rlp.setMargins(0, dp(10), 0, dp(9));
        box.addView(rule, rlp);
        streaming = tiles.label(8.5f);
        streaming.setTextColor(pal.ink);
        box.addView(streaming);
        t.addView(box, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private static LinearLayout.LayoutParams margin(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = top;
        return lp;
    }

    private void buildActions() {
        openTile = place(tiles.tile(false, true), 0, 3, 3, 1);
        Tiles.fill(openTile, pal.inverse);
        openTile.setVisibility(View.INVISIBLE);
        trailerTile = place(tiles.tile(true, true), 3, 3, 1, 1);
        Tiles.fill(trailerTile, Palette.ACCENT);
        tiles.ring(trailerTile, pal.ink);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        DotMatrixView play = new DotMatrixView(this, dp(DotMatrixView.ICON_PITCH));
        play.setPattern(DotMatrixView.PLAY, false);
        play.setColors(0xFFFFFFFF, 0);
        box.addView(play, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView l = tiles.label(8);
        l.setText(R.string.play_trailer);
        l.setTextColor(0xFFFFFFFF);
        box.addView(l, margin(dp(8)));
        trailerTile.addView(box, Tiles.at(Gravity.CENTER, WRAP, WRAP, 0, dp(4), 0, 0));
        trailerTile.setContentDescription(getString(R.string.play_trailer));
        trailerTile.setVisibility(View.INVISIBLE);
        buildSave();
    }

    /** My List: a circle with a plus, which becomes a tick (in the accent colour) once the title is saved. */
    private void buildSave() {
        saveTile = place(tiles.tile(true, true), 4, 3, 1, 1);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        DotMatrixView icon = new DotMatrixView(this, dp(DotMatrixView.ICON_PITCH));
        box.addView(icon, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView l = tiles.label(8);
        box.addView(l, margin(dp(8)));
        saveTile.addView(box, Tiles.at(Gravity.CENTER, WRAP, WRAP, 0, dp(4), 0, 0));
        Runnable paint = () -> {
            boolean saved = MyList.has(prefs, type, id);
            icon.setPattern(saved ? DotMatrixView.CHECK : DotMatrixView.PLUS, icon.getMeasuredWidth() > 0);
            icon.setColors(saved ? 0xFFFFFFFF : pal.ink, 0);
            l.setText(saved ? R.string.saved : R.string.my_list);
            l.setTextColor(saved ? 0xFFFFFFFF : pal.ink);
            Tiles.fill(saveTile, saved ? Palette.ACCENT : pal.surface);
            tiles.ring(saveTile, saved ? pal.ink : Palette.ACCENT);
            saveTile.setContentDescription(getString(saved ? R.string.saved : R.string.my_list));
        };
        paint.run();
        saveTile.setOnClickListener(v -> {
            Tiles.press(v);
            MyList.toggle(prefs, type, id, savedName, year, story, poster, backdrop, score);
            paint.run();
        });
    }

    private void show(Tmdb.Details d, List<Tmdb.Provider> providers) {
        if (d.name != null) savedName = d.name;
        year = d.year;
        story = d.overview;
        score = d.rating;
        if (d.poster != null) poster = d.poster;
        if (d.backdrop != null) backdrop = d.backdrop;
        if (d.name != null) name.setText(d.name);
        rating.setText(d.rating > 0 ? String.format(Locale.US, "%.1f", d.rating) : "–");
        List<String> facts = new ArrayList<>();
        if (!d.year.isEmpty()) facts.add(d.year);
        if (d.length != null) facts.add(d.length);
        if (d.certification != null) facts.add(d.certification);
        meta.setText(TextUtils.join("  ·  ", facts));
        genres.setText(TextUtils.join(", ", d.genres));
        overview.setText(d.overview == null ? "" : d.overview);
        cast.setText(d.cast.isEmpty() ? "" : getString(R.string.starring, TextUtils.join(", ", d.cast)));
        cast.setVisibility(d.cast.isEmpty() ? View.GONE : View.VISIBLE);
        streaming.setText(d.streamingOn.isEmpty() ? getString(R.string.not_streaming)
                : getString(R.string.streaming_on, TextUtils.join(", ", d.streamingOn)));

        PackageManager pm = getPackageManager();
        String title = d.name == null ? name.getText().toString() : d.name;
        for (Tmdb.Provider p : providers) {
            if (!streamsOn(d, p)) continue;
            Intent open = p.openIntent(pm, title);
            if (open == null) continue;
            fillOpen(p, open);
            break;
        }
        if (openTile.getVisibility() != View.VISIBLE) notStreaming();
        if (d.trailer != null) {
            Intent trailer = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + d.trailer));
            if (pm.getLeanbackLaunchIntentForPackage(YOUTUBE) != null) trailer.setPackage(YOUTUBE);
            trailerTile.setOnClickListener(v -> {
                Tiles.press(v);
                start(trailer);
            });
            reveal(trailerTile);
        }
        if (openTile.isFocusable()) openTile.requestFocus();
        else if (trailerTile.getVisibility() == View.VISIBLE) trailerTile.requestFocus();
        else saveTile.requestFocus();
    }

    /** No service here has it: the tile says so, quietly, and does not take focus. */
    private void notStreaming() {
        openTile.setFocusable(false);
        Tiles.fill(openTile, pal.surface);
        TextView l = tiles.label(9);
        l.setText(R.string.not_on_services);
        l.setTextColor(pal.dim());
        openTile.addView(l, Tiles.at(Gravity.CENTER_VERTICAL | Gravity.START, WRAP, WRAP, dp(20), 0, dp(12), 0));
        reveal(openTile);
    }

    private void fillOpen(Tmdb.Provider p, Intent open) {
        ImageView mark = new ImageView(this);
        mark.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable clip = new GradientDrawable();
        clip.setCornerRadius(dp(10));
        clip.setColor(0);
        mark.setBackground(clip);
        mark.setClipToOutline(true);
        openTile.addView(mark, Tiles.at(Gravity.CENTER_VERTICAL | Gravity.START, dp(40), dp(40), dp(18), 0, 0, 0));
        images.load(p.logoUrl(), mark, dp(40), dp(40));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView over = tiles.label(8.5f);
        over.setText(R.string.open_in_short);
        over.setTextColor(Palette.alpha(pal.onInverse, 0.6f));
        words.addView(over);
        TextView service = tiles.text(tiles.medium, 16);
        service.setText(p.label);
        service.setTextColor(pal.onInverse);
        words.addView(service, margin(dp(5)));
        openTile.addView(words, Tiles.at(Gravity.CENTER_VERTICAL | Gravity.START, WRAP, WRAP, dp(72), 0, dp(12), 0));
        openTile.setContentDescription(getString(R.string.open_in, p.label));
        openTile.setOnClickListener(v -> {
            Tiles.press(v);
            start(open);
        });
        reveal(openTile);
    }

    private void reveal(View v) {
        v.setVisibility(View.VISIBLE);
        v.setAlpha(0f);
        v.setScaleX(0.9f);
        v.setScaleY(0.9f);
        v.animate().alpha(1f).scaleX(v.isFocused() ? Tiles.lift(v) : 1f).scaleY(v.isFocused() ? Tiles.lift(v) : 1f)
                .setDuration(480).setInterpolator(Tiles.EASE).start();
    }

    /** TMDB's provider names here are JustWatch's ("Apple TV Plus", "Amazon Prime Video"); match loosely. */
    private static boolean streamsOn(Tmdb.Details d, Tmdb.Provider p) {
        String want = p.label.toLowerCase(Locale.US).replace("+", "").replace(" video", "").trim();
        for (String s : d.streamingOn) {
            String have = s.toLowerCase(Locale.US).replace("+", "").replace(" plus", "");
            if (have.contains(want) || want.contains(have.trim())) return true;
        }
        return false;
    }

    private void start(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not open " + intent + ": " + e);
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
