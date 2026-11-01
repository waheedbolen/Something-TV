package com.tvrefresh.home;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dubai weather in full: now, how it feels, the day's light, the next twelve hours and the week. */
public class WeatherActivity extends TileActivity {
    private static final String[] WEEKDAYS = {"", "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Weather.stale(prefs, weather)) {
            io.execute(() -> {
                try {
                    Weather w = Weather.fetch(prefs);
                    handler.post(() -> {
                        if (isDestroyed()) return;
                        weather = w;
                        rebuild();
                    });
                } catch (IOException | JSONException e) {
                    Log.w("Home.Weather", e.toString());
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    void build() {
        Weather w = weather;
        // Now: the one inverse tile.
        FrameLayout now = card(true, 0, 0, 3, 2);
        DotMatrixView icon = new DotMatrixView(this, dp(6.5f));
        icon.setPattern(w == null ? DotMatrixView.CLOUD : w.dots(), false);
        icon.setColors(Palette.ACCENT, 0);
        now.addView(icon, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(20), dp(20), 0, 0));
        TextView city = label(10, Palette.alpha(pal.onInverse, 0.6f));
        city.setText(Weather.CITY);
        now.addView(city, Tiles.at(Gravity.TOP | Gravity.END, WRAP, WRAP, 0, dp(22), dp(20), 0));
        TextView date = label(9, Palette.alpha(pal.onInverse, 0.45f));
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM", Locale.US);
        f.setTimeZone(Weather.ZONE);
        date.setText(f.format(Weather.now().getTime()));
        now.addView(date, Tiles.at(Gravity.TOP | Gravity.END, WRAP, WRAP, 0, dp(38), dp(20), 0));
        TextView temp = text(tiles.light, 64, pal.onInverse);
        temp.setLetterSpacing(-0.05f);
        temp.setText(w == null ? "--°" : getString(R.string.temp, w.temp));
        now.addView(temp, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(16), 0, 0, dp(40)));
        TextView cond = text(tiles.regular, 15, pal.onInverse);
        cond.setText(w == null ? getString(R.string.loading) : w.condition());
        now.addView(cond, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(20), 0, 0, dp(18)));
        TextView range = label(10, Palette.alpha(pal.onInverse, 0.6f));
        range.setText(w == null ? "" : String.format(Locale.US, "H%d · L%d", w.high, w.low));
        now.addView(range, Tiles.at(Gravity.BOTTOM | Gravity.END, WRAP, WRAP, 0, 0, dp(20), dp(20)));

        // How it feels.
        stat(3, R.string.wx_feels, w == null || w.feels < -50 ? "–" : getString(R.string.temp, w.feels), null);
        stat(4, R.string.wx_humidity, w == null || w.humidity < 0 ? "–" : w.humidity + "%", null);
        stat(5, R.string.wx_wind, w == null || w.wind < 0 ? "–" : String.valueOf(Math.round(w.wind)), "KM/H");
        stat(6, R.string.wx_uv, w == null || w.uv < 0 ? "–" : String.valueOf(w.uv), w == null || w.uv < 0 ? null : Weather.uvWord(w.uv));
        stat(7, R.string.wx_rain, w == null || w.rain < 0 ? "–" : w.rain + "%", null);

        daylight(w);
        hours(w);
        week(w);

        TextView credit = label(7.5f, Palette.alpha(pal.ink, 0.35f));
        credit.setText(R.string.weather_credit);
        root.addView(credit, Tiles.at(Gravity.BOTTOM | Gravity.END, WRAP, WRAP, 0, 0, dp(X0), dp(24)));
    }

    private void stat(int col, int name, String value, String unit) {
        FrameLayout t = card(false, col, 0, 1, 1);
        TextView l = label(8, pal.dim());
        l.setText(name);
        t.addView(l, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(14), dp(15), 0, 0));
        // The value and its unit side by side on one baseline, so every tile's number sits at the same height.
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.BOTTOM);
        TextView v = text(tiles.light, 28, pal.ink);
        v.setLetterSpacing(-0.03f);
        v.setText(value);
        line.addView(v, new LinearLayout.LayoutParams(WRAP, WRAP));
        if (unit != null) {
            TextView u = label(7.5f, pal.dim());
            u.setText(unit);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.setMargins(dp(5), 0, 0, dp(5));
            line.addView(u, lp);
        }
        t.addView(line, Tiles.at(Gravity.BOTTOM | Gravity.START, WRAP, WRAP, dp(12), 0, dp(8), dp(12)));
    }

    /** Sunrise to sunset as a line of dots, the sun where it is now. */
    private void daylight(Weather w) {
        FrameLayout t = card(false, 3, 1, 5, 1);
        TextView rise = label(8.5f, pal.dim());
        TextView set = label(8.5f, pal.dim());
        TextView mid = label(8.5f, pal.ink);
        boolean known = w != null && w.sunrise >= 0 && w.sunset > w.sunrise;
        rise.setText(known ? getString(R.string.wx_sunrise, time(w.sunrise)) : "");
        set.setText(known ? getString(R.string.wx_sunset, time(w.sunset)) : "");
        t.addView(rise, Tiles.at(Gravity.TOP | Gravity.START, WRAP, WRAP, dp(18), dp(16), 0, 0));
        t.addView(set, Tiles.at(Gravity.TOP | Gravity.END, WRAP, WRAP, 0, dp(16), dp(18), 0));
        DotBar bar = new DotBar(this, dp(12), 42);
        t.addView(bar, Tiles.at(Gravity.CENTER, WRAP, WRAP, 0, dp(2), 0, 0));
        if (known) {
            Calendar c = Weather.now();
            int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            float p = (now - w.sunrise) / (float) (w.sunset - w.sunrise);
            bar.set(p < 0 || p > 1 ? -1f : p, pal.ink, Palette.alpha(pal.ink, 0.12f));
            int len = w.sunset - w.sunrise;
            mid.setText(getString(R.string.wx_daylight, len / 60, len % 60));
        } else {
            bar.set(-1f, pal.ink, Palette.alpha(pal.ink, 0.12f));
        }
        t.addView(mid, Tiles.at(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, WRAP, WRAP, 0, 0, 0, dp(14)));
    }

    /** The next twelve hours, one column each. */
    private void hours(Weather w) {
        FrameLayout t = card(false, 0, 2, 8, 1);
        LinearLayout line = new LinearLayout(this);
        line.setPadding(dp(8), 0, dp(8), 0);
        int n = w == null ? 0 : Math.min(12, w.hours.length);
        for (int i = 0; i < n; i++) {
            Weather.Hour h = w.hours[i];
            String when = i == 0 ? getString(R.string.wx_now) : (h.hour % 12 == 0 ? 12 : h.hour % 12) + (h.hour < 12 ? "AM" : "PM");
            line.addView(column(when, Weather.dots(h.code, h.day), getString(R.string.temp, h.temp), null, i == 0),
                    new LinearLayout.LayoutParams(0, MATCH, 1f));
        }
        t.addView(line, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    /** The week: today and six more days. */
    private void week(Weather w) {
        FrameLayout t = card(false, 0, 3, 8, 1);
        LinearLayout line = new LinearLayout(this);
        line.setPadding(dp(8), 0, dp(8), 0);
        int n = w == null ? 0 : w.days.length;
        for (int i = 0; i < n; i++) {
            Weather.Day d = w.days[i];
            line.addView(column(i == 0 ? getString(R.string.wx_today) : WEEKDAYS[d.weekday], Weather.dots(d.code, true),
                    getString(R.string.temp, d.high), getString(R.string.temp, d.low), i == 0), new LinearLayout.LayoutParams(0, MATCH, 1f));
        }
        t.addView(line, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private LinearLayout column(String top, String[] icon, String value, String second, boolean first) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, dp(15), 0, 0);
        TextView l = label(8, first ? pal.ink : pal.dim());
        l.setText(top);
        c.addView(l);
        DotMatrixView d = new DotMatrixView(this, dp(2.6f));
        d.setPattern(icon, false);
        d.setColors(first ? Palette.ACCENT : pal.ink, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.topMargin = dp(9);
        c.addView(d, lp);
        LinearLayout temps = new LinearLayout(this);
        TextView v = text(tiles.medium, 13, pal.ink);
        v.setText(value);
        temps.addView(v);
        if (second != null) {
            TextView s = text(tiles.regular, 13, pal.dim());
            s.setText(second);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(WRAP, WRAP);
            slp.leftMargin = dp(6);
            temps.addView(s, slp);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(WRAP, WRAP);
        tlp.topMargin = dp(9);
        c.addView(temps, tlp);
        return c;
    }
}
