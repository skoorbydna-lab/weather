package uk.skoorbydna.rideweather.watch;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Forecast for the watch: fetch, save, and turn into something to show. Same 2-of-3 rain rule as the phone. */
public class WData {
    static final String API = "https://api.open-meteo.com/v1/forecast";
    static final String[] RAIN_MODELS = {"dmi_seamless", "icon_seamless", "ukmo_seamless"};
    static final int SLOTS = 12;          // 12 x 15 min = 3 hours
    static final double WET = 0.1;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("rw", Context.MODE_PRIVATE); }

    // ---------- location ----------
    static boolean hasLoc(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasBgLoc(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static Location locate(Context c) {
        if (!hasLoc(c)) return null;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            List<String> on = lm.getProviders(true);
            String prov = on.contains(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER
                    : on.contains(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                    : on.contains(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
            Location got = null;
            if (prov != null) {
                final Location[] box = new Location[1];
                final CountDownLatch done = new CountDownLatch(1);
                lm.getCurrentLocation(prov, null, Executors.newSingleThreadExecutor(), l -> { box[0] = l; done.countDown(); });
                done.await(8, TimeUnit.SECONDS);
                got = box[0];
            }
            if (got == null) {
                for (String p : on) {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (got == null || l.getTime() > got.getTime())) got = l;
                }
            }
            return got;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    static String placeName(Context c, double lat, double lon) {
        try {
            if (!Geocoder.isPresent()) return null;
            List<Address> a = new Geocoder(c, Locale.UK).getFromLocation(lat, lon, 1);
            if (a == null || a.isEmpty()) return null;
            Address ad = a.get(0);
            if (ad.getLocality() != null) return ad.getLocality();
            if (ad.getSubLocality() != null) return ad.getSubLocality();
            return ad.getSubAdminArea();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- fetch ----------
    static void fetch(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        double lat = p.getFloat("lat", 51.5074f), lon = p.getFloat("lon", -0.1278f);
        String name = p.getString("name", "London");
        Location l = locate(c);
        if (l != null) {
            boolean moved = Math.abs(l.getLatitude() - lat) > 0.01 || Math.abs(l.getLongitude() - lon) > 0.01;
            lat = l.getLatitude();
            lon = l.getLongitude();
            if (moved || "London".equals(name)) {
                String n = placeName(c, lat, lon);
                name = n != null ? n : "My location";
            }
        }
        String ll = String.format(Locale.UK, "latitude=%.3f&longitude=%.3f", lat, lon);
        String main = get(API + "?" + ll + "&timezone=auto&wind_speed_unit=mph"
                + "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
                + "&hourly=temperature_2m,weather_code,is_day,precipitation,precipitation_probability,wind_speed_10m,wind_direction_10m&forecast_hours=13"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,sunrise,sunset&forecast_days=4");
        String rain = get(API + "?" + ll + "&timezone=auto&minutely_15=precipitation&past_minutely_15=1&forecast_minutely_15="
                + (SLOTS + 2) + "&models=" + String.join(",", RAIN_MODELS));
        p.edit().putFloat("lat", (float) lat).putFloat("lon", (float) lon).putString("name", name)
                .putString("cMain", main).putString("cRain", rain).putLong("fetchedAt", System.currentTimeMillis()).apply();
    }

    static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setRequestProperty("User-Agent", "RideWeatherWatch/1.0");
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

    // ---------- parsed data ----------
    static class Hour { String time, icon; double temp, rain, prob, wind, dir; boolean day; }
    static class Day { String name, icon; double max, min, rain, prob; }

    String name, desc, icon, sunrise, sunset;
    long fetchedAt;
    double temp, feels, wind, gust, dir, hi = Double.NaN, lo = Double.NaN;
    double[] rain = new double[SLOTS];
    String[] times = new String[SLOTS];
    long[] starts = new long[SLOTS];      // slot start, epoch ms
    List<Hour> hours = new ArrayList<>();
    List<Day> days = new ArrayList<>();

    static WData load(Context c) {
        SharedPreferences p = prefs(c);
        String sMain = p.getString("cMain", null), sRain = p.getString("cRain", null);
        if (sMain == null) return null;
        try {
            WData d = new WData();
            d.name = p.getString("name", "");
            d.fetchedAt = p.getLong("fetchedAt", 0);
            JSONObject m = new JSONObject(sMain);
            JSONObject cur = m.getJSONObject("current");
            d.temp = cur.optDouble("temperature_2m");
            d.feels = cur.optDouble("apparent_temperature");
            d.wind = cur.optDouble("wind_speed_10m");
            d.gust = cur.optDouble("wind_gusts_10m");
            d.dir = cur.optDouble("wind_direction_10m");
            int code = cur.optInt("weather_code", 3);
            boolean day = cur.optInt("is_day", 1) == 1;
            d.desc = words(code);
            d.icon = icon(code, day);

            JSONObject h = m.optJSONObject("hourly");
            if (h != null) {
                JSONArray t = h.getJSONArray("time");
                for (int i = 1; i < t.length(); i++) {          // skip the current hour
                    Hour x = new Hour();
                    x.time = t.getString(i).substring(11, 16);
                    x.temp = h.getJSONArray("temperature_2m").optDouble(i);
                    x.icon = icon(h.getJSONArray("weather_code").optInt(i, 3), h.getJSONArray("is_day").optInt(i, 1) == 1);
                    x.day = h.getJSONArray("is_day").optInt(i, 1) == 1;
                    x.rain = h.getJSONArray("precipitation").optDouble(i);
                    x.prob = h.getJSONArray("precipitation_probability").optDouble(i);
                    x.wind = h.getJSONArray("wind_speed_10m").optDouble(i);
                    x.dir = h.getJSONArray("wind_direction_10m").optDouble(i);
                    d.hours.add(x);
                }
            }
            JSONObject dl = m.optJSONObject("daily");
            if (dl != null) {
                JSONArray t = dl.getJSONArray("time");
                d.hi = dl.getJSONArray("temperature_2m_max").optDouble(0);
                d.lo = dl.getJSONArray("temperature_2m_min").optDouble(0);
                d.sunrise = dl.getJSONArray("sunrise").optString(0, "").replaceAll(".*T", "");
                d.sunset = dl.getJSONArray("sunset").optString(0, "").replaceAll(".*T", "");
                for (int i = 1; i < t.length(); i++) {
                    Day x = new Day();
                    x.name = LocalDate.parse(t.getString(i)).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.UK);
                    x.icon = icon(dl.getJSONArray("weather_code").optInt(i, 3), true);
                    x.max = dl.getJSONArray("temperature_2m_max").optDouble(i);
                    x.min = dl.getJSONArray("temperature_2m_min").optDouble(i);
                    x.rain = dl.getJSONArray("precipitation_sum").optDouble(i);
                    x.prob = dl.getJSONArray("precipitation_probability_max").optDouble(i);
                    d.days.add(x);
                }
            }

            for (int i = 0; i < SLOTS; i++) { d.rain[i] = Double.NaN; d.times[i] = ""; }
            if (sRain != null) {
                JSONObject r = new JSONObject(sRain);
                long off = r.optLong("utc_offset_seconds", 0) * 1000;
                JSONObject mm = r.getJSONObject("minutely_15");
                JSONArray time = mm.getJSONArray("time");
                long now = System.currentTimeMillis();
                int k = 0;
                for (int i = 0; i < time.length() && k < SLOTS; i++) {
                    String ts = time.getString(i);
                    // each value is the rain in the 15 minutes BEFORE its time
                    long ms = LocalDateTime.parse(ts).toEpochSecond(ZoneOffset.UTC) * 1000 - off;
                    if (ms <= now) continue;
                    List<Double> vals = new ArrayList<>();
                    for (String mod : RAIN_MODELS) {
                        JSONArray a = mm.optJSONArray("precipitation_" + mod);
                        if (a != null && !a.isNull(i)) vals.add(a.optDouble(i));
                    }
                    d.rain[k] = agree(vals);
                    d.starts[k] = ms - 15 * 60_000L;
                    d.times[k] = LocalDateTime.parse(ts).minusMinutes(15).toString().substring(11, 16);
                    k++;
                }
            }
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    static double agree(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        Collections.sort(v);
        return v.size() >= 3 ? v.get(v.size() / 2) : v.get(0);
    }

    /** Short line about rain in the next 3 hours. */
    String rainLine() {
        int first = -1, end = -1;
        double total = 0;
        boolean any = false;
        for (int i = 0; i < SLOTS; i++) {
            if (Double.isNaN(rain[i])) continue;
            any = true;
            if (rain[i] >= WET) {
                if (first < 0) first = i;
                if (end < 0) total += rain[i];
            } else if (first >= 0 && end < 0) end = i;
        }
        if (!any) return "No rain data";
        if (first < 0) return "Dry for 3 hours";
        String mm = String.format(Locale.UK, "%.1f mm", total);
        if (first == 0) return (end > 0 ? "Rain until " + times[end] : "Rain for 3 h+") + " · " + mm;
        return "Rain " + times[first] + (end > 0 ? "–" + times[end] : "") + " · " + mm;
    }

    /** Very short version for the tile, where the round screen is narrow. */
    String shortRainLine() {
        int first = -1, end = -1;
        boolean any = false;
        for (int i = 0; i < SLOTS; i++) {
            if (Double.isNaN(rain[i])) continue;
            any = true;
            if (rain[i] >= WET) { if (first < 0) first = i; }
            else if (first >= 0 && end < 0) end = i;
        }
        if (!any) return "No rain data";
        if (first < 0) return "Dry for 3 hours";
        if (first == 0) return end > 0 ? "Rain until " + times[end] : "Rain for 3 h+";
        return "Rain from " + times[first];
    }

    boolean wetSoon() {
        for (double r : rain) if (!Double.isNaN(r) && r >= WET) return true;
        return false;
    }

    // ---------- pictures ----------
    static final String[] COMPASS = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};

    static String compass(double d) {
        if (Double.isNaN(d)) return "";
        return COMPASS[(int) Math.round(((d % 360) + 360) % 360 / 22.5) % 16];
    }

    static int windColour(double mph) {
        if (Double.isNaN(mph)) return Color.WHITE;
        if (mph < 8) return 0xFF22C55E;
        if (mph < 15) return 0xFFEAB308;
        if (mph < 22) return 0xFFF97316;
        return 0xFFF43F5E;
    }

    /** Arrow pointing the way the wind blows (same as the phone app). */
    static Bitmap arrow(int size, double dir, int colour) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        if (Double.isNaN(dir)) return b;
        Canvas cv = new Canvas(b);
        cv.scale(size / 24f, size / 24f);
        cv.rotate((float) ((dir + 180) % 360), 12, 12);
        Path p = new Path();
        p.moveTo(12, 2); p.lineTo(18, 14); p.lineTo(13.2f, 12); p.lineTo(13.2f, 22);
        p.lineTo(10.8f, 22); p.lineTo(10.8f, 12); p.lineTo(6, 14); p.close();
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(colour);
        cv.drawPath(p, pt);
        return b;
    }

    /** 12 rain bars (15 min each) with "now / +1h / +2h" times underneath. */
    Bitmap bars(int w, int h, float dens) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFF9FB2D6);
        text.setTextSize(9 * dens);
        float labelH = 11 * dens, top = 2 * dens, base = h - labelH - dens;
        float slot = (float) w / SLOTS, gap = Math.max(2, slot * 0.2f);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(0x44FFFFFF);
        line.setStrokeWidth(Math.max(1, dens));
        cv.drawLine(0, base, w, base, line);
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < SLOTS; i++) {
            double mm = rain[i];
            float x0 = i * slot + gap / 2, x1 = (i + 1) * slot - gap / 2;
            if (Double.isNaN(mm) || mm <= 0) {
                bar.setColor(0x26FFFFFF);
                cv.drawRoundRect(new RectF(x0, base - 2 * dens, x1, base), dens, dens, bar);
            } else {
                double rate = mm * 4;                                   // mm per hour, same scale as the phone (0–6)
                float frac = (float) (Math.min(rate, RC_MAX) / RC_MAX);
                float y = base - Math.max(3 * dens, frac * (base - top));
                bar.setColor(rainColour(rate));
                cv.drawRoundRect(new RectF(x0, y, x1, base), 2 * dens, 2 * dens, bar);
            }
        }
        for (int i = 0; i < SLOTS; i += 4) {
            String t = i == 0 ? "now" : times[i];
            if (t == null || t.isEmpty()) continue;
            cv.drawText(t, i * slot + gap / 2, h - 2 * dens, text);
        }
        return b;
    }

    /** Same colours as the phone app's Raincast: green → yellow → orange → red (mm per hour). */
    static final double[][] STOPS = {{0.1, 74, 222, 128}, {1, 163, 230, 53}, {2, 250, 204, 21}, {3.5, 249, 115, 22}, {5, 239, 68, 68}};
    static final double RC_MAX = 6;

    static int rainColour(double rate) {
        double[] a = STOPS[0];
        if (rate <= a[0]) return 0xFF000000 | ((int) a[1] << 16) | ((int) a[2] << 8) | (int) a[3];
        for (int i = 1; i < STOPS.length; i++) {
            double[] b = STOPS[i];
            if (rate <= b[0]) {
                double f = (rate - a[0]) / (b[0] - a[0]);
                int r = (int) Math.round(a[1] + (b[1] - a[1]) * f), g = (int) Math.round(a[2] + (b[2] - a[2]) * f), bl = (int) Math.round(a[3] + (b[3] - a[3]) * f);
                return 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
            a = b;
        }
        return 0xFF000000 | ((int) a[1] << 16) | ((int) a[2] << 8) | (int) a[3];
    }

    /** Rain in the next hour as 12 five-minute dashes round the edge, countdown style:
     *  12 o'clock is now, going clockwise – a quarter of the way round is "in 15 minutes". */
    static void drawRing(Canvas cv, int size, float dens, WData d) {
        float stroke = 7 * dens, inset = stroke / 2 + 2 * dens;
        RectF r = new RectF(inset, inset, size - inset, size - inset);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.BUTT);
        long now = System.currentTimeMillis();
        for (int j = 0; j < 12; j++) {
            double rate = d == null ? Double.NaN : d.rateAt(now + j * 5 * 60_000L + 150_000L);
            boolean wet = !Double.isNaN(rate) && rate >= 0.2;
            p.setColor(wet ? rainColour(rate) : 0x40FFFFFF);
            cv.drawArc(r, -90 + 30 * j + 2.2f, 25.6f, false, p);
        }
        // small white mark at 12 o'clock = now
        Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        mark.setColor(0xFFFFFFFF);
        mark.setStrokeWidth(2.5f * dens);
        mark.setStrokeCap(Paint.Cap.ROUND);
        cv.drawLine(size / 2f, inset - stroke / 2 - dens, size / 2f, inset + stroke / 2 + dens, mark);
    }

    static Bitmap ring(int size, float dens, WData d) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        drawRing(new Canvas(b), size, dens, d);
        return b;
    }

    /** Agreed rain rate (mm/h) at a moment, from the 15-minute slots. */
    double rateAt(long t) {
        for (int k = 0; k < SLOTS; k++) {
            if (starts[k] == 0) continue;
            if (t >= starts[k] && t < starts[k] + 15 * 60_000L) return Double.isNaN(rain[k]) ? Double.NaN : rain[k] * 4;
        }
        return Double.NaN;
    }

    /** Best 2-hour daylight window in the next 12 hours (same idea as the phone's best ride), or null. */
    String[] bestRide() {
        int best = -1;
        double bv = 1e9;
        for (int n = 0; n + 1 < hours.size(); n++) {
            Hour a = hours.get(n), b = hours.get(n + 1);
            if (!a.day || !b.day) continue;
            double v = (score(a) + score(b)) / 2;
            if (v < bv) { bv = v; best = n; }
        }
        if (best < 0) return null;
        String from = hours.get(best).time;
        int h = Integer.parseInt(from.substring(0, 2));
        return new String[]{from, String.format(Locale.UK, "%02d:00", (h + 2) % 24)};
    }

    static double score(Hour x) {
        double p = Double.isNaN(x.rain) ? 0 : x.rain, w = Double.isNaN(x.wind) ? 0 : x.wind;
        double sc = p * 3 + (Double.isNaN(x.prob) ? (p > 0 ? 1 : 0) : x.prob * 0.02) + Math.max(0, w - 8) * 0.25;
        if (!Double.isNaN(x.temp) && x.temp < 3) sc += (3 - x.temp) * 0.4;
        return sc;
    }

    // ---------- complication text ----------
    /** Short slot: big text + small title, e.g. "6°" / "Dry", "19:30" / "Rain", "18:45" / "Stops". */
    String[] complication() {
        int first = -1, end = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (Double.isNaN(rain[i])) continue;
            if (rain[i] >= WET) { if (first < 0) first = i; }
            else if (first >= 0 && end < 0) end = i;
        }
        String t = Math.round(temp) + "°";
        if (first < 0) return new String[]{t, "Dry"};
        if (first == 0) return end > 0 ? new String[]{times[end], "Stops"} : new String[]{t, "Rain"};
        return new String[]{times[first], "Rain"};
    }

    static byte[] png(Bitmap b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }

    // ---------- words & icons ----------
    static String words(int c) {
        switch (c) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
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
            case 80: case 81: return "Showers";
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
