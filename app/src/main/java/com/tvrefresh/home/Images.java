package com.tvrefresh.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posters, backdrops and logos: fetched once to disk, decoded no larger than the card that shows them (RGB 565,
 * half the memory of full colour), and kept in a 24 MB memory cache. Enough for every row on a 2 GB TV.
 */
final class Images {
    private static final String TAG = "Home.Images";
    private static final long DISK_MAX_AGE = 30L * 24 * 60 * 60 * 1000;
    private static Images instance;

    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final File dir;

    static synchronized Images get(Context ctx) {
        if (instance == null) instance = new Images(ctx.getApplicationContext());
        return instance;
    }

    private Images(Context ctx) {
        dir = new File(ctx.getCacheDir(), "img");
        dir.mkdirs();
        pool.execute(() -> {
            File[] old = dir.listFiles();
            long now = System.currentTimeMillis();
            if (old != null) for (File f : old) if (now - f.lastModified() > DISK_MAX_AGE) f.delete();
        });
    }

    /** Lets go of the pictures not on screen, for when another app needs the memory. */
    void trim() {
        memory.evictAll();
    }

    /** Shows url in view at about w x h pixels, fading in; a later call for the same view wins. */
    void load(String url, ImageView view, int w, int h) {
        view.setTag(R.id.image_url, url);
        if (url == null) {
            view.setImageDrawable(null);
            return;
        }
        String key = url + "@" + w + "x" + h;
        Bitmap hit = memory.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            view.setAlpha(1f);
            return;
        }
        view.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap b = fetch(url, w, h);
            if (b == null) return;
            memory.put(key, b);
            ui.post(() -> {
                if (!url.equals(view.getTag(R.id.image_url))) return;   // the view shows something else now
                view.setImageBitmap(b);
                view.setAlpha(0f);
                view.animate().alpha(1f).setDuration(260).start();
            });
        });
    }

    /** Synchronous, for callers already off the main thread. */
    Bitmap fetch(String url, int w, int h) {
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.length());
        // TMDB's image CDN hands out several servers and some can be dead for a while (seen: one address that
        // refused every connection); a fresh lookup a moment later usually gets a working one.
        for (int attempt = 1; !f.exists(); attempt++) {
            try {
                Net.download(url, f);
            } catch (IOException e) {
                Log.w(TAG, url + " (attempt " + attempt + "): " + e.getMessage());
                if (attempt >= 3) return null;
                try {
                    Thread.sleep(1500L * attempt);
                } catch (InterruptedException ie) {
                    return null;
                }
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) {
            f.delete();
            return null;
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = url.endsWith(".png") ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        // Power-of-two sampling can leave it up to twice too big: trim to the card.
        float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        if (scale < 0.75f) {
            Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }
}
