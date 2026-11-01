package com.tvrefresh.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A line of dots: those before the marker lit, the marker in the accent colour, the rest faint. It fills in. */
final class DotBar extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float pitch;
    private final int count;
    private float shown;
    private int lit, unlit;

    DotBar(Context ctx, float pitchPx, int count) {
        super(ctx);
        pitch = pitchPx;
        this.count = count;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** progress 0 to 1, or below 0 for no marker at all. */
    void set(float progress, int litColor, int unlitColor) {
        lit = litColor;
        unlit = unlitColor;
        ValueAnimator a = ValueAnimator.ofFloat(0f, progress);
        a.setDuration(1100);
        a.setStartDelay(250);
        a.setInterpolator(Tiles.EASE);
        a.addUpdateListener(x -> {
            shown = (float) x.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(count * pitch), Math.round(pitch * 1.4f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int marker = shown < 0 ? -1 : Math.round(shown * (count - 1));
        float r = pitch * 0.32f, y = getHeight() / 2f;
        for (int i = 0; i < count; i++) {
            paint.setColor(i == marker ? Palette.ACCENT : i < marker ? lit : unlit);
            canvas.drawCircle(i * pitch + pitch / 2, y, i == marker ? r * 1.6f : r, paint);
        }
    }
}
