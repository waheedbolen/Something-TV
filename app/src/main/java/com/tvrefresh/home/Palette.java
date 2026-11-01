package com.tvrefresh.home;

import android.animation.ArgbEvaluator;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * The two looks, light by day and dark by night, and one accent that stays the same in both. A palette can be
 * any point between the two, so a change of look fades rather than flips.
 */
final class Palette {
    static final int ACCENT = 0xFFEB4A2B;

    private static final Palette DAY = new Palette(0xFFE8E8E5, 0xFFFFFFFF, 0xFF141414, 0xFF141414, 0xFFF4F4F2, 0xFFEFEFEC);
    private static final Palette NIGHT = new Palette(0xFF000000, 0xFF161618, 0xFFEFEFEC, 0xFFF2F2F0, 0xFF121212, 0xFF26262A);
    private static final ArgbEvaluator MIX = new ArgbEvaluator();

    final int bg;         // behind the tiles
    final int surface;    // most tiles
    final int inverse;    // the few tiles in the opposite colour: the clock, Search
    final int ink;        // text and icons on a surface
    final int onInverse;  // text and icons on an inverse tile
    final int plate;      // the circle behind an app's glyph, one step off the surface

    private Palette(int bg, int surface, int inverse, int ink, int onInverse, int plate) {
        this.bg = bg;
        this.surface = surface;
        this.inverse = inverse;
        this.ink = ink;
        this.onInverse = onInverse;
        this.plate = plate;
    }

    /** 0 is day, 1 is night. */
    static Palette at(float night) {
        if (night <= 0f) return DAY;
        if (night >= 1f) return NIGHT;
        return new Palette(mix(DAY.bg, NIGHT.bg, night), mix(DAY.surface, NIGHT.surface, night),
                mix(DAY.inverse, NIGHT.inverse, night), mix(DAY.ink, NIGHT.ink, night),
                mix(DAY.onInverse, NIGHT.onInverse, night), mix(DAY.plate, NIGHT.plate, night));
    }

    int dim() {
        return alpha(ink, 0.5f);
    }

    int rule() {
        return alpha(ink, 0.1f);
    }

    static int alpha(int color, float a) {
        return (Math.round(a * (color >>> 24)) << 24) | (color & 0xFFFFFF);
    }

    private static int mix(int a, int b, float t) {
        return (Integer) MIX.evaluate(t, a, b);
    }

    /**
     * Night between sunset and sunrise in Dubai (from the weather, which carries both), or 18:00 to 06:00 before
     * the first forecast. prefs "theme" set to "day" or "night" holds one look; "auto" or nothing follows the sun.
     */
    static boolean isNight(SharedPreferences prefs, Weather w) {
        String forced = prefs.getString("theme", "auto");
        if (forced.equals("day")) return false;
        if (forced.equals("night")) return true;
        return sunDown(w);
    }

    /** Whether the sun is down in Dubai now, whatever look is chosen. */
    static boolean sunDown(Weather w) {
        Calendar c = Weather.now();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int rise = w != null && w.sunrise >= 0 ? w.sunrise : 6 * 60;
        int set = w != null && w.sunset >= 0 ? w.sunset : 18 * 60;
        return now < rise || now >= set;
    }
}
