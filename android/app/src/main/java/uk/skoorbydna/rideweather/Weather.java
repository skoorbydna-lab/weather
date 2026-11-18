package uk.skoorbydna.rideweather;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Fetches the forecast for the widget from Open-Meteo, using the same 2-of-3 rain rule as the web app. */
public class Weather {
    static final String API = "https://api.open-meteo.com/v1/forecast";
    static final String[] RAIN_MODELS = {"dmi_seamless", "icon_seamless", "ukmo_seamless"};
    static final double WET = 0.1;           // mm in 15 minutes counts as rain (same as the alerts)
    static final int SLOTS = 12;             // 12 x 15 min = 3 hours

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("rw", Context.MODE_PRIVATE);
    }

    /** Download and save the latest data. Throws if the network fails. */
    static void fetch(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String name = p.getString("name", "London");
        double[] ll = where(c);
        double lat = ll[0], lon = ll[1];
        String sLL = String.format(Locale.UK, "latitude=%.3f&longitude=%.3f", lat, lon);
        String now = get(API + "?" + sLL + "&timezone=auto&wind_speed_unit=mph"
                + "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
                + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1");
        String rain = get(API + "?" + sLL + "&timezone=auto&minutely_15=precipitation&past_minutely_15=1&forecast_minutely_15="
                + (SLOTS + 1) + "&models=" + String.join(",", RAIN_MODELS));
        p.edit().putString("cNow", now).putString("cRain", rain).putString("cName", name)
                .putLong("fetchedAt", System.currentTimeMillis()).apply();
    }

    /** Where to forecast for: the app's place, or where you are now when it's set to My location. */
    static double[] where(Context c) {
        SharedPreferences p = prefs(c);
        double lat = p.getFloat("lat", 51.5074f), lon = p.getFloat("lon", -0.1278f);

        if (p.getBoolean("gps", false)) {
            // "My location": use the newest of your alert server's location (OwnTracks) and the phone's last fix
            long bestTs = 0;
            String srv = p.getString("srv", ""), key = p.getString("key", "");
            if (!srv.isEmpty() && !key.isEmpty()) {
                try {
                    JSONObject st = new JSONObject(get(srv + "/status?key=" + URLEncoder.encode(key, "UTF-8")));
                    JSONObject l = st.optJSONObject("loc");
                    if (l != null && l.has("lat")) {
                        lat = l.getDouble("lat"); lon = l.getDouble("lon"); bestTs = l.optLong("ts", 1);
                    }
                } catch (Exception ignored) { }
            }
            Location dl = freshLocation(c);
            if (dl == null) dl = lastKnown(c);
            if (dl != null && dl.getTime() > bestTs) { lat = dl.getLatitude(); lon = dl.getLongitude(); }
            p.edit().putFloat("gpsLat", (float) lat).putFloat("gpsLon", (float) lon).apply();
        }
        return new double[]{lat, lon};
    }

    static boolean hasLoc(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasBackgroundLoc(Context c) {
        return Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Ask the phone for a new fix (up to 6 s). Needs "Allow all the time" when the app isn't open. */
    static Location freshLocation(Context c) {
        if (!hasLoc(c) || Build.VERSION.SDK_INT < 30) return null;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            List<String> on = lm.getProviders(true);
            String prov = Build.VERSION.SDK_INT >= 31 && on.contains(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER
                    : on.contains(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                    : on.contains(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
            if (prov == null) return null;
            final Location[] got = new Location[1];
            final CountDownLatch done = new CountDownLatch(1);
            lm.getCurrentLocation(prov, null, Executors.newSingleThreadExecutor(), loc -> { got[0] = loc; done.countDown(); });
            done.await(6, TimeUnit.SECONDS);
            return got[0];
        } catch (Exception e) {
            return null;
        }
    }

    static Location lastKnown(Context c) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String prov : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            return best;
        } catch (SecurityException e) {
            return null;
        }
    }

    static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        con.setRequestProperty("User-Agent", "RideWeatherWidget/1.0");
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = con.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    // ---------- what the widget shows ----------
    static class Data {
        String name, desc, icon, unit;
        double temp, feels, hi = Double.NaN, lo = Double.NaN, wind, gust, dir;
        double[] rain = new double[SLOTS];   // agreed mm per 15 min, NaN = no data
        String[] times = new String[SLOTS];  // "HH:MM" start of each slot
        long fetchedAt;
    }

    /** Build the widget data from what was saved; null if nothing has been fetched yet. */
    static Data load(Context c) {
        SharedPreferences p = prefs(c);
        String sNow = p.getString("cNow", null), sRain = p.getString("cRain", null);
        if (sNow == null) return null;
        try {
            Data d = new Data();
            d.name = p.getString("cName", "");
            d.unit = p.getString("unit", "mph");
            d.fetchedAt = p.getLong("fetchedAt", 0);
            JSONObject now = new JSONObject(sNow);
            JSONObject cur = now.getJSONObject("current");
            d.temp = cur.optDouble("temperature_2m");
            d.feels = cur.optDouble("apparent_temperature");
            d.wind = cur.optDouble("wind_speed_10m");
            d.gust = cur.optDouble("wind_gusts_10m");
            d.dir = cur.optDouble("wind_direction_10m");
            int code = cur.optInt("weather_code", 3);
            boolean day = cur.optInt("is_day", 1) == 1;
            d.desc = words(code);
            d.icon = icon(code, day);
            JSONObject daily = now.optJSONObject("daily");
            if (daily != null) {
                d.hi = daily.getJSONArray("temperature_2m_max").optDouble(0);
                d.lo = daily.getJSONArray("temperature_2m_min").optDouble(0);
            }
            for (int i = 0; i < SLOTS; i++) { d.rain[i] = Double.NaN; d.times[i] = ""; }
            if (sRain != null) {
                JSONObject r = new JSONObject(sRain);
                long off = r.optLong("utc_offset_seconds", 0) * 1000;
                JSONObject m = r.getJSONObject("minutely_15");
                JSONArray time = m.getJSONArray("time");
                long t0 = System.currentTimeMillis();
                int k = 0;
                for (int i = 0; i < time.length() && k < SLOTS; i++) {
                    String t = time.getString(i);
                    long ms = LocalDateTime.parse(t).toEpochSecond(ZoneOffset.UTC) * 1000 - off;
                    if (ms + 15 * 60_000L <= t0) continue;           // slot already over
                    List<Double> vals = new ArrayList<>();
                    for (String mod : RAIN_MODELS) {
                        JSONArray a = m.optJSONArray("precipitation_" + mod);
                        if (a != null && !a.isNull(i)) vals.add(a.optDouble(i));
                    }
                    if (vals.isEmpty()) {
                        JSONArray a = m.optJSONArray("precipitation");
                        if (a != null && !a.isNull(i)) vals.add(a.optDouble(i));
                    }
                    d.rain[k] = agree(vals);
                    d.times[k] = t.length() >= 16 ? t.substring(11, 16) : "";
                    k++;
                }
            }
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /** 2 of 3: the middle of three models; with only two, the lower one. */
    static double agree(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        Collections.sort(v);
        if (v.size() >= 3) return v.get(v.size() / 2);
        return v.get(0);
    }

    /** One line about rain in the next 3 hours. */
    static String rainLine(Data d) {
        int first = -1, end = -1;
        double total = 0;
        boolean any = false;
        for (int i = 0; i < SLOTS; i++) {
            if (Double.isNaN(d.rain[i])) continue;
            any = true;
            if (d.rain[i] >= WET) {
                if (first < 0) first = i;
                if (end < 0) total += d.rain[i];
            } else if (first >= 0 && end < 0) end = i;
        }
        if (!any) return "No rain data";
        if (first < 0) return "☀ Dry for the next 3 hours";
        String mm = String.format(Locale.UK, "%.1f mm", total);
        String from = first == 0 ? "Rain now" : "Rain from " + d.times[first];
        if (end > 0) return "🌧 " + from + " until " + d.times[end] + " · " + mm;
        return "🌧 " + from + " · " + mm + " in the next 3 h";
    }

    static String words(int c) {
        switch (c) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: return "Fog";
            case 48: return "Freezing fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: return "Light snow";
            case 73: return "Snow";
            case 75: return "Heavy snow";
            case 77: return "Snow grains";
            case 80: return "Showers";
            case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorms";
            case 96: case 99: return "Thunder & hail";
            default: return "";
        }
    }

    static String icon(int c, boolean day) {
        if (c == 0) return day ? "☀️" : "🌙";
        if (c == 1) return day ? "🌤️" : "🌙";
        if (c == 2) return day ? "⛅" : "☁️";
        if (c == 3) return "☁️";
        if (c == 45 || c == 48) return "🌫️";
        if (c >= 51 && c <= 57) return "🌦️";
        if (c >= 61 && c <= 67) return "🌧️";
        if (c >= 71 && c <= 77) return "🌨️";
        if (c >= 80 && c <= 82) return day ? "🌦️" : "🌧️";
        if (c == 85 || c == 86) return "🌨️";
        if (c >= 95) return "⛈️";
        return "☁️";
    }
}
