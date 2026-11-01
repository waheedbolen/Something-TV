package com.tvrefresh.home;

import org.json.JSONObject;

/** A film or a series from TMDB, as a row card shows it. */
final class Title {
    final String type;          // "movie" or "tv"
    final int id;
    final String name;
    final String year;
    final String date;          // yyyy-MM-dd, or null
    final String overview;
    final String poster;        // TMDB image paths, or null
    final String backdrop;
    final double rating;
    final Tmdb.Provider provider;   // the platform whose row it came from, or null

    private Title(String type, int id, String name, String date, String overview, String poster, String backdrop,
                  double rating, Tmdb.Provider provider) {
        this.type = type;
        this.id = id;
        this.name = name;
        this.date = date != null && date.length() >= 10 ? date.substring(0, 10) : null;
        this.year = date != null && date.length() >= 4 ? date.substring(0, 4) : "";
        this.overview = overview;
        this.poster = poster;
        this.backdrop = backdrop;
        this.rating = rating;
        this.provider = provider;
    }

    /** From a TMDB list result; type may come from the result itself ("media_type", trending lists). */
    static Title from(JSONObject o, String type, Tmdb.Provider provider) {
        String t = type != null ? type : o.optString("media_type", "movie");
        boolean movie = t.equals("movie");
        String name = str(o, movie ? "title" : "name");
        String date = str(o, movie ? "release_date" : "first_air_date");
        return new Title(t, o.optInt("id"), name == null ? "" : name, date,
                str(o, "overview"), str(o, "poster_path"), str(o, "backdrop_path"),
                o.optDouble("vote_average", 0), provider);
    }

    boolean isMovie() {
        return type.equals("movie");
    }

    /** "Netflix · 2024 · ★ 7.8 · Series", leaving out what is not known. */
    String meta() {
        StringBuilder b = new StringBuilder();
        if (provider != null) b.append(provider.label);
        if (!year.isEmpty()) append(b, year);
        if (rating > 0) append(b, String.format(java.util.Locale.US, "★ %.1f", rating));
        append(b, isMovie() ? "Film" : "Series");
        return b.toString();
    }

    private static void append(StringBuilder b, String s) {
        if (b.length() > 0) b.append("  ·  ");
        b.append(s);
    }

    /** optString turns JSON null into the text "null"; this does not. */
    static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String s = o.optString(key, null);
        return s == null || s.isEmpty() ? null : s;
    }
}
