package com.tvrefresh.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The wallpaper: a different free photo (Lorem Picsum, which serves Unsplash photos) every time Home comes back.
 * The next picture is always downloaded in advance, so a change is instant and works offline with the last one.
 */
final class Backdrop {
    /** Called on the main thread whenever a photo goes up, with who took it. */
    interface Listener {
        void onPhoto(Bitmap photo, String author);
    }

    private static final String TAG = "Home.Backdrop";
    private static final String LIST_URL = "https://picsum.photos/v2/list?limit=100&page=";
    private static final int MIN_WIDTH = 3840;            // only photos whose original is 4K or larger
    private static final double MIN_RATIO = 1.45;         // landscape, close to 16:9 or wider
    private static final long POOL_MAX_AGE = 7L * 24 * 60 * 60 * 1000;
    private static final int RECENT = 40;                 // never repeat one of the last 40
    private static final long RETRY_MS = 30_000;
    private static final int MAX_RETRIES = 10;

    private final ImageView bottom;
    private final ImageView top;
    private final SharedPreferences prefs;
    private final ExecutorService io;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Random random = new Random();
    private final File dir;
    private final int width;
    private final int height;
    private int retries;
    private final Transform transform;

    /** Applied off the main thread before a photo goes up, e.g. to frost it into a background. */
    interface Transform {
        Bitmap apply(Bitmap photo);
    }

    Backdrop(Context ctx, ImageView bottom, ImageView top, ExecutorService io, Listener listener) {
        this(ctx, bottom, top, io, listener, null);
    }

    Backdrop(Context ctx, ImageView bottom, ImageView top, ExecutorService io, Listener listener, Transform transform) {
        this.bottom = bottom;
        this.top = top;
        this.io = io;
        this.listener = listener;
        this.transform = transform;
        this.prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
        this.dir = folder(ctx);
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        // Pictures are fetched at the size Android draws the screen (1920 x 1080 here): sharper is not possible.
        this.width = Math.max(m.widthPixels, m.heightPixels);
        this.height = Math.min(m.widthPixels, m.heightPixels);
    }

    static File folder(Context ctx) {
        File d = new File(ctx.getFilesDir(), "wallpaper");
        d.mkdirs();
        return d;
    }

    /** The picture on screen now, for the screensaver. */
    static File current(Context ctx) {
        return new File(folder(ctx), "current.jpg");
    }

    /** Puts last time's picture up straight away, with no network. */
    void showCurrent() {
        io.execute(() -> {
            Bitmap b = shown(decode(file("current.jpg"), width, height));
            String author = read(file("current.txt"));
            if (b != null) ui.post(() -> {
                bottom.setImageBitmap(b);
                listener.onPhoto(b, author);
            });
        });
    }

    /** The next picture: the one fetched in advance (or a fresh one the first time), then fetch the one after. */
    void advance() {
        if (!busy.compareAndSet(false, true)) return;
        io.execute(() -> {
            boolean shown = false;
            try {
                File next = file("next.jpg");
                if (next.exists() || fetch(next, file("next.txt"))) {
                    Bitmap b = shown(decode(next, width, height));
                    String author = read(file("next.txt"));
                    if (b == null) {
                        next.delete();
                    } else if (next.renameTo(file("current.jpg"))) {
                        file("next.txt").renameTo(file("current.txt"));
                        shown = true;
                        ui.post(() -> crossfade(b, author));
                        fetch(file("next.jpg"), file("next.txt"));
                    }
                }
            } finally {
                busy.set(false);
            }
            if (!shown) ui.post(this::retryIfBlank);
        });
    }

    /** Right after power-on Wi-Fi may not be up yet: keep trying while the screen is still black. */
    private void retryIfBlank() {
        if (bottom.getDrawable() == null && retries++ < MAX_RETRIES) ui.postDelayed(this::advance, RETRY_MS);
    }

    private Bitmap shown(Bitmap decoded) {
        if (decoded == null || transform == null) return decoded;
        Bitmap t = transform.apply(decoded);
        if (t != decoded) decoded.recycle();
        return t;
    }

    void stop() {
        ui.removeCallbacksAndMessages(null);
    }

    private void crossfade(Bitmap b, String author) {
        retries = 0;
        top.animate().cancel();
        top.setImageBitmap(b);
        top.setAlpha(0f);
        top.animate().alpha(1f).setDuration(1200).withEndAction(() -> {
            bottom.setImageBitmap(b);
            top.setAlpha(0f);
            top.setImageDrawable(null);
        }).start();
        listener.onPhoto(b, author);
    }

    private boolean fetch(File image, File credit) {
        for (int attempt = 0; attempt < 3; attempt++) {
            JSONObject photo = pick();
            if (photo == null) return false;
            String id = photo.optString("id");
            String url = "https://picsum.photos/id/" + id + "/" + width + "/" + height + ".jpg";
            try {
                Net.download(url, image);
                write(credit, photo.optString("author"));
                return true;
            } catch (IOException e) {
                Log.w(TAG, "photo " + id + ": " + e.getMessage());
                image.delete();
            }
        }
        return false;
    }

    private JSONObject pick() {
        JSONArray pool = pool();
        if (pool == null || pool.length() == 0) return null;
        List<String> recent = recent();
        JSONObject choice = null;
        for (int i = 0; i < 25 && choice == null; i++) {
            JSONObject p = pool.optJSONObject(random.nextInt(pool.length()));
            if (p != null && !recent.contains(p.optString("id"))) choice = p;
        }
        if (choice == null) choice = pool.optJSONObject(random.nextInt(pool.length()));
        if (choice == null) return null;
        recent.add(choice.optString("id"));
        while (recent.size() > RECENT) recent.remove(0);
        prefs.edit().putString("recent", new JSONArray(recent).toString()).apply();
        return choice;
    }

    /** Every 4K landscape photo in the library, refreshed once a week. */
    private JSONArray pool() {
        String cached = prefs.getString("pool", null);
        long age = System.currentTimeMillis() - prefs.getLong("pool_time", 0);
        if (cached != null && age < POOL_MAX_AGE) {
            try {
                return new JSONArray(cached);
            } catch (JSONException ignored) {
                // rebuilt below
            }
        }
        JSONArray pool = new JSONArray();
        try {
            for (int page = 1; page <= 30; page++) {
                JSONArray list = new JSONArray(Net.text(LIST_URL + page));
                if (list.length() == 0) break;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject p = list.getJSONObject(i);
                    int w = p.optInt("width");
                    int h = p.optInt("height");
                    if (w >= MIN_WIDTH && h > 0 && (double) w / h >= MIN_RATIO) {
                        pool.put(new JSONObject().put("id", p.optString("id")).put("author", p.optString("author")));
                    }
                }
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "photo list: " + e.getMessage());
        }
        if (pool.length() > 0) {
            prefs.edit().putString("pool", pool.toString()).putLong("pool_time", System.currentTimeMillis()).apply();
            return pool;
        }
        try {
            return cached == null ? null : new JSONArray(cached);
        } catch (JSONException e) {
            return null;
        }
    }

    private List<String> recent() {
        List<String> ids = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) ids.add(a.optString(i));
        } catch (JSONException ignored) {
            // start over
        }
        return ids;
    }

    private File file(String name) {
        return new File(dir, name);
    }

    /** Decodes no larger than the screen, so a picture never costs more than about 8 MB. */
    static Bitmap decode(File f, int w, int h) {
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(File f, String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
