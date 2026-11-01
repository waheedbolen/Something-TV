package com.tvrefresh.home;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Dubai time large, with seconds; three other cities; the day's light; the screensaver and the TV's clock settings. */
public class ClockActivity extends TileActivity {
    /** The world clocks, in order. Change the cities here. */
    private static final String[][] WORLD = {
            {"London", "Europe/London"},
            {"New York", "America/New_York"},
            {"Tokyo", "Asia/Tokyo"},
    };

    private DotMatrixView hours, minutes, secs;
    private View colon;
    private TextView date, ampm, sunText;
    private final TextView[] cityTime = new TextView[WORLD.length];
    private final TextView[] cityNote = new TextView[WORLD.length];
    private final DotMatrixView[] cityDots = new DotMatrixView[WORLD.length];
    private DotBar bar;
    private int lastMinute = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Calendar c = Weather.now();
            int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            show(c, minute != lastMinute && lastMinute >= 0);
            lastMinute = minute;
            colon.animate().alpha(c.get(Calendar.SECOND) % 2 == 0 ? 1f : 0.25f).setDuration(480).start();
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    void build() {
        FrameLayout big = card(true, 0, 0, 5, 3);
        date = label(11, Palette.alpha(pal.onInverse, 0.6f));
        big.addView(date, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(24), dp(24), 0, 0));
        TextView zone = label(9, Palette.alpha(pal.onInverse, 0.45f));
        zone.setText(getString(R.string.clock_zone, Weather.CITY));
        big.addView(zone, Tiles.at(Gravity.TOP | Gravity.END, WRAP, WRAP, 0, dp(26), dp(24), 0));

