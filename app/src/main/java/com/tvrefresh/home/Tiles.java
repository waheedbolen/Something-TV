package com.tvrefresh.home;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The one building block of Home and the detail card: a rounded tile or a circle. Focus lifts it slightly and draws
 * a thin ring in the accent colour, with no shadow; a press dips it. Everything moves on the same ease-out curve.
 */
final class Tiles {
    static final Interpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final int RADIUS = 24;           // dp, every rounded tile
    static final float LIFT = 1.025f;       // a focused tile: a lift you notice, not a zoom
    static final float LIFT_ROUND = 1.04f;  // a focused circle: smaller, so it grows a little more
    static final float RING = 1.5f;         // dp, the focus ring

    private final Context ctx;
    private final float density;
    final Typeface mono, light, regular, medium;

    Tiles(Context ctx) {
        this.ctx = ctx;
        density = ctx.getResources().getDisplayMetrics().density;
        mono = ctx.getResources().getFont(R.font.space_mono);
        light = ctx.getResources().getFont(R.font.inter_light);
        regular = ctx.getResources().getFont(R.font.inter_regular);
        medium = ctx.getResources().getFont(R.font.inter_medium);
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    /** A tile whose fill the caller sets (see fill()); focusable tiles get the ring, the lift and the dip. */
    FrameLayout tile(boolean circle, boolean focusable) {
        return tile(circle ? -1 : RADIUS, focusable);
    }

    /** A rounded tile with its own corner radius (posters take a smaller one), or a circle when radius < 0. */
    FrameLayout tile(float radius, boolean focusable) {
        boolean circle = radius < 0;
        FrameLayout t = new FrameLayout(ctx);
        t.setId(View.generateViewId());
        t.setBackground(shape(radius));
        // No clipping by default: a rounded clip stops the GPU batching what is inside. Tiles with a photo, which
        // would show square corners, call clip().
        t.setSoundEffectsEnabled(false);    // Sounds plays Home's own tap instead
        if (focusable) {
            GradientDrawable ring = shape(radius);
            ring.setColor(0);
            ring.setStroke(dp(RING), Palette.ACCENT);
            ring.setAlpha(0);
            t.setTag(R.id.ring, ring);      // drawn only while focused or fading; see focus()
            t.setFocusable(true);
            t.setClickable(true);
            t.setTag(R.id.card, Boolean.TRUE);
            float lift = circle ? LIFT_ROUND : LIFT;
            t.setTag(R.id.lift, lift);
            t.setOnFocusChangeListener((v, f) -> focus(v, f));
        }
        return t;
    }

    private GradientDrawable shape(float radius) {
        GradientDrawable d = new GradientDrawable();
        if (radius < 0) d.setShape(GradientDrawable.OVAL);
        else d.setCornerRadius(dp(radius));
        return d;
    }

    /** Rounds off the corners of whatever the tile holds: for tiles with photos. */
    static void clip(View tile) {
        tile.setClipToOutline(true);
    }

    static void fill(View tile, int color) {
        ((GradientDrawable) tile.getBackground()).setColor(color);
    }

    /** The focus ring's colour; a tile filled with the accent needs another one to show it. */
    void ring(View tile, int color) {
        ((GradientDrawable) tile.getTag(R.id.ring)).setStroke(dp(RING), color);
    }

    static float lift(View v) {
        Object l = v.getTag(R.id.lift);
        return l instanceof Float ? (Float) l : 1f;
    }

    /** Lift and ring in, or settle and ring out. Callers that do more on focus call this first. */
    void focus(View v, boolean focused) {
        float s = focused ? lift(v) : 1f;
        v.animate().cancel();
        // alpha and translationY too: focus can arrive while enter() is still bringing the tile in.
        // The ring alone marks focus: no shadow, which would only show in the light look anyway.
        v.animate().scaleX(s).scaleY(s).alpha(1f).translationY(0).translationZ(0)
                .setStartDelay(0).setDuration(focused ? 260 : 200).setInterpolator(EASE).start();
        Drawable ring = (Drawable) v.getTag(R.id.ring);
        if (ring != null) {
            if (focused) v.setForeground(ring);
            ObjectAnimator fade = ObjectAnimator.ofInt(ring, "alpha", ring.getAlpha(), focused ? 255 : 0).setDuration(focused ? 200 : 140);
            if (!focused) fade.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    if (!v.isFocused()) v.setForeground(null);      // an invisible ring still costs a draw
                }
            });
            fade.start();
        }
    }

    /** The tap sound and a small dip under the finger, then back to the lifted size. */
    static void press(View v) {
        Sounds.get(v.getContext()).tap();
        float s = v.isFocused() ? lift(v) : 1f;
        v.animate().cancel();
        v.animate().scaleX(s * 0.975f).scaleY(s * 0.975f).alpha(1f).translationY(0).setStartDelay(0).setDuration(80).setInterpolator(EASE)
                .withEndAction(() -> v.animate().scaleX(s).scaleY(s).setDuration(220).setInterpolator(EASE).start())
                .start();
    }

    /** Tiles come in one after another, nearest the top left first: a short rise, a fade, a little growth. */
    void enter(List<View> views) {
        views.sort((a, b) -> Float.compare(a.getX() + a.getY() * 1.4f, b.getX() + b.getY() * 1.4f));
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            float s = v.isFocused() ? lift(v) : 1f;
            v.animate().cancel();
            v.setAlpha(0f);
            v.setScaleX(s * 0.9f);
            v.setScaleY(s * 0.9f);
            v.setTranslationY(dp(18));
            v.animate().alpha(1f).scaleX(s).scaleY(s).translationY(0).setStartDelay(i * 28L)
                    .setDuration(620).setInterpolator(EASE).withLayer()
                    .withEndAction(() -> v.animate().setStartDelay(0)).start();
        }
    }

    TextView text(Typeface face, float sp) {
        TextView t = new TextView(ctx);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    /** Small capitals in the monospace face: labels, dates, chips. */
    TextView label(float sp) {
        TextView t = text(mono, sp);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        return t;
    }

    /** A white pill with black monospace type, the same in both looks: it sits on photos. */
    TextView chip(String s) {
        TextView c = label(8.5f);
        c.setText(s);
        c.setTextColor(0xFF111111);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0xFFFFFFFF);
        c.setBackground(bg);
        c.setPadding(dp(9), dp(5), dp(9), dp(4));
        c.setGravity(Gravity.CENTER);
        return c;
    }

    static FrameLayout.LayoutParams at(int gravity, int w, int h, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.setMargins(left, top, right, bottom);
        return lp;
    }
}
