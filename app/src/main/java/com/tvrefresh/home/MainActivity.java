package com.tvrefresh.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Home as a grid of tiles, after Nothing's widgets: a dot-matrix clock, what is trending, Dubai weather, the apps,
 * a pick from each UAE service, and a row per service below. Light by day, dark by night, one accent in both.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Home";

    // The grid, in dp: 8 columns x 4 rows of 100 dp cells with 12 dp gaps, centred on the 960 x 540 dp screen.
    private static final int CELL = 100, GAP = 12, X0 = 38, Y0 = 52;
    private static final int DOCK = 5;              // app tiles on the grid; every app is in All apps below
    private static final int FEATURED = 5;
    private static final long FEATURE_EVERY = 9000;
    private static final long ROWS_FRESH = 30 * 60 * 1000L;
    private static final long WEATHER_EVERY = 15 * 60 * 1000L;
    private static final long THEME_FADE = 1800;
    private static final int INVERSE = 0, SURFACE = 1, ACCENT = 2;

    private static final class Row {
        final String label;
        final Tmdb.Provider provider;
        final List<Title> titles;

        Row(String label, Tmdb.Provider provider, List<Title> titles) {
            this.label = label;
            this.provider = provider;
            this.titles = titles;
        }
    }

    /**
     * A ScrollView that leaves scrolling to Home: it glides to a whole section instead of nudging per key. It also
     * hides the rows more than a screen away (cull), so the TV draws a screenful of tiles each frame, not hundreds.
     */
    static final class Pager extends ScrollView {
        private ValueAnimator glide;
        private ViewGroup[] culled = new ViewGroup[0];

        Pager(Context c) {
            super(c);
            setFocusable(false);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }

        @Override
        public boolean executeKeyEvent(KeyEvent event) {
            return false;   // focus moves by the window's own search; glideTo() follows it
        }

        /** Hide what is far from view among these groups' children, now and whenever the page moves. */
        void cull(ViewGroup... groups) {
            culled = groups;
            setOnScrollChangeListener((v, x, y, ox, oy) -> applyCull());
            for (ViewGroup g : groups) g.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(this::applyCull));
        }

        void applyCull() {
            int top = getScrollY(), h = getHeight();
            if (h == 0) return;
            for (ViewGroup g : culled) {
                int base = 0;
                for (View v = g; v != null && v.getParent() != this; v = (View) v.getParent()) base += v.getTop();
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c.getVisibility() == GONE) continue;
                    // A screen of margin each way, so the next row is always there to take focus.
                    boolean near = base + c.getBottom() > top - h && base + c.getTop() < top + 2 * h;
                    int want = near ? VISIBLE : INVISIBLE;
                    if (c.getVisibility() != want) c.setVisibility(want);
                }
            }
        }

        void glideTo(int y) {
            int max = Math.max(0, getChildAt(0).getHeight() - getHeight());
            int to = Math.max(0, Math.min(max, y));
            if (glide != null) glide.cancel();
            if (to == getScrollY()) return;
            glide = ValueAnimator.ofInt(getScrollY(), to);
            glide.setDuration(560);
            glide.setInterpolator(Tiles.EASE);
            glide.addUpdateListener(a -> scrollTo(0, (int) a.getAnimatedValue()));
            glide.start();
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newFixedThreadPool(4);
    private SharedPreferences prefs;
    private Tmdb tmdb;
    private Images images;
    private Tiles tiles;
    private Shelves shelves;
    private Shelves.Data shelf;
    private Weather weather;

    private float night;
    private float nightTarget = -1;
    private Palette pal;
    private ValueAnimator themeFade;
    private final ColorDrawable windowBg = new ColorDrawable();
    /** What each tile does when the look changes, by group, so a rebuilt group drops its old entries. */
    private final Map<String, List<Runnable>> themed = new LinkedHashMap<>();

    private Pager pager;
    private FrameLayout bento;
    private LinearLayout more;
    private final List<View> grid = new ArrayList<>();
    private final List<View> dock = new ArrayList<>();
    private final List<View> picks = new ArrayList<>();

    private DotMatrixView hours, minutes, wxDots;
    private TextView date, ampm, wxTemp, wxCity;
    private Runnable lookShown;
    private final TextView[] greeting = new TextView[3];
    private View seconds;
    private int lastMinute = -1;

    private FrameLayout feature;
    private ImageView featA, featB, featLogo;
    private TextView featName, featMeta;
    private LinearLayout featDots;
    private List<Title> featured = new ArrayList<>();
    private Map<String, String> logos = new HashMap<>();
    private int featIndex, featGen;
    private boolean featFocused, started, shelvesReleased;
    private View myListRow;
    private int myListShown;

    private Apps appList;
    private List<Apps.App> apps = new ArrayList<>();
    private List<Row> rows = new ArrayList<>();
    private long loadedAt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Calendar c = Weather.now();
            int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            if (minute != lastMinute) {
                lastMinute = minute;
                showTime(true);
                showGreeting();
                checkTheme(true);
            }
            seconds.setAlpha(c.get(Calendar.SECOND) % 2 == 0 ? 1f : 0.15f);   // a step, not a fade: one frame a second
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };
    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            refreshWeather(true);
            handler.postDelayed(this, WEATHER_EVERY);
        }
    };
    private final Runnable featureTick = () -> showFeature(featIndex + 1, true);
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            apps = appList.load();
            buildDock();
            buildMore();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        tmdb = new Tmdb(this);
        images = Images.get(this);
        tiles = new Tiles(this);
        appList = new Apps(this, tiles);
        shelves = new Shelves(this, tiles, images, new Shelves.Look() {
            @Override public Palette pal() {
                return pal;
            }

            @Override public void themed(Runnable apply) {
                MainActivity.this.themed("more", apply);
            }
        }, X0);
        weather = Weather.cached(prefs);
        takeTheme(getIntent());
        night = Palette.isNight(prefs, weather) ? 1f : 0f;
        pal = Palette.at(night);
        getWindow().setBackgroundDrawable(windowBg);

        FrameLayout root = new FrameLayout(this);
        pager = new Pager(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        bento = new FrameLayout(this);
        bento.setClipChildren(false);
        page.addView(bento, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(540)));
        more = new LinearLayout(this);
        more.setOrientation(LinearLayout.VERTICAL);
        more.setClipChildren(false);
        more.setPadding(0, 0, 0, dp(Y0));
        page.addView(more, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pager.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(pager, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        themed("page", () -> {
            windowBg.setColor(pal.bg);
        });

        buildClock();
        buildFeature();
        buildWeather();
        buildLookSwitch();
        circle(6, 1, DotMatrixView.SEARCH, R.string.search_short, INVERSE, v -> startActivity(new Intent(this, SearchActivity.class)));
        circle(7, 1, DotMatrixView.SETTINGS, R.string.settings, SURFACE, v -> open(new Intent(Settings.ACTION_SETTINGS)));
        circle(5, 2, DotMatrixView.INPUTS, R.string.inputs, ACCENT, v -> startActivity(new Intent(this, InputsActivity.class)));
        buildGreeting();
        apps = appList.load();
        buildDock();
        buildPicks(Collections.emptyList());
        buildMore();
        showTime(false);
        showWeather();
        applyTheme();

        pager.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> follow(now));
        pager.cull(page, more);
        bento.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                bento.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                focusHome();
                tiles.enter(new ArrayList<>(grid));
            }
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        handler.post(tick);
        refreshWeather(false);
        handler.postDelayed(weatherTick, WEATHER_EVERY);
        if (loadedAt > 0 && System.currentTimeMillis() - loadedAt > ROWS_FRESH) load();
        scheduleFeature();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (shelvesReleased) buildMore();
        else if (MyList.version(prefs) != myListShown) {
            // Something was saved or removed on a detail card: only the My List row changes.
            if (myListRow != null) more.removeView(myListRow);
            myListShown = MyList.version(prefs);
            myListRow = shelves.myListRow(MyList.all(prefs));
            if (myListRow != null) more.addView(myListRow, 0);
        }
        if (pager.getScrollY() == 0) {
            bento.setAlpha(0f);
            bento.setTranslationY(dp(10));
            bento.animate().alpha(1f).translationY(0).setDuration(420).setInterpolator(Tiles.EASE).withLayer().start();
        }
    }

    @Override
    protected void onStop() {
        started = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(weatherTick);
        handler.removeCallbacks(featureTick);
        super.onStop();
    }

    /**
     * While another app is in front, the shelves' pictures give their memory back (this TV has 2 GB); they are
     * built again from what was loaded when Home returns.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND && !shelvesReleased && !started) {
            more.removeAllViews();
            themed.remove("more");
            shelvesReleased = true;
            pager.scrollTo(0, 0);
        }
        if (level >= TRIM_MEMORY_UI_HIDDEN) images.trim();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    /** Home pressed while on Home: back to the top. `--es theme day|night|auto` also sets the look (for testing). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String page = intent.getStringExtra("open");
        if ("clock".equals(page)) startActivity(new Intent(this, ClockActivity.class));
        if ("weather".equals(page)) startActivity(new Intent(this, WeatherActivity.class));
        if ("browse".equals(page)) startActivity(new Intent(this, BrowseActivity.class));
        if ("search".equals(page)) startActivity(new Intent(this, SearchActivity.class));
        if ("inputs".equals(page)) startActivity(new Intent(this, InputsActivity.class));
        if (takeTheme(intent)) {
            lookShown.run();
            checkTheme(true);
        }
        pager.glideTo(0);
        focusHome();
    }

    @Override
    public void onBackPressed() {
        pager.glideTo(0);
        focusHome();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        Boolean handled = Sounds.navigate(this, e);
        return handled != null ? handled : super.dispatchKeyEvent(e);
    }

    private boolean takeTheme(Intent intent) {
        String theme = intent == null ? null : intent.getStringExtra("theme");
        if (theme == null) return false;
        prefs.edit().putString("theme", theme).apply();
        return true;
    }

    private void focusHome() {
        View first = !dock.isEmpty() ? dock.get(0) : feature;
        first.requestFocus();
    }

    /** The page glides with focus: the grid at the top, or the focused row near the top of the screen. */
    private void follow(View now) {
        if (now == null) return;
        if (within(bento, now)) {
            pager.glideTo(0);
            return;
        }
        for (int i = 0; i < more.getChildCount(); i++) {
            View row = more.getChildAt(i);
            if (within(row, now)) {
                pager.glideTo(bento.getHeight() + row.getTop() - dp(Y0 - 12));
                return;
            }
        }
    }

    private static boolean within(View parent, View v) {
        for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    // ---- The look -----------------------------------------------------------------------------------------

    /** A Look whose colour changes land in one of Home's groups, so a rebuilt group drops its old ones. */
    private Shelves.Look look(String group) {
        return new Shelves.Look() {
            @Override public Palette pal() {
                return pal;
            }

            @Override public void themed(Runnable apply) {
                MainActivity.this.themed(group, apply);
            }
        };
    }

    private void themed(String group, Runnable apply) {
        List<Runnable> list = themed.get(group);
        if (list == null) themed.put(group, list = new ArrayList<>());
        list.add(apply);
        apply.run();
    }

    private void applyTheme() {
        pal = Palette.at(night);
        for (List<Runnable> list : themed.values()) for (Runnable r : list) r.run();
    }

    /** Day or night by the sun; a change fades through every colour over a couple of seconds. */
    private void checkTheme(boolean animate) {
        float target = Palette.isNight(prefs, weather) ? 1f : 0f;
        boolean fading = themeFade != null && themeFade.isRunning();
        if (fading ? target == nightTarget : target == night) return;
        nightTarget = target;
        if (themeFade != null) themeFade.cancel();
        if (!animate) {
            night = target;
            applyTheme();
            return;
        }
        themeFade = ValueAnimator.ofFloat(night, target);
        themeFade.setDuration(THEME_FADE);
        themeFade.setInterpolator(Tiles.EASE);
        themeFade.addUpdateListener(a -> {
            night = (float) a.getAnimatedValue();
            applyTheme();
        });
        themeFade.start();
    }

    // ---- The grid -----------------------------------------------------------------------------------------

    private static int span(int n) {
        return n * CELL + (n - 1) * GAP;
    }

    private <T extends View> T place(T v, int col, int row, int cols, int rows) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(span(cols)), dp(span(rows)), Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(X0 + col * (CELL + GAP));
        lp.topMargin = dp(Y0 + row * (CELL + GAP));
        bento.addView(v, lp);
        grid.add(v);
        // Each grid tile is kept as a ready-made picture on the GPU and only redrawn when it changes: a frame is then
        // twenty pictures moved into place, not every photo, gradient and word inside them drawn again.
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        return v;
    }

    private void unplace(List<View> views) {
        for (View v : views) {
            bento.removeView(v);
            grid.remove(v);
        }
        views.clear();
    }

    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT, MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private void buildClock() {
        FrameLayout t = place(tiles.tile(false, true), 0, 0, 2, 2);
        t.setContentDescription(getString(R.string.clock));
        t.setOnClickListener(v -> {
            Tiles.press(v);
            startActivity(new Intent(this, ClockActivity.class));
        });
        date = tiles.label(11);
        t.addView(date, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(18), dp(19), 0, 0));
        seconds = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Palette.ACCENT);
        seconds.setBackground(dot);
        t.addView(seconds, Tiles.at(Gravity.TOP | Gravity.END, dp(7), dp(7), 0, dp(21), dp(20), 0));
        hours = new DotMatrixView(this, dp(10));
        minutes = new DotMatrixView(this, dp(10));
        t.addView(hours, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(18), dp(44), 0, 0));
        t.addView(minutes, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(18), dp(124), 0, 0));
        ampm = tiles.label(11);
        t.addView(ampm, Tiles.at(Gravity.BOTTOM | Gravity.END, WRAP, WRAP, 0, 0, dp(18), dp(18)));
        themed("grid", () -> {
            Tiles.fill(t, pal.inverse);
            hours.setColors(pal.onInverse, Palette.alpha(pal.onInverse, 0.045f));
            minutes.setColors(pal.onInverse, Palette.alpha(pal.onInverse, 0.045f));
            date.setTextColor(Palette.alpha(pal.onInverse, 0.6f));
            ampm.setTextColor(Palette.alpha(pal.onInverse, 0.6f));
        });
    }

    private void showTime(boolean animate) {
        Calendar c = Weather.now();
        boolean h24 = DateFormat.is24HourFormat(this);
        int h = c.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!h24 && h == 0) h = 12;
        hours.setDigits(String.format(Locale.US, "%02d", h), animate);
        minutes.setDigits(String.format(Locale.US, "%02d", c.get(Calendar.MINUTE)), animate);
        date.setText(dubai("EEE dd MMM").format(c.getTime()));
        ampm.setText(h24 ? "" : c.get(Calendar.AM_PM) == Calendar.AM ? "am" : "pm");
        lastMinute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private void buildFeature() {
        feature = place(tiles.tile(false, true), 2, 0, 4, 2);
        Tiles.clip(feature);
        featA = photo(feature);
        featB = photo(feature);
        featB.setAlpha(0f);
        View scrim = new View(this);
        scrim.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[] {
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xB8000000, 0x1A000000, 0x00000000}),
                new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0x8C000000, 0x00000000, 0x00000000})}));
        feature.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));
        feature.addView(tiles.chip(getString(R.string.trending_now)), Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(16), dp(16), 0, 0));
        featLogo = new ImageView(this);
        featLogo.setAdjustViewBounds(true);
        featLogo.setMaxWidth(dp(200));
        featLogo.setMaxHeight(dp(62));
        featLogo.setScaleType(ImageView.ScaleType.FIT_START);
        featLogo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        feature.addView(featLogo, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(20), 0, 0, dp(44)));
        featName = tiles.text(tiles.medium, 22);
        featName.setTextColor(0xFFFFFFFF);
        featName.setSingleLine(false);
        featName.setMaxLines(2);
        featName.setMaxWidth(dp(260));
        feature.addView(featName, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(20), 0, 0, dp(44)));
        featMeta = tiles.label(10);
        featMeta.setTextColor(0xCCFFFFFF);
        featMeta.setText(R.string.loading);
        feature.addView(featMeta, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(20), 0, 0, dp(20)));
        featDots = new LinearLayout(this);
        featDots.setGravity(Gravity.CENTER_VERTICAL);
        feature.addView(featDots, Tiles.at(Gravity.BOTTOM | Gravity.END, WRAP, dp(6), 0, 0, dp(20), dp(24)));
        feature.setOnFocusChangeListener((v, f) -> {
            tiles.focus(v, f);
            featFocused = f;
            if (f) handler.removeCallbacks(featureTick);
            else scheduleFeature();
        });
        feature.setOnClickListener(v -> {
            Tiles.press(v);
            if (!featured.isEmpty()) openDetail(featured.get(featIndex));
        });
        themed("grid", () -> Tiles.fill(feature, pal.surface));
    }

    private ImageView photo(FrameLayout tile) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        return iv;
    }

    private void scheduleFeature() {
        handler.removeCallbacks(featureTick);
        if (started && !featFocused && featured.size() > 1) handler.postDelayed(featureTick, FEATURE_EVERY);
    }

    /** The photo and its logo are fetched first, so the picture, the title and the dot change together. */
    private void showFeature(int index, boolean animate) {
        if (featured.isEmpty()) return;
        int i = index % featured.size();
        Title t = featured.get(i);
        String logo = logos.get(t.type + t.id);
        int gen = ++featGen;
        net.execute(() -> {
            Bitmap photo = t.backdrop == null ? null : images.fetch(Tmdb.image("w1280", t.backdrop), dp(span(4)), dp(span(2)));
            Bitmap mark = logo == null ? null : images.fetch(Tmdb.image("w500", logo), dp(200), dp(62));
            handler.post(() -> {
                if (gen != featGen || isDestroyed()) return;
                featIndex = i;
                swapFeature(t, photo, mark, animate);
                scheduleFeature();
            });
        });
    }

    private void swapFeature(Title t, Bitmap photo, Bitmap mark, boolean animate) {
        // The new photo goes on whichever layer leaves the old one in view while it fades in over it, or out.
        boolean onTop = featB.getAlpha() < 0.5f;
        ImageView in = onTop ? featB : featA;
        in.setImageBitmap(photo);
        if (onTop) {
            featB.setAlpha(0f);
            featB.animate().alpha(1f).setDuration(animate ? 1000 : 0).setInterpolator(Tiles.EASE).start();
        } else {
            featA.setAlpha(1f);
            featB.animate().alpha(0f).setDuration(animate ? 1000 : 0).setInterpolator(Tiles.EASE).start();
        }

        Runnable words = () -> {
            featLogo.setImageBitmap(mark);
            featLogo.setVisibility(mark != null ? View.VISIBLE : View.GONE);
            featName.setVisibility(mark != null ? View.GONE : View.VISIBLE);
            featName.setText(t.name);
            List<String> m = new ArrayList<>();
            if (t.provider != null) m.add(t.provider.label);
            m.add(t.isMovie() ? getString(R.string.film) : getString(R.string.series));
            if (!t.year.isEmpty()) m.add(t.year);
            if (t.rating > 0) m.add(String.format(Locale.US, "★ %.1f", t.rating));
            featMeta.setText(android.text.TextUtils.join("  ·  ", m));
        };
        View[] text = {featLogo, featName, featMeta};
        if (!animate) {
            words.run();
        } else {
            for (View v : text) v.animate().alpha(0f).translationY(-dp(6)).setDuration(200).setInterpolator(Tiles.EASE).start();
            handler.postDelayed(() -> {
                words.run();
                for (int k = 0; k < text.length; k++) {
                    View v = text[k];
                    v.setTranslationY(dp(10));
                    v.animate().alpha(1f).translationY(0).setStartDelay(k * 60L).setDuration(520).setInterpolator(Tiles.EASE)
                            .withEndAction(() -> v.animate().setStartDelay(0)).start();
                }
            }, 220);
        }
        for (int k = 0; k < featDots.getChildCount(); k++) markDot(featDots.getChildAt(k), k == featIndex, animate);
    }

    private void markDot(View d, boolean on, boolean animate) {
        ((GradientDrawable) d.getBackground()).setColor(on ? Palette.ACCENT : 0x80FFFFFF);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) d.getLayoutParams();
        int to = dp(on ? 18 : 6);
        if (!animate) {
            lp.width = to;
            d.setLayoutParams(lp);
            return;
        }
        ValueAnimator a = ValueAnimator.ofInt(lp.width, to);
        a.setDuration(480);
        a.setInterpolator(Tiles.EASE);
        a.addUpdateListener(x -> {
            lp.width = (int) x.getAnimatedValue();
            d.setLayoutParams(lp);
        });
        a.start();
    }

    private void buildWeather() {
        FrameLayout t = place(tiles.tile(false, true), 7, 0, 1, 1);
        t.setContentDescription(getString(R.string.weather));
        t.setOnClickListener(v -> {
            Tiles.press(v);
            startActivity(new Intent(this, WeatherActivity.class));
        });
        wxDots = new DotMatrixView(this, dp(4.4f));
        t.addView(wxDots, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(14), dp(14), 0, 0));
        wxCity = tiles.label(8);
        t.addView(wxCity, Tiles.at(Gravity.TOP | Gravity.END, WRAP, WRAP, 0, dp(16), dp(14), 0));
        wxTemp = tiles.text(tiles.light, 30);
        wxTemp.setLetterSpacing(-0.04f);
        t.addView(wxTemp, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(12), 0, 0, dp(10)));
        themed("grid", () -> {
            Tiles.fill(t, pal.surface);
            wxTemp.setTextColor(pal.ink);
            wxCity.setTextColor(pal.dim());
            wxDots.setColors(Palette.ACCENT, 0);
        });
    }

    private void showWeather() {
        wxCity.setText(Weather.CITY);
        if (weather == null) {
            wxTemp.setText("--°");
            wxDots.setPattern(DotMatrixView.CLOUD, false);
            return;
        }
        wxTemp.setText(getString(R.string.temp, weather.temp));
        wxDots.setPattern(weather.dots(), true);
    }

    private static final String[] LOOKS = {"auto", "day", "night"};

    /** Auto follows the sun; Light and Dark hold. Each press moves to the next, and the whole page fades over. */
    private void buildLookSwitch() {
        FrameLayout t = place(tiles.tile(true, true), 6, 0, 1, 1);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        DotMatrixView icon = new DotMatrixView(this, dp(DotMatrixView.ICON_PITCH));
        box.addView(icon, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView l = tiles.label(8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(8);
        box.addView(l, lp);
        t.addView(box, Tiles.at(Gravity.CENTER, WRAP, WRAP, 0, dp(4), 0, 0));
        Runnable show = () -> {
            String look = prefs.getString("theme", "auto");
            boolean animate = icon.getMeasuredWidth() > 0;
            icon.setPattern(look.equals("day") ? DotMatrixView.SUN : look.equals("night") ? DotMatrixView.MOON : DotMatrixView.AUTO, animate);
            l.setText(look.equals("day") ? R.string.look_light : look.equals("night") ? R.string.look_dark : R.string.look_auto);
            t.setContentDescription(getString(R.string.look) + ": " + l.getText());
        };
        show.run();
        t.setOnClickListener(v -> {
            Tiles.press(v);
            int k = Arrays.asList(LOOKS).indexOf(prefs.getString("theme", "auto"));
            prefs.edit().putString("theme", LOOKS[(k + 1) % LOOKS.length]).apply();
            show.run();
            showGreeting();
            checkTheme(true);
        });
        themed("grid", () -> {
            Tiles.fill(t, pal.surface);
            icon.setColors(pal.ink, 0);
            l.setTextColor(pal.ink);
        });
        lookShown = show;
    }

    private void circle(int col, int row, String[] icon, int label, int kind, View.OnClickListener click) {
        FrameLayout t = place(tiles.tile(true, true), col, row, 1, 1);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        DotMatrixView i = new DotMatrixView(this, dp(DotMatrixView.ICON_PITCH));
        i.setPattern(icon, false);
        box.addView(i, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView l = tiles.label(8);
        l.setText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(8);
        box.addView(l, lp);
        t.addView(box, Tiles.at(Gravity.CENTER, WRAP, WRAP, 0, dp(4), 0, 0));
        t.setContentDescription(getString(label));
        t.setOnClickListener(v -> {
            Tiles.press(v);
            click.onClick(v);
        });
        themed("grid", () -> {
            int bg = kind == INVERSE ? pal.inverse : kind == ACCENT ? Palette.ACCENT : pal.surface;
            int fg = kind == INVERSE ? pal.onInverse : kind == ACCENT ? 0xFFFFFFFF : pal.ink;
            Tiles.fill(t, bg);
            if (kind == ACCENT) tiles.ring(t, pal.ink);
            i.setColors(fg, 0);
            l.setTextColor(fg);
        });
    }

    private void buildGreeting() {
        FrameLayout t = place(tiles.tile(false, false), 6, 2, 2, 1);
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(dp(17), dp(5), dp(17), dp(5));
        List<View> rules = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            if (k > 0) {
                View rule = new View(this);
                lines.addView(rule, new LinearLayout.LayoutParams(MATCH, dp(1)));
                rules.add(rule);
            }
            greeting[k] = tiles.text(tiles.regular, 13);
            greeting[k].setGravity(Gravity.CENTER_VERTICAL);
            lines.addView(greeting[k], new LinearLayout.LayoutParams(MATCH, 0, 1f));
        }
        t.addView(lines, new FrameLayout.LayoutParams(MATCH, MATCH));
        themed("grid", () -> {
            Tiles.fill(t, pal.surface);
            for (View r : rules) r.setBackgroundColor(pal.rule());
            showGreeting();
        });
    }

    /** Three short lines, the facts in full ink and the words around them dimmed. */
    private void showGreeting() {
        Calendar c = Weather.now();
        int h = c.get(Calendar.HOUR_OF_DAY);
        greeting[0].setText(say(getString(h < 12 ? R.string.good_morning : h < 18 ? R.string.good_afternoon : R.string.good_evening) + ".", true));
        if (weather != null) {
            SpannableStringBuilder b = new SpannableStringBuilder();
            b.append(say(getString(R.string.its), false)).append(say(" " + getString(R.string.temp, weather.temp), true))
                    .append(say(" " + getString(R.string.and) + " ", false))
                    .append(say(weather.condition().toLowerCase(Locale.US), true)).append(say(".", false));
            greeting[1].setText(b);
        } else {
            greeting[1].setText(say(Weather.CITY, true));
        }
        boolean dark = Palette.sunDown(weather);     // the sun, not the look: Dark by choice at noon still means sunset next
        int at = weather == null ? -1 : dark ? weather.sunrise : weather.sunset;
        if (at >= 0) {
            Calendar when = Weather.now();
            when.set(Calendar.HOUR_OF_DAY, at / 60);
            when.set(Calendar.MINUTE, at % 60);
            java.text.DateFormat tf = DateFormat.getTimeFormat(this);
            tf.setTimeZone(Weather.ZONE);
            String time = tf.format(when.getTime()).toLowerCase(Locale.US);
            SpannableStringBuilder b = new SpannableStringBuilder();
            b.append(say(getString(dark ? R.string.sunrise_at : R.string.sunset_at) + " ", false)).append(say(time, true)).append(say(".", false));
            greeting[2].setText(b);
        } else {
            greeting[2].setText(say(dubai("EEEE d MMMM").format(c.getTime()), false));
        }
    }

    private static SimpleDateFormat dubai(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(Weather.ZONE);
        return f;
    }

    private CharSequence say(String s, boolean strong) {
        SpannableStringBuilder b = new SpannableStringBuilder(s);
        b.setSpan(new ForegroundColorSpan(strong ? pal.ink : pal.dim()), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    // ---- Apps ---------------------------------------------------------------------------------------------

    private void buildDock() {
        boolean hadFocus = false;
        for (View v : dock) hadFocus |= v.hasFocus();
        unplace(dock);
        themed.remove("dock");
        for (int i = 0; i < Math.min(DOCK, apps.size()); i++) dock.add(place(appList.tile(apps.get(i), look("dock")), i, 2, 1, 1));
        if (hadFocus) focusHome();
    }

    // ---- Films and series ---------------------------------------------------------------------------------

    private void load() {
        if (!tmdb.enabled()) return;
        io.execute(() -> {
            try {
                List<Tmdb.Provider> providers = tmdb.providers();
                List<Future<List<Title>>> lists = new ArrayList<>();
                for (Tmdb.Provider p : providers) lists.add(net.submit(() -> tmdb.popularOn(p)));
                List<Title> trending = tmdb.trending("all");
                List<Title> feat = new ArrayList<>(trending.subList(0, Math.min(FEATURED, trending.size())));
                Map<String, Future<String>> marks = new HashMap<>();
                for (Title t : feat) marks.put(t.type + t.id, net.submit(() -> tmdb.logo(t.type, t.id)));
                List<Row> loaded = new ArrayList<>();
                for (int i = 0; i < providers.size(); i++) {
                    try {
                        List<Title> titles = lists.get(i).get();
                        if (!titles.isEmpty()) loaded.add(new Row(getString(R.string.on_service, providers.get(i).label), providers.get(i), titles));
                    } catch (Exception e) {
                        Log.w(TAG, providers.get(i).label + ": " + e);
                    }
                }
                Map<String, String> found = new HashMap<>();
                for (Map.Entry<String, Future<String>> e : marks.entrySet()) {
                    try {
                        String path = e.getValue().get();
                        if (path != null) found.put(e.getKey(), path);
                    } catch (Exception ignored) {
                        // no logo: the name shows instead
                    }
                }
                handler.post(() -> {
                    if (isDestroyed()) return;
                    loadedAt = System.currentTimeMillis();
                    rows = loaded;
                    logos = found;
                    boolean first = featured.isEmpty();
                    featured = feat;
                    buildDots();
                    showFeature(first ? 0 : featIndex, !first);
                    List<Title> three = pickFrom(loaded, trending, feat);
                    buildPicks(three.subList(0, Math.min(3, three.size())));
                    buildMore();
                });
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "titles: " + e);
            }
            try {
                Shelves.Data d = Shelves.load(this, tmdb, net, "all");
                handler.post(() -> {
                    if (isDestroyed()) return;
                    shelf = d;
                    buildMore();
                });
            } catch (Exception e) {
                Log.w(TAG, "shelves: " + e);
            }
        });
    }

    private void buildDots() {
        featDots.removeAllViews();
        for (int k = 0; k < featured.size(); k++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(3));
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            if (k > 0) lp.leftMargin = dp(5);
            featDots.addView(d, lp);
            markDot(d, k == featIndex, false);
        }
    }

    /** One title from each service for the grid's bottom row, none of them already in the feature. */
    private static List<Title> pickFrom(List<Row> rows, List<Title> trending, List<Title> feat) {
        Set<String> used = new HashSet<>();
        for (Title t : feat) used.add(t.type + t.id);
        List<Title> out = new ArrayList<>();
        for (Row r : rows) {
            for (Title t : r.titles) {
                if (t.backdrop != null && used.add(t.type + t.id)) {
                    out.add(t);
                    break;
                }
            }
            if (out.size() == 4) return out;
        }
        for (Title t : trending) {
            if (out.size() == 4) break;
            if (used.add(t.type + t.id)) out.add(t);
        }
        return out;
    }

    private void buildPicks(List<Title> titles) {
        boolean hadFocus = false;
        for (View v : picks) hadFocus |= v.hasFocus();
        unplace(picks);
        themed.remove("picks");
        picks.add(place(browseTile(), 6, 3, 2, 1));
        for (int k = 0; k < 3; k++) {
            FrameLayout t;
            if (k < titles.size()) {
                Title title = titles.get(k);
                t = titleTile(title, title.provider != null ? title.provider.label : getString(R.string.trending_short), span(2), span(1), "picks");
            } else {
                t = tiles.tile(false, false);
                themed("picks", () -> Tiles.fill(t, pal.surface));
            }
            View placed = place(t, k * 2, 3, 2, 1);
            picks.add(placed);
            if (!titles.isEmpty()) {
                placed.setAlpha(0f);
                placed.animate().alpha(1f).setStartDelay(k * 70L).setDuration(520).setInterpolator(Tiles.EASE)
                        .withEndAction(() -> placed.animate().setStartDelay(0)).start();
            }
        }
        if (hadFocus && !picks.isEmpty()) picks.get(0).requestFocus();
    }

    /** The way into Browse: a dot poster wall, the words, and three of today's posters fanned out. */
    private FrameLayout browseTile() {
        FrameLayout t = tiles.tile(false, true);
        DotMatrixView icon = new DotMatrixView(this, dp(DotMatrixView.ICON_PITCH));
        icon.setPattern(DotMatrixView.BROWSE, false);
        t.addView(icon, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(16), dp(16), 0, 0));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = tiles.text(tiles.medium, 14);
        name.setText(R.string.browse);
        words.addView(name, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView note = tiles.label(8.5f);
        note.setText(R.string.browse_note);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(5);
        words.addView(note, lp);
        t.addView(words, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(16), 0, 0, dp(14)));
        List<Title> fan = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Row r : rows) {
            for (Title x : r.titles) {
                if (x.poster != null && seen.add(x.type + x.id)) {
                    if (fan.size() < 3) fan.add(x);
                    break;
                }
            }
        }
        for (int k = 0; k < fan.size(); k++) {
            ImageView p = new ImageView(this);
            p.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable clip = new GradientDrawable();
            clip.setCornerRadius(dp(6));
            clip.setColor(0xFF333333);
            p.setBackground(clip);
            p.setClipToOutline(true);
            p.setRotation((k - 1) * 7f);
            images.load(Tmdb.image("w185", fan.get(k).poster), p, dp(36), dp(54));
            t.addView(p, Tiles.at(Gravity.CENTER_VERTICAL | Gravity.END, dp(36), dp(54), 0, dp(k == 1 ? -6 : 4), dp(16 + (2 - k) * 24), 0));
        }
        t.setContentDescription(getString(R.string.browse));
        t.setOnClickListener(v -> {
            Tiles.press(v);
            startActivity(new Intent(this, BrowseActivity.class));
        });
        themed("picks", () -> {
            Tiles.fill(t, pal.surface);
            icon.setColors(pal.ink, 0);
            name.setTextColor(pal.ink);
            note.setTextColor(pal.dim());
        });
        return t;
    }

    private FrameLayout titleTile(Title t, String chip, int wDp, int hDp, String group) {
        FrameLayout tile = tiles.tile(false, true);
        Tiles.clip(tile);
        ImageView img = photo(tile);
        String path = t.backdrop != null ? Tmdb.image("w780", t.backdrop) : Tmdb.image("w342", t.poster);
        images.load(path, img, dp(wDp), dp(hDp));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xB3000000, 0x00000000}));
        tile.addView(scrim, new FrameLayout.LayoutParams(MATCH, dp(hDp * 0.65f), Gravity.BOTTOM));
        if (chip != null) tile.addView(tiles.chip(chip), Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(12), dp(12), 0, 0));
        TextView name = tiles.text(tiles.medium, 13);
        name.setTextColor(0xFFFFFFFF);
        name.setText(t.name);
        tile.addView(name, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(14), 0, dp(14), dp(13)));
        tile.setContentDescription(t.name);
        tile.setOnClickListener(v -> {
            Tiles.press(v);
            openDetail(t);
        });
        themed(group, () -> Tiles.fill(tile, pal.surface));
        return tile;
    }

    /** Below the grid, a press of Down away: the same shelves as Browse, and every app when the dock is full. */
    private void buildMore() {
        boolean hadFocus = more.hasFocus();
        more.removeAllViews();
        themed.remove("more");
        shelvesReleased = false;
        if (shelf != null) shelves.fill(more, shelf);
        myListShown = MyList.version(prefs);
        myListRow = shelves.myListRow(MyList.all(prefs));
        if (myListRow != null) more.addView(myListRow, 0);
        if (apps.size() > DOCK) {
            List<View> all = new ArrayList<>();
            for (Apps.App a : apps) all.add(appList.tile(a, look("more")));
            more.addView(shelves.row(getString(R.string.all_apps), all, dp(CELL), dp(CELL)), Math.max(0, more.getChildCount() - 1));
        }
        if (hadFocus) focusHome();
    }

    // ---- Opening things -----------------------------------------------------------------------------------

    private void openDetail(Title t) {
        startActivity(new Intent(this, DetailActivity.class)
                .putExtra(DetailActivity.TYPE, t.type)
                .putExtra(DetailActivity.ID, t.id)
                .putExtra(DetailActivity.NAME, t.name)
                .putExtra(DetailActivity.BACKDROP, t.backdrop)
                .putExtra(DetailActivity.POSTER, t.poster));
    }


    private boolean open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not open " + i + ": " + e);
            return false;
        }
    }

    // ---- Weather ------------------------------------------------------------------------------------------

    private void refreshWeather(boolean force) {
        // A forecast saved before sunrise and sunset were asked for is fetched again at once.
        if (!force && !Weather.stale(prefs, weather)) return;
        io.execute(() -> {
            try {
                Weather w = Weather.fetch(prefs);
                handler.post(() -> {
                    weather = w;
                    showWeather();
                    showGreeting();
                    checkTheme(true);
                });
            } catch (IOException | JSONException e) {
                Log.w(TAG, "weather: " + e.getMessage());
            }
        });
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
