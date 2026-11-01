package com.tvrefresh.home;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Dubai weather from Open-Meteo (free, no key): now, the next 24 hours, the next 7 days, and today's sunrise and
 * sunset, which decide when Home turns dark. The last answer is kept, so nothing ever waits for it.
 */
final class Weather {
    static final String CITY = "Dubai";
    /** Home keeps Dubai time itself: the TV's own time zone has been seen falling back to GMT. */
    static final TimeZone ZONE = TimeZone.getTimeZone("Asia/Dubai");
    private static final String URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=25.2048&longitude=55.2708"
            + "&current=temperature_2m,weather_code,is_day,apparent_temperature,relative_humidity_2m,wind_speed_10m,uv_index"
            + "&hourly=temperature_2m,weather_code,is_day"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,precipitation_probability_max"
            + "&timezone=Asia%2FDubai&forecast_days=7&forecast_hours=24";
    private static final long MAX_AGE = 15 * 60 * 1000L;

    static final class Hour {
        final int hour, temp, code;
        final boolean day;

        Hour(int hour, int temp, int code, boolean day) {
            this.hour = hour;
            this.temp = temp;
            this.code = code;
            this.day = day;
        }
    }

    static final class Day {
        final int weekday;          // Calendar.SUNDAY ...
        final int high, low, code, rain;

        Day(int weekday, int high, int low, int code, int rain) {
            this.weekday = weekday;
            this.high = high;
            this.low = low;
            this.code = code;
            this.rain = rain;
        }
    }

    final int temp, high, low, code;
    final boolean day;
    final int sunrise;      // minutes after local midnight, or -1 when not known
    final int sunset;
    final int feels, humidity, uv, rain;    // -1 when not known
    final double wind;                      // km/h, -1 when not known
    final Hour[] hours;
    final Day[] days;

    private Weather(JSONObject json) throws JSONException {
        JSONObject now = json.getJSONObject("current");
        JSONObject daily = json.getJSONObject("daily");
        temp = (int) Math.round(now.getDouble("temperature_2m"));
        code = now.getInt("weather_code");
        day = now.optInt("is_day", 1) == 1;
        feels = (int) Math.round(now.optDouble("apparent_temperature", -1));
        humidity = now.optInt("relative_humidity_2m", -1);
        wind = now.optDouble("wind_speed_10m", -1);
        uv = (int) Math.round(now.optDouble("uv_index", -1));
        high = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
        low = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
        sunrise = minutes(daily.optJSONArray("sunrise"), 0);
        sunset = minutes(daily.optJSONArray("sunset"), 0);
        JSONArray rains = daily.optJSONArray("precipitation_probability_max");
        rain = rains == null ? -1 : rains.optInt(0, -1);

        JSONObject hourly = json.optJSONObject("hourly");
        JSONArray ht = hourly == null ? null : hourly.optJSONArray("time");
        hours = new Hour[ht == null ? 0 : ht.length()];
        for (int i = 0; i < hours.length; i++) {
            hours[i] = new Hour(minutes(ht, i) / 60, (int) Math.round(hourly.getJSONArray("temperature_2m").getDouble(i)),
                    hourly.getJSONArray("weather_code").getInt(i), hourly.getJSONArray("is_day").optInt(i, 1) == 1);
        }
        JSONArray dt = daily.optJSONArray("time");
        JSONArray codes = daily.optJSONArray("weather_code");
        days = new Day[dt == null || codes == null ? 0 : dt.length()];
        for (int i = 0; i < days.length; i++) {
            days[i] = new Day(weekday(dt.getString(i)), (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)),
                    (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)), codes.getInt(i),
                    rains == null ? -1 : rains.optInt(i, -1));
        }
    }

    /** "2026-11-01T06:21" (local time, as asked for) to 381. */
    private static int minutes(JSONArray a, int i) {
        String s = a == null ? "" : a.optString(i, "");
        if (s.length() < 16) return -1;
        try {
            return Integer.parseInt(s.substring(11, 13)) * 60 + Integer.parseInt(s.substring(14, 16));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int weekday(String date) {
        Calendar c = Calendar.getInstance(ZONE, Locale.US);
        c.clear();
        c.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1, Integer.parseInt(date.substring(8, 10)));
        return c.get(Calendar.DAY_OF_WEEK);
    }

    /** Now, in Dubai. */
    static Calendar now() {
        return Calendar.getInstance(ZONE, Locale.US);
    }

    static Weather cached(SharedPreferences prefs) {
        String s = prefs.getString("weather", null);
        if (s == null) return null;
        try {
            return new Weather(new JSONObject(s));
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    /** Too old, or saved before this version asked for the hours and days. */
    static boolean stale(SharedPreferences prefs, Weather w) {
        return w == null || w.hours.length == 0 || System.currentTimeMillis() - prefs.getLong("weather_time", 0) > MAX_AGE;
    }

    static Weather fetch(SharedPreferences prefs) throws IOException, JSONException {
        String s = Net.text(URL);
        Weather w = new Weather(new JSONObject(s));
        prefs.edit().putString("weather", s).putLong("weather_time", System.currentTimeMillis()).apply();
        return w;
    }

    String condition() {
        return condition(code, day);
    }

    String[] dots() {
        return dots(code, day);
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    static String condition(int code, boolean day) {
        switch (code) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: case 56: case 57: return "Drizzle";
            case 61: case 63: case 65: case 66: case 67: return "Rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: case 82: return "Showers";
            case 85: case 86: return "Snow showers";
            case 95: case 96: case 99: return "Thunderstorm";
            default: return "";
        }
    }

    /** Which dot picture DotMatrixView draws for a weather code. */
    static String[] dots(int code, boolean day) {
        if (code <= 1) return day ? DotMatrixView.SUN : DotMatrixView.MOON;
        if (code <= 3 || code == 45 || code == 48) return DotMatrixView.CLOUD;
        return DotMatrixView.RAIN;
    }

    static String uvWord(int uv) {
        return uv < 3 ? "Low" : uv < 6 ? "Moderate" : uv < 8 ? "High" : uv < 11 ? "Very high" : "Extreme";
    }
}
