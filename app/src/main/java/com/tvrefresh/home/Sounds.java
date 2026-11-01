package com.tvrefresh.home;

import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

/**
 * Home's own two sounds in place of the system's: a soft tick when focus moves, a clean tap on OK. Both play on
 * the system-sounds volume and stay silent when system sounds are off in Settings.
 */
final class Sounds {
    private static Sounds instance;

    private final Context ctx;
    private final SoundPool pool;
    private final int tick, tap;

    static synchronized Sounds get(Context ctx) {
        if (instance == null) instance = new Sounds(ctx.getApplicationContext());
        return instance;
    }

    private Sounds(Context ctx) {
        this.ctx = ctx;
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        tick = pool.load(ctx, R.raw.focus_tick, 1);
        tap = pool.load(ctx, R.raw.tap, 1);
    }

    void move() {
        play(tick, 0.55f);
    }

    void tap() {
        play(tap, 0.8f);
    }

    private void play(int id, float volume) {
        if (Settings.System.getInt(ctx.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0) return;
        pool.play(id, volume, volume, 1, 0, 1f);
    }

    /**
     * For an activity's dispatchKeyEvent: arrow keys go to the views first (a row scrolls itself, smoothly), and
     * if none takes the key, focus moves here instead of in the window, which would play the system's sound.
     * Returns null when the key is not an arrow press and the activity should handle it as usual.
     */
    static Boolean navigate(Activity a, KeyEvent e) {
        int dir;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = View.FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = View.FOCUS_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: dir = View.FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = View.FOCUS_DOWN; break;
            default: return null;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return null;
        a.onUserInteraction();
        View before = a.getCurrentFocus();
        if (a.getWindow().superDispatchKeyEvent(e)) {
            if (a.getCurrentFocus() != before) get(a).move();
            return true;
        }
        View next = before == null ? null : before.focusSearch(dir);
        if (next != null && next != before && next.requestFocus(dir)) get(a).move();
        return true;   // at an edge nothing moves, and nothing plays
    }
}