        LinearLayout time = new LinearLayout(this);
        time.setGravity(Gravity.CENTER_VERTICAL);
        float pitch = dp(14);
        hours = new DotMatrixView(this, pitch);
        minutes = new DotMatrixView(this, pitch);
        int on = pal.onInverse, off = Palette.alpha(pal.onInverse, 0.05f);
        hours.setColors(on, off);
        minutes.setColors(on, off);
        int w = Math.round(pitch * 12), h = Math.round(pitch * 7);
        time.addView(hours, new LinearLayout.LayoutParams(w, h));
        colon = colonDots();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(10), dp(56));
        clp.setMargins(dp(20), 0, dp(20), 0);
        time.addView(colon, clp);
        time.addView(minutes, new LinearLayout.LayoutParams(w, h));
        big.addView(time, Tiles.at(Gravity.CENTER, WRAP, WRAP, 0, 0, 0, dp(6)));

        ampm = label(11, Palette.alpha(pal.onInverse, 0.6f));
        big.addView(ampm, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(24), 0, 0, dp(26)));
        secs = new DotMatrixView(this, dp(4.5f));
        secs.setColors(Palette.ACCENT, Palette.alpha(pal.onInverse, 0.05f));
        big.addView(secs, Tiles.at(Gravity.BOTTOM | Gravity.END, Math.round(dp(4.5f) * 12), Math.round(dp(4.5f) * 7), 0, 0, dp(24), dp(22)));

        for (int i = 0; i < WORLD.length; i++) city(i);

        FrameLayout sun = card(false, 0, 3, 4, 1);
        TextView l = label(8.5f, pal.dim());
        l.setText(R.string.clock_daylight);
        sun.addView(l, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(18), dp(16), 0, 0));
        sunText = label(8.5f, pal.ink);
        sun.addView(sunText, Tiles.at(Gravity.TOP | Gravity.END, WRAP, WRAP, 0, dp(16), dp(18), 0));
        bar = new DotBar(this, dp(11.5f), 34);
        sun.addView(bar, Tiles.at(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, WRAP, WRAP, 0, 0, 0, dp(20)));
        daylight();

        View saver = action(DotMatrixView.MOON, getString(R.string.clock_screensaver), getString(R.string.clock_start_now), 4, 3, 2,
                () -> open(new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator")));
        action(DotMatrixView.SETTINGS, getString(R.string.clock_settings), getString(R.string.clock_settings_note), 6, 3, 2,
                () -> open(new Intent(Settings.ACTION_DATE_SETTINGS)));
        show(Weather.now(), false);
        saver.post(saver::requestFocus);
    }

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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(10), dp(10));
            if (k > 0) lp.topMargin = dp(26);
            c.addView(dot, lp);
        }
        return c;
    }

    private void city(int i) {
        FrameLayout t = card(false, 5, i, 3, 1);
        TextView name = label(9, pal.dim());
        name.setText(WORLD[i][0]);
        t.addView(name, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(18), dp(16), 0, 0));
        cityNote[i] = label(8, pal.dim());
        t.addView(cityNote[i], Tiles.at(Gravity.TOP | Gravity.END, WRAP, WRAP, 0, dp(17), dp(18), 0));
        cityTime[i] = text(tiles.light, 30, pal.ink);
        cityTime[i].setLetterSpacing(-0.03f);
        t.addView(cityTime[i], Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(16), 0, 0, dp(12)));
        cityDots[i] = new DotMatrixView(this, dp(2.6f));
        cityDots[i].setColors(pal.ink, 0);
        t.addView(cityDots[i], Tiles.at(Gravity.BOTTOM | Gravity.END, WRAP, WRAP, 0, 0, dp(18), dp(18)));
    }

    private void show(Calendar now, boolean animate) {
        boolean h24 = DateFormat.is24HourFormat(this);
        int h = now.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!h24 && h == 0) h = 12;
        hours.setDigits(String.format(Locale.US, "%02d", h), animate);
        minutes.setDigits(String.format(Locale.US, "%02d", now.get(Calendar.MINUTE)), animate);
        secs.setDigits(String.format(Locale.US, "%02d", now.get(Calendar.SECOND)), false);   // a step each second
        ampm.setText(h24 ? "" : now.get(Calendar.AM_PM) == Calendar.AM ? "am" : "pm");
        SimpleDateFormat f = new SimpleDateFormat("EEEE d MMMM", Locale.US);
        f.setTimeZone(Weather.ZONE);
        date.setText(f.format(now.getTime()));

        long t = now.getTimeInMillis();
        int here = Weather.ZONE.getOffset(t);
        int today = now.get(Calendar.DAY_OF_YEAR);
        for (int i = 0; i < WORLD.length; i++) {
            TimeZone z = TimeZone.getTimeZone(WORLD[i][1]);
            java.text.DateFormat tf = DateFormat.getTimeFormat(this);
            tf.setTimeZone(z);
            cityTime[i].setText(tf.format(now.getTime()).toLowerCase(Locale.US));
            Calendar c = Calendar.getInstance(z, Locale.US);
            c.setTimeInMillis(t);
            int diff = (z.getOffset(t) - here) / 60_000;
            String hours = (diff >= 0 ? "+" : "−") + Math.abs(diff) / 60 + (Math.abs(diff) % 60 == 0 ? "H" : "H" + Math.abs(diff) % 60);
            int day = c.get(Calendar.DAY_OF_YEAR);
            String when = day == today ? "" : (day > today || (today > 300 && day < 10)) ? "  ·  " + getString(R.string.clock_tomorrow)
                    : "  ·  " + getString(R.string.clock_yesterday);
            cityNote[i].setText(hours + when);
            int hr = c.get(Calendar.HOUR_OF_DAY);
            cityDots[i].setPattern(hr >= 6 && hr < 18 ? DotMatrixView.SUN : DotMatrixView.MOON, animate);
        }
        if (animate) daylight();
    }

    private void daylight() {
        Weather w = weather;
        if (w == null || w.sunrise < 0 || w.sunset <= w.sunrise) {
            sunText.setText("");
            bar.set(-1f, pal.ink, Palette.alpha(pal.ink, 0.12f));
            return;
        }
        Calendar c = Weather.now();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        float p = (now - w.sunrise) / (float) (w.sunset - w.sunrise);
        bar.set(p < 0 || p > 1 ? -1f : p, pal.ink, Palette.alpha(pal.ink, 0.12f));
        if (now < w.sunrise) sunText.setText(getString(R.string.clock_sunrise_in, (w.sunrise - now) / 60, (w.sunrise - now) % 60));
        else if (now < w.sunset) sunText.setText(getString(R.string.clock_sunset_in, (w.sunset - now) / 60, (w.sunset - now) % 60));
        else sunText.setText(getString(R.string.wx_sunrise, time(w.sunrise)));
    }
}
