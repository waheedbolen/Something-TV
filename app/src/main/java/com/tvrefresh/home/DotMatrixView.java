package com.tvrefresh.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Digits and small pictures drawn as a grid of dots, lit and unlit. When what it shows changes, the dots that
 * change light up or go out in a wave from the top row down; the rest stay as they are.
 */
final class DotMatrixView extends View {
    static final String[] SUN = {
            "....#....", ".#.....#.", "...###...", "..#####..", "#.#####.#", "..#####..", "...###...", ".#.....#.", "....#...."};
    static final String[] MOON = {
            "...###...", ".####....", ".###.....", "###......", "###......", "###......", ".###.....", ".####....", "...###..."};
    static final String[] CLOUD = {
            ".........", ".........", "...###...", "..#####..", ".######..", "#########", "#########", ".#######.", "........."};
    static final String[] SEARCH = {
            "..###....", ".#...#...", "#.....#..", "#.....#..", "#.....#..", ".#...#...", "..###.#..", ".......#.", "........#"};
    static final String[] SETTINGS = {
            "....#....", ".#.###.#.", "..#...#..", ".#.....#.", "##..#..##", ".#.....#.", "..#...#..", ".#.###.#.", "....#...."};
    static final String[] INPUTS = {
            "....#####", "........#", "....#...#", ".....#..#", "#######.#", ".....#..#", "....#...#", "........#", "....#####"};
    static final String[] PLAY = {
            ".#.......", ".###.....", ".#####...", ".#######.", ".########", ".#######.", ".#####...", ".###.....", ".#......."};
    static final String[] PLUS = {
            ".........", "....#....", "....#....", "....#....", ".#######.", "....#....", "....#....", "....#....", "........."};
    static final String[] CHECK = {
            ".........", "........#", ".......#.", "......#..", "#....#...", ".#..#....", "..##.....", ".........", "........."};
    static final String[] MIC = {
            "...###...", "...###...", "...###...", "...###...", ".#.###.#.", ".#.....#.", "..#####..", "....#....", "..#####.."};
    static final String[] HDMI = {
            ".........", ".........", "#########", "#.#.#.#.#", "#.......#", ".#.....#.", "..#####..", ".........", "........."};
    static final String[] SCREEN = {
            "#########", "#.......#", "#.......#", "#.......#", "#.......#", "#########", ".........", "...###...", "........."};
    static final String[] BOX = {
            ".........", ".........", ".........", ".#######.", "#.......#", "#...#...#", ".#######.", ".........", "........."};
    /** Every icon is 9 x 9 dots at this pitch (dp), so they all read as one set. */
    static final float ICON_PITCH = 3.2f;
    static final String[] BROWSE = {
            "##.##.##.", "##.##.##.", "##.##.##.", "##.##.##.", ".........", "##.##.##.", "##.##.##.", "##.##.##.", "##.##.##."};
    static final String[] AUTO = {
            "...###...", ".#..####.", ".#..####.", "#...#####", "#...#####", "#...#####", ".#..####.", ".#..####.", "...###..."};
    static final String[] RAIN = {
            "...###...", "..#####..", ".#######.", "#########", ".#######.", ".........", ".#..#..#.", "#..#..#..", "........."};

    /** 5 x 7, the same glyphs the design mockups use. */
    private static final Map<Character, String[]> GLYPHS = new HashMap<>();
    static {
        GLYPHS.put('0', new String[] {".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."});
        GLYPHS.put('1', new String[] {"..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."});
        GLYPHS.put('2', new String[] {".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"});
        GLYPHS.put('3', new String[] {".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."});
        GLYPHS.put('4', new String[] {"...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."});
        GLYPHS.put('5', new String[] {"#####", "#....", "####.", "....#", "....#", "#...#", ".###."});
        GLYPHS.put('6', new String[] {"..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."});
        GLYPHS.put('7', new String[] {"#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."});
        GLYPHS.put('8', new String[] {".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."});
        GLYPHS.put('9', new String[] {".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."});
    }
    private static final String GLYPH_GAP = "..";    // empty columns between two digits

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float pitch;
    private int on = 0xFFFFFFFF, off = 0;
    private boolean[][] cells = new boolean[0][0];
    private boolean[][] before;
    private float progress = 1f;
    private ValueAnimator wave;

    DotMatrixView(Context ctx, float pitchPx) {
        super(ctx);
        pitch = pitchPx;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Drawn once into a GPU layer and reused every frame, instead of a hundred circles a frame; the layer is
        // only redrawn while the dots change.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void setColors(int lit, int unlit) {
        if (lit == on && unlit == off) return;
        on = lit;
        off = unlit;
        invalidate();
    }

    void setDigits(String digits, boolean animate) {
        String[] rows = new String[7];
        Arrays.fill(rows, "");
        for (int i = 0; i < digits.length(); i++) {
            String[] g = GLYPHS.get(digits.charAt(i));
            if (g == null) continue;
            for (int y = 0; y < 7; y++) rows[y] += (rows[y].isEmpty() ? "" : GLYPH_GAP) + g[y];
        }
        setPattern(rows, animate);
    }

    void setPattern(String[] rows, boolean animate) {
        boolean[][] next = new boolean[rows.length][];
        for (int y = 0; y < rows.length; y++) {
            next[y] = new boolean[rows[y].length()];
            for (int x = 0; x < rows[y].length(); x++) next[y][x] = rows[y].charAt(x) == '#';
        }
        if (Arrays.deepEquals(next, cells)) return;
        boolean sameShape = next.length == cells.length && (next.length == 0 || next[0].length == cells[0].length);
        before = animate && sameShape ? cells : null;
        cells = next;
        if (!sameShape) requestLayout();
        if (wave != null) wave.cancel();
        if (before == null) {
            progress = 1f;
            invalidate();
            return;
        }
        wave = ValueAnimator.ofFloat(0f, 1f);
        wave.setDuration(760);
        wave.setInterpolator(new LinearInterpolator());
        wave.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        wave.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int cols = cells.length == 0 ? 0 : cells[0].length;
        setMeasuredDimension(Math.round(cols * pitch), Math.round(cells.length * pitch));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = pitch * 0.38f;
        int rows = cells.length;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cells[y].length; x++) {
                boolean now = cells[y][x];
                boolean was = before != null && progress < 1f ? before[y][x] : now;
                int color;
                float radius = r;
                if (was == now) {
                    color = now ? on : off;
                } else {
                    // Each row starts a little after the one above it.
                    float start = 0.45f * y / Math.max(1, rows - 1);
                    float t = Math.max(0f, Math.min(1f, (progress - start) / 0.55f));
                    t = 1f - (1f - t) * (1f - t);
                    color = blend(was ? on : off, now ? on : off, t);
                    radius = now ? r * (0.35f + 0.65f * t) : r * (1f - 0.35f * (float) Math.sin(Math.PI * t));
                }
                if ((color >>> 24) == 0) continue;
                paint.setColor(color);
                canvas.drawCircle(x * pitch + pitch / 2, y * pitch + pitch / 2, radius, paint);
            }
        }
    }

    private static int blend(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
