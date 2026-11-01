package com.tvrefresh.home;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Films and series saved for later, kept on the TV (no account). Each entry is stored the way TMDB lists them, so
 * Title.from reads it back; the newest is first. version() changes on every save, so a screen knows to redraw.
 */
final class MyList {
    private static final String KEY = "mylist", VERSION = "mylist_v";

    static List<Title> all(SharedPreferences prefs) {
        List<Title> out = new ArrayList<>();
        JSONArray a = read(prefs);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(Title.from(o, null, null));
        }
        return out;
    }

    static boolean has(SharedPreferences prefs, String type, int id) {
        return indexOf(read(prefs), type, id) >= 0;
    }

    static int version(SharedPreferences prefs) {
        return prefs.getInt(VERSION, 0);
    }

    /** Saves the title, or takes it off the list if it is on it. Returns whether it is on the list now. */
    static boolean toggle(SharedPreferences prefs, String type, int id, String name, String year, String overview,
                          String poster, String backdrop, double rating) {
        JSONArray a = read(prefs);
        int at = indexOf(a, type, id);
        JSONArray next = new JSONArray();
        if (at < 0) {
            try {
                boolean movie = type.equals("movie");
                next.put(new JSONObject()
                        .put("media_type", type).put("id", id)
                        .put(movie ? "title" : "name", name)
                        .put(movie ? "release_date" : "first_air_date", year == null ? "" : year)
                        .put("overview", overview == null ? JSONObject.NULL : overview)
                        .put("poster_path", poster == null ? JSONObject.NULL : poster)
                        .put("backdrop_path", backdrop == null ? JSONObject.NULL : backdrop)
                        .put("vote_average", rating));
            } catch (JSONException e) {
                return false;
            }
        }
        for (int i = 0; i < a.length(); i++) if (i != at) next.put(a.opt(i));
        prefs.edit().putString(KEY, next.toString()).putInt(VERSION, version(prefs) + 1).apply();
        return at < 0;
    }

    private static int indexOf(JSONArray a, String type, int id) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optInt("id") == id && type.equals(o.optString("media_type"))) return i;
        }
        return -1;
    }

    private static JSONArray read(SharedPreferences prefs) {
        try {
            return new JSONArray(prefs.getString(KEY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
