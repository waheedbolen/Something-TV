package com.tvrefresh.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Movie Database (themoviedb.org): what is popular on each streaming platform in the UAE, with posters,
 * backdrops and details. Platform availability comes from TMDB's JustWatch data. Every answer is cached on disk,
 * so Home shows the last lists at once and still has them offline.
 */
final class Tmdb {
    static final String REGION = "AE";
    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMG = "https://image.tmdb.org/t/p/";
    private static final long LISTS_MAX_AGE = 6L * 60 * 60 * 1000;
    private static final long PROVIDERS_MAX_AGE = 7L * 24 * 60 * 60 * 1000;
    private static final int ROW_SIZE = 16;

    /** A streaming service, with the app that plays it on this TV and a web search link it may answer. */
    static final class Provider {
        final int id;
        final String label;
        final String logo;
        final String[] packages;
        final String search;

        Provider(int id, String label, String logo, String[] packages, String search) {
            this.id = id;
            this.label = label;
            this.logo = logo;
            this.packages = packages;
            this.search = search;
        }

        String logoUrl() {
            return logo == null ? null : IMG + "w92" + logo;
        }

        /** The installed, enabled app for this service, or null. */
        String installedPackage(PackageManager pm) {
            for (String p : packages) {
                if (pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null) return p;
            }
            return null;
        }

