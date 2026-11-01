package com.tvrefresh.home;

import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver: a 4K landscape (Lorem Picsum) every minute, crossfading and drifting slowly, dimmed for the room;
 * and in the corner Home's dot-matrix clock with the colon beating in the accent colour, the date and the weather.
 * The clock moves a little each minute and the picture keeps changing, so nothing burns in.
 */
public class ClockDream extends DreamService {
    private static final long PHOTO_EVERY = 60_000;
    private static final long MOVE_EVERY = 60_000;
    private static final float DRIFT = 1.07f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private Tiles tiles;
    private Backdrop backdrop;
    private FrameLayout stage;
    private LinearLayout block;
    private DotMatrixView hours, minutes;
    private View colon;
    private TextView date, credit, ampm;
    private int lastMinute = -1;
    private boolean zoomIn = true;
    private float pan = 1f;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Calendar c = Weather.now();
            int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            if (minute != lastMinute) {
                showTime(lastMinute >= 0);
                lastMinute = minute;
            }
            colon.animate().alpha(c.get(Calendar.SECOND) % 2 == 0 ? 1f : 0.25f).setDuration(500).start();
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };
    private final Runnable nextPhoto = new Runnable() {
        @Override public void run() {
            backdrop.advance();
            handler.postDelayed(this, PHOTO_EVERY);
        }
    };
    private final Runnable nudge = new Runnable() {
        @Override public void run() {
            block.animate().alpha(0f).setDuration(900).setInterpolator(Tiles.EASE).withEndAction(() -> {
                place();
                block.animate().alpha(1f).setDuration(1400).setInterpolator(Tiles.EASE).start();
            }).start();
            handler.postDelayed(this, MOVE_EVERY);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        tiles = new Tiles(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // The pictures, on a stage that drifts: in during one photo, out during the next, so it never jumps.
        stage = new FrameLayout(this);
        ImageView bottom = photo(), top = photo();
        stage.addView(bottom);
        stage.addView(top);
        root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backdrop = new Backdrop(this, bottom, top, io, (photo, author) -> {
            credit.setText(author == null || author.isEmpty() ? "" : getString(R.string.photo_credit, author));
            drift();
        });

        // Dim the room's brightest thing a little, and much more behind the clock.
        View dim = new View(this);
        dim.setBackgroundColor(0x40000000);
        root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xCC000000, 0x55000000, 0x00000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tiles.dp(300), Gravity.BOTTOM));

        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setAlpha(0f);
        LinearLayout time = new LinearLayout(this);
        time.setGravity(Gravity.CENTER_VERTICAL);
        float pitch = tiles.dp(9);
        hours = new DotMatrixView(this, pitch);
        minutes = new DotMatrixView(this, pitch);
        for (DotMatrixView d : new DotMatrixView[] {hours, minutes}) d.setColors(0xFFFFFFFF, 0x12FFFFFF);
        int w = Math.round(pitch * 12), h = Math.round(pitch * 7);
        time.addView(hours, new LinearLayout.LayoutParams(w, h));
        colon = colonDots();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(tiles.dp(7), tiles.dp(38));
        clp.setMargins(tiles.dp(14), 0, tiles.dp(14), 0);
        time.addView(colon, clp);
        time.addView(minutes, new LinearLayout.LayoutParams(w, h));
        ampm = tiles.label(11);
        ampm.setTextColor(0xB3FFFFFF);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.gravity = Gravity.BOTTOM;
        alp.setMargins(tiles.dp(12), 0, 0, tiles.dp(2));
        time.addView(ampm, alp);
        // WRAP, not the vertical layout's default MATCH, which would cut the time to the width of the text lines.
        block.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        date = tiles.label(12);
        date.setTextColor(0xE6FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = tiles.dp(20);
        block.addView(date, dlp);
        Weather wx = Weather.cached(getSharedPreferences("home", MODE_PRIVATE));
        if (wx != null) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            DotMatrixView icon = new DotMatrixView(this, tiles.dp(1.9f));
            icon.setPattern(wx.dots(), false);
            icon.setColors(Palette.ACCENT, 0);
            line.addView(icon, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView words = tiles.label(10.5f);
            words.setTextColor(0xB3FFFFFF);
            words.setText(getString(R.string.temp, wx.temp) + "  ·  " + wx.condition() + "  ·  " + Weather.CITY);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.leftMargin = tiles.dp(9);
            line.addView(words, wlp);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = tiles.dp(9);
            block.addView(line, llp);
        }
        root.addView(block, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));

        credit = tiles.label(8);
        credit.setAllCaps(false);
        credit.setTextColor(0x80FFFFFF);
        root.addView(credit, Tiles.at(Gravity.BOTTOM | Gravity.END, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 0, tiles.dp(40), tiles.dp(30)));
        setContentView(root);
        showTime(false);
    }

    private ImageView photo() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    /** Two accent dots, one above the other. */
    private View colonDots() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        for (int k = 0; k < 2; k++) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Palette.ACCENT);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tiles.dp(7), tiles.dp(7));
            if (k > 0) lp.topMargin = tiles.dp(18);
            c.addView(dot, lp);
        }
        return c;
    }

    /**
     * One slow move per photo, the other way each time: in while sliding a little to one side, then out while sliding
     * back to the middle. The slide stays within what the zoom adds, so no edge of the picture ever shows.
     */
    private void drift() {
        float to = zoomIn ? DRIFT : 1f;
        float x = 0;
        if (zoomIn) {
            pan = -pan;
            x = pan * tiles.dp(14);
        }
        zoomIn = !zoomIn;
        stage.animate().cancel();
        stage.animate().scaleX(to).scaleY(to).translationX(x).setDuration(PHOTO_EVERY + 4000)
                .setInterpolator(new LinearInterpolator()).start();
    }

    private void showTime(boolean animate) {
        Calendar c = Weather.now();
        boolean h24 = DateFormat.is24HourFormat(this);
        int h = c.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!h24 && h == 0) h = 12;
        hours.setDigits(String.format(Locale.US, "%02d", h), animate);
        minutes.setDigits(String.format(Locale.US, "%02d", c.get(Calendar.MINUTE)), animate);
        SimpleDateFormat f = new SimpleDateFormat("EEEE d MMMM", Locale.US);
        f.setTimeZone(Weather.ZONE);
        date.setText(f.format(c.getTime()));
        ampm.setText(h24 ? "" : c.get(Calendar.AM_PM) == Calendar.AM ? "am" : "pm");
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        backdrop.showCurrent();
        block.post(() -> {
            place();
            block.animate().alpha(1f).setDuration(1400).setInterpolator(Tiles.EASE).start();
        });
        handler.post(tick);
        handler.postDelayed(nextPhoto, 8000);
        handler.postDelayed(nudge, MOVE_EVERY);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacksAndMessages(null);
        backdrop.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        io.shutdownNow();
        super.onDetachedFromWindow();
    }

    /** Near the bottom-left corner, a little different each time, so no pixel holds the same thing for long. */
    private void place() {
        block.setTranslationX(tiles.dp(56 + random.nextInt(40)));
        block.setTranslationY(-tiles.dp(44 + random.nextInt(36)));
    }
}