        /** Opens the title in the app: its search for the name where the app answers that link, else the app. */
        Intent openIntent(PackageManager pm, String titleName) {
            String pkg = installedPackage(pm);
            if (pkg == null) return null;
            if (search != null) {
                try {
                    Uri uri = Uri.parse(String.format(search, URLEncoder.encode(titleName, "UTF-8")));
                    Intent i = new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg);
                    if (pm.resolveActivity(i, 0) != null) return i;
                } catch (java.io.UnsupportedEncodingException ignored) {
                    // UTF-8 is always there
                }
            }
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            return i != null ? i : pm.getLaunchIntentForPackage(pkg);
        }
    }

    /**
     * The major services in the UAE, in row order, matched by name against TMDB's list for the region (Oct 2026:
     * Apple's service is listed as plain "Apple TV", and Disney+ is not listed: its shows stream on OSN+ here).
     * "Apple TV Store" (rentals) never matches, thanks to the $.
     */
    private static final Object[][] MAJOR = {
            {"^netflix$", "Netflix", new String[] {"com.netflix.ninja"}, "https://www.netflix.com/search?q=%s"},
            {"^apple tv( plus|\\+)?$", "Apple TV", new String[] {"com.apple.atve.androidtv.appletv"}, "https://tv.apple.com/search?term=%s"},
            {"^amazon prime video$", "Prime Video", new String[] {"com.amazon.amazonvideo.livingroom"}, "https://www.primevideo.com/search/?phrase=%s"},
            {"^disney( plus|\\+)$", "Disney+", new String[] {"com.disneyplus.mea", "com.disney.disneyplus"}, null},
            {"^osn", "OSN+", new String[] {"com.osn.go"}, null},
            {"^shahid", "Shahid", new String[] {"net.mbc.shahidTV", "net.mbc.shahid"}, null},
            {"^starz ?play", "STARZPLAY", new String[] {"com.parsifal.starz"}, null},
            {"^tod$", "TOD", new String[0], null},
    };

    private final String key;
    private final File cache;

    Tmdb(Context ctx) {
        key = BuildConfig.TMDB_KEY.trim();
        cache = new File(ctx.getCacheDir(), "tmdb");
        cache.mkdirs();
    }

    boolean enabled() {
        return !key.isEmpty();
    }

    static String image(String size, String path) {
        return path == null ? null : IMG + size + path;
    }

    // ---- Lists --------------------------------------------------------------------------------------------

    /** The major UAE services TMDB knows, in MAJOR's order. */
    List<Provider> providers() throws IOException, JSONException {
        JSONArray all = get("/watch/providers/movie", "watch_region=" + REGION, PROVIDERS_MAX_AGE).getJSONArray("results");
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) sorted.add(all.getJSONObject(i));
        sorted.sort((a, b) -> priority(a) - priority(b));
        List<Provider> out = new ArrayList<>();
        for (Object[] m : MAJOR) {
            Pattern p = Pattern.compile((String) m[0], Pattern.CASE_INSENSITIVE);
            for (JSONObject o : sorted) {
                String name = o.optString("provider_name");
                if (!p.matcher(name.trim()).find() || name.toLowerCase(Locale.US).contains("with ads")
                        || name.toLowerCase(Locale.US).contains("channel")) continue;
                out.add(new Provider(o.optInt("provider_id"), (String) m[1], Title.str(o, "logo_path"), (String[]) m[2], (String) m[3]));
                break;
            }
        }
        return out;
    }

    private static int priority(JSONObject o) {
        JSONObject d = o.optJSONObject("display_priorities");
        return d != null ? d.optInt(REGION, o.optInt("display_priority", 999)) : o.optInt("display_priority", 999);
    }

    List<Title> trending(String type) throws IOException, JSONException {
        JSONArray r = get("/trending/" + type + "/week", "", LISTS_MAX_AGE).getJSONArray("results");
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < r.length() && out.size() < ROW_SIZE; i++) {
            JSONObject o = r.getJSONObject(i);
            String mt = o.optString("media_type", type);
            if (!mt.equals("movie") && !mt.equals("tv")) continue;
            Title t = Title.from(o, mt, null);
            if (t.backdrop != null) out.add(t);
        }
        return out;
    }

    /** What is popular on one service in the UAE right now, included in the subscription (no rentals). */
    List<Title> popularOn(Provider p, String type) throws IOException, JSONException {
        // Recent only: popularity alone fills every service's row with the same decades-old catalogue series.
        boolean movie = type.equals("movie");
        String since = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new java.util.Date(System.currentTimeMillis() - (movie ? 730L : 365L) * 24 * 60 * 60 * 1000));
        String q = "watch_region=" + REGION + "&with_watch_providers=" + p.id
                + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false&page=1"
                + (movie ? "&primary_release_date.gte=" : "&air_date.gte=") + since;
        JSONArray r = get("/discover/" + type, q, LISTS_MAX_AGE).getJSONArray("results");
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < r.length() && out.size() < ROW_SIZE; i++) {
            Title t = Title.from(r.getJSONObject(i), type, p);
            if (t.poster != null) out.add(t);
        }
        return out;
    }

    /** Films and series for one service, alternating, most popular first. */
    List<Title> popularOn(Provider p) throws IOException, JSONException {
        List<Title> movies = popularOn(p, "movie");
        List<Title> shows = popularOn(p, "tv");
        List<Title> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; out.size() < ROW_SIZE && (i < movies.size() || i < shows.size()); i++) {
            if (i < shows.size() && seen.add("tv" + shows.get(i).id)) out.add(shows.get(i));
            if (i < movies.size() && seen.add("movie" + movies.get(i).id)) out.add(movies.get(i));
        }
        return out.size() > ROW_SIZE ? out.subList(0, ROW_SIZE) : out;
    }

    /**
     * New in the last 60 days and included with one of the given services in the UAE, most popular first; films and
     * series alternating when type is "all".
     */
    List<Title> fresh(List<Provider> on, String type) throws IOException, JSONException {
        if (type.equals("all")) {
            List<Title> movies = fresh(on, "movie"), shows = fresh(on, "tv"), out = new ArrayList<>();
            for (int i = 0; out.size() < ROW_SIZE && (i < movies.size() || i < shows.size()); i++) {
                if (i < shows.size()) out.add(shows.get(i));
                if (i < movies.size()) out.add(movies.get(i));
            }
            return out.size() > ROW_SIZE ? out.subList(0, ROW_SIZE) : out;
        }
        StringBuilder ids = new StringBuilder();
        for (Provider p : on) ids.append(ids.length() > 0 ? "%7C" : "").append(p.id);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        long now = System.currentTimeMillis();
        String since = f.format(new java.util.Date(now - 60L * 24 * 60 * 60 * 1000)), until = f.format(new java.util.Date(now));
        String field = type.equals("movie") ? "primary_release_date" : "first_air_date";
        String q = "watch_region=" + REGION + "&with_watch_providers=" + ids
                + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false&page=1"
                + "&" + field + ".gte=" + since + "&" + field + ".lte=" + until;
        JSONArray r = get("/discover/" + type, q, LISTS_MAX_AGE).getJSONArray("results");
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < r.length() && out.size() < ROW_SIZE; i++) {
            Title t = Title.from(r.getJSONObject(i), type, null);
            if (t.backdrop != null) out.add(t);
        }
        return out;
    }

    /** Films and series whose names match, best first, each with a poster. Kept an hour. */
    List<Title> search(String query) throws IOException, JSONException {
        String q = "query=" + java.net.URLEncoder.encode(query, "UTF-8") + "&include_adult=false&page=1";
        JSONArray r = get("/search/multi", q, 60L * 60 * 1000).getJSONArray("results");
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < r.length() && out.size() < 30; i++) {
            JSONObject o = r.getJSONObject(i);
            String mt = o.optString("media_type");
            if (!mt.equals("movie") && !mt.equals("tv")) continue;
            Title t = Title.from(o, mt, null);
            if (t.poster != null) out.add(t);
        }
        return out;
    }

    /** The title's English logo, a transparent PNG path, or null when TMDB has none. Kept a week. */
    String logo(String type, int id) throws IOException, JSONException {
        JSONArray logos = get("/" + type + "/" + id + "/images", "include_image_language=en,null", PROVIDERS_MAX_AGE)
                .optJSONArray("logos");
        if (logos == null) return null;
        for (int i = 0; i < logos.length(); i++) {
            JSONObject l = logos.getJSONObject(i);
            String path = Title.str(l, "file_path");
            if (path != null && path.endsWith(".png") && "en".equals(l.optString("iso_639_1"))) return path;
        }
        return null;
    }

    // ---- Details ------------------------------------------------------------------------------------------

    static final class Details {
        String name, year, overview, poster, backdrop, length, certification, trailer;
        double rating;
        final List<String> genres = new ArrayList<>();
        final List<String> cast = new ArrayList<>();
        final List<String> streamingOn = new ArrayList<>();   // provider names in the UAE, subscription only
    }

    Details details(String type, int id) throws IOException, JSONException {
        boolean movie = type.equals("movie");
        JSONObject o = get("/" + type + "/" + id, "append_to_response=videos,credits,watch/providers"
                + (movie ? ",release_dates" : ",content_ratings"), LISTS_MAX_AGE);
        Details d = new Details();
        d.name = Title.str(o, movie ? "title" : "name");
        String date = Title.str(o, movie ? "release_date" : "first_air_date");
        d.year = date != null && date.length() >= 4 ? date.substring(0, 4) : "";
        d.overview = Title.str(o, "overview");
        d.poster = Title.str(o, "poster_path");
        d.backdrop = Title.str(o, "backdrop_path");
        d.rating = o.optDouble("vote_average", 0);
        if (movie) {
            int m = o.optInt("runtime");
            if (m > 0) d.length = (m / 60 > 0 ? (m / 60) + "h " : "") + (m % 60) + "m";
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) d.length = seasons + (seasons == 1 ? " season" : " seasons");
        }
        JSONArray g = o.optJSONArray("genres");
        for (int i = 0; g != null && i < g.length() && i < 3; i++) d.genres.add(g.getJSONObject(i).optString("name"));
        JSONObject credits = o.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        for (int i = 0; cast != null && i < cast.length() && d.cast.size() < 4; i++) d.cast.add(cast.getJSONObject(i).optString("name"));
        d.trailer = trailer(o.optJSONObject("videos"));
        d.certification = certification(o, movie);
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject here = wp == null ? null : wp.optJSONObject("results");
        here = here == null ? null : here.optJSONObject(REGION);
        JSONArray flat = here == null ? null : here.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length(); i++) d.streamingOn.add(flat.getJSONObject(i).optString("provider_name"));
        return d;
    }

    /** The YouTube key of the official trailer, else any trailer, else a teaser. */
    private static String trailer(JSONObject videos) {
        JSONArray r = videos == null ? null : videos.optJSONArray("results");
        if (r == null) return null;
        String best = null;
        int bestScore = -1;
        for (int i = 0; i < r.length(); i++) {
            JSONObject v = r.optJSONObject(i);
            if (v == null || !"YouTube".equals(v.optString("site"))) continue;
            String t = v.optString("type");
            int score = ("Trailer".equals(t) ? 2 : "Teaser".equals(t) ? 1 : 0) * 2 + (v.optBoolean("official") ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = v.optString("key");
            }
        }
        return best;
    }

    private static String certification(JSONObject o, boolean movie) {
        JSONObject block = o.optJSONObject(movie ? "release_dates" : "content_ratings");
        JSONArray r = block == null ? null : block.optJSONArray("results");
        Map<String, String> byCountry = new LinkedHashMap<>();
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject c = r.optJSONObject(i);
            if (c == null) continue;
            String country = c.optString("iso_3166_1");
            if (movie) {
                JSONArray dates = c.optJSONArray("release_dates");
                for (int k = 0; dates != null && k < dates.length(); k++) {
                    String cert = dates.optJSONObject(k).optString("certification");
                    if (!cert.isEmpty()) { byCountry.put(country, cert); break; }
                }
            } else {
                String cert = c.optString("rating");
                if (!cert.isEmpty()) byCountry.put(country, cert);
            }
        }
        String cert = byCountry.get(REGION);
        return cert != null ? cert : byCountry.get("US");
    }

    // ---- HTTP with a disk cache ---------------------------------------------------------------------------

    private JSONObject get(String path, String query, long maxAge) throws IOException, JSONException {
        File f = new File(cache, Integer.toHexString((path + "?" + query).hashCode()) + ".json");
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAge) {
            try {
                return new JSONObject(read(f));
            } catch (JSONException ignored) {
                // fetch again
            }
        }
        boolean bearer = key.length() > 40;   // the long Read Access Token; the short API Key goes in the URL
        String url = API + path + "?language=en-US" + (query.isEmpty() ? "" : "&" + query) + (bearer ? "" : "&api_key=" + key);
        try {
            String body = Net.text(url, bearer ? key : null);
            JSONObject json = new JSONObject(body);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return json;
        } catch (IOException e) {
            if (f.exists()) return new JSONObject(read(f));   // stale beats nothing when offline
            throw e;
        }
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
