package uk.skoorbydna.rideweather;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The app's own notifications: rain alerts, morning/evening summaries and Met Office warnings. */
public class Alerts {
    static final int JOB_ID = 7101;
    static final String CH_RAIN = "rain", CH_SUM = "summary", CH_WARN = "warnings";
    static final String[][] MODELS = {{"dmi_seamless", "DMI"}, {"icon_seamless", "ICON"}, {"ukmo_seamless", "Met Office"}};

    // ---------- settings (sent from the web page) ----------
    static class Settings {
        boolean on, enabled = true, summary = true, evening = true, warn = true;
        double thresh = 0.1;
        int agree = 2, summaryHour = 7, eveningHour = 17, quietStart = 22, quietEnd = 6;
        String region = "auto";
    }

    static Settings settings(Context c) {
        Settings s = new Settings();
        try {
            JSONObject o = new JSONObject(Weather.prefs(c).getString("alerts", "{}"));
            s.on = o.optBoolean("appMode", false);
            s.enabled = o.optBoolean("enabled", true);
            s.thresh = o.optDouble("thresh", 0.1);
            s.agree = o.optInt("agree", 2);
            s.summary = o.optBoolean("summary", true);
            s.summaryHour = o.optInt("summaryHour", 7);
            s.evening = o.optBoolean("evening", true);
            s.eveningHour = o.optInt("eveningHour", 17);
            s.warn = o.optBoolean("warnAlerts", true);
            s.region = o.optString("warnRegion", "auto");
            s.quietStart = o.optInt("quietStart", 22);
            s.quietEnd = o.optInt("quietEnd", 6);
        } catch (Exception ignored) { }
        return s;
    }

    static void save(Context c, String json) {
        Weather.prefs(c).edit().putString("alerts", json).apply();
        schedule(c);
    }

    // ---------- scheduling ----------
    static void schedule(Context c) {
        Settings s = settings(c);
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (s.on && (s.enabled || s.warn)) {
            if (js.getPendingJob(JOB_ID) == null) {
                js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, CheckJob.class))
                        .setPeriodic(15 * 60_000L)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build());
            }
        } else {
            js.cancel(JOB_ID);
        }
        alarm(c, "morning", s.on && s.summary, s.summaryHour, 21);
        alarm(c, "evening", s.on && s.evening, s.eveningHour, 22);
    }

    static void alarm(Context c, String kind, boolean on, int hour, int req) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, SummaryReceiver.class).setAction("uk.skoorbydna.rideweather.SUMMARY").putExtra("kind", kind);
        PendingIntent pi = PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (!on) return;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 5);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 30_000) cal.add(Calendar.DAY_OF_YEAR, 1);
        long t = cal.getTimeInMillis();
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
        }
    }

    // ---------- notifications ----------
    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_RAIN, "Rain alerts", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_SUM, "Morning & evening summaries", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(CH_WARN, "Met Office warnings", NotificationManager.IMPORTANCE_HIGH));
    }

    static boolean canNotify(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static void notify(Context c, String channel, int id, String title, String text) {
        channels(c);
        if (!canNotify(c)) return;
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(id, n);
    }

    static boolean inQuiet(int h, int start, int end) {
        if (start == end) return false;
        return start < end ? (h >= start && h < end) : (h >= start || h < end);
    }

    static double[] arr(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        if (a == null) return null;
        double[] d = new double[a.length()];
        for (int i = 0; i < d.length; i++) d[i] = a.isNull(i) ? Double.NaN : a.optDouble(i);
        return d;
    }

    // ---------- rain alert (same rules as the alert server) ----------
    /** Returns a short result for the settings screen. */
    static String checkRain(Context c, boolean force) throws Exception {
        Settings s = settings(c);
        if (!force && (!s.on || !s.enabled)) return "alerts off";
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (!force && inQuiet(hour, s.quietStart, s.quietEnd)) return "quiet hours";

        double[] ll = Weather.where(c);
        StringBuilder ids = new StringBuilder();
        for (String[] m : MODELS) { if (ids.length() > 0) ids.append(','); ids.append(m[0]); }
        String url = String.format(Locale.UK, "%s?latitude=%.3f&longitude=%.3f&minutely_15=precipitation&forecast_minutely_15=7&past_minutely_15=1&timezone=auto&models=%s",
                Weather.API, ll[0], ll[1], ids);
        JSONObject d = new JSONObject(Weather.get(url));
        long off = d.optLong("utc_offset_seconds", 0) * 1000, now = System.currentTimeMillis();
        JSONObject m = d.getJSONObject("minutely_15");
        JSONArray time = m.getJSONArray("time");
        List<Integer> idx = new ArrayList<>();
        long[] ms = new long[time.length()];
        for (int i = 0; i < time.length(); i++) {
            // each value is the rain in the 15 minutes BEFORE its time, so the slot starts 15 min earlier
            ms[i] = LocalDateTime.parse(time.getString(i)).toEpochSecond(ZoneOffset.UTC) * 1000 - off - 15 * 60_000L;
            if (ms[i] + 15 * 60_000L > now && ms[i] < now + 60 * 60_000L) idx.add(i);
        }
        int votes = 0, yes = 0;
        double total = 0;
        List<Integer> firsts = new ArrayList<>();
        StringBuilder sum = new StringBuilder();
        for (String[] mod : MODELS) {
            double[] a = arr(m, "precipitation_" + mod[0]);
            if (a == null) continue;
            boolean has = false, wet = false;
            int first = -1;
            double t = 0;
            for (int i : idx) {
                if (Double.isNaN(a[i])) continue;
                has = true;
                t += a[i];
                if (a[i] >= s.thresh && first < 0) { first = i; wet = true; }
            }
            if (!has) continue;
            votes++;
            if (sum.length() > 0) sum.append(", ");
            sum.append(mod[1]).append(wet ? " rain" : " dry");
            if (wet) { yes++; total += t; firsts.add(first); }
        }
        if (votes == 0) return "no model data";
        int need = Math.min(s.agree, votes);
        SharedPreferences p = Weather.prefs(c);

        if (yes < need) {
            if (p.getBoolean("raining", false)) p.edit().putBoolean("raining", false).apply();
            if (force) notify(c, CH_RAIN, 1, "Dry for the next hour", "No rain expected in the next hour (" + sum + ").");
            return "dry (" + sum + ")";
        }
        if (!force && (p.getBoolean("raining", false) || now - p.getLong("lastAlert", 0) < 45 * 60_000L)) return "already alerted";

        java.util.Collections.sort(firsts);
        int f = firsts.get((firsts.size() - 1) / 2);         // middle start time of the agreeing models
        String when = ms[f] <= now ? "now" : "from about " + LocalDateTime.parse(time.getString(f)).minusMinutes(15).toString().substring(11, 16);
        String body = String.format(Locale.UK, "Rain %s – about %.1f mm in the next hour (%d of %d models agree).", when, total / yes, yes, votes);
        p.edit().putBoolean("raining", true).putLong("lastAlert", now).apply();
        notify(c, CH_RAIN, 1, "🌧 Rain coming", body);
        return "rain alert sent";
    }

    // ---------- morning / evening summary ----------
    static String summary(Context c, String kind, boolean force) throws Exception {
        Settings s = settings(c);
        SharedPreferences p = Weather.prefs(c);
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(new java.util.Date());
        String key = "last_" + kind;
        if (!force && today.equals(p.getString(key, ""))) return "already sent today";
        double[] ll = Weather.where(c);
        String url = String.format(Locale.UK, "%s?latitude=%.3f&longitude=%.3f&timezone=auto&wind_speed_unit=mph&forecast_days=2"
                + "&hourly=temperature_2m,apparent_temperature,precipitation,precipitation_probability,weather_code,cloud_cover,wind_speed_10m,wind_gusts_10m,wind_direction_10m,is_day"
                + "&daily=sunrise,sunset", Weather.API, ll[0], ll[1]);
        JSONObject d = new JSONObject(Weather.get(url));
        JSONObject h = d.getJSONObject("hourly"), daily = d.getJSONObject("daily");
        H hh = new H(h);
        boolean kmh = "km/h".equals(p.getString("unit", "mph"));
        String todayD = daily.getJSONArray("time").getString(0), tomorrowD = daily.getJSONArray("time").getString(1);
        int nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String title;
        StringBuilder b = new StringBuilder();
        if ("morning".equals(kind)) {
            Desc x = describe(hh, hh.hours(todayD, 7, 19), false, kmh);
            title = "Today's ride weather";
            if (x != null) {
                b.append(x.look).append(", ").append(Math.round(x.max)).append("° / ").append(Math.round(x.min)).append("°\n");
                b.append("Rain: ").append(x.rain).append(x.pmax >= 0 ? " (" + x.pmax + "% chance)" : "").append("\n");
                b.append("Wind: ").append(x.wind).append("\n");
            }
            b.append("Best ride: ").append(bestRide(hh, hh.hours(todayD, Math.max(nowHour, 6), 20))).append("\n");
            b.append("Daylight: ").append(daily.getJSONArray("sunrise").getString(0).substring(11, 16)).append("–")
                    .append(daily.getJSONArray("sunset").getString(0).substring(11, 16));
        } else {
            title = "Evening weather update";
            Desc t = describe(hh, hh.hours(todayD, nowHour + 1, 23), true, kmh);
            if (t != null) b.append("This evening: ").append(t.look).append(", down to ").append(Math.round(t.min)).append("°\n")
                    .append("  Rain: ").append(t.rain).append("\n  Wind: ").append(t.wind).append("\n");
            Desc tm = describe(hh, hh.hours(tomorrowD, 7, 19), false, kmh);
            Desc all = describe(hh, hh.hours(tomorrowD, 0, 23), true, kmh);
            if (tm != null) {
                b.append("Tomorrow: ").append(tm.look).append(", ").append(Math.round(tm.max)).append("° / ")
                        .append(Math.round(all != null ? all.min : tm.min)).append("°\n")
                        .append("  Rain: ").append(tm.rain).append(tm.pmax >= 0 ? " (" + tm.pmax + "% chance)" : "").append("\n")
                        .append("  Wind: ").append(tm.wind).append("\n")
                        .append("  Best ride: ").append(bestRide(hh, hh.hours(tomorrowD, 6, 20))).append("\n")
                        .append("  Daylight: ").append(daily.getJSONArray("sunrise").getString(1).substring(11, 16)).append("–")
                        .append(daily.getJSONArray("sunset").getString(1).substring(11, 16));
            }
        }
        if (!force) p.edit().putString(key, today).apply();
        notify(c, CH_SUM, "morning".equals(kind) ? 2 : 3, title, b.toString().trim());
        return "sent";
    }

    static class H {
        String[] time;
        double[] temp, feels, rain, prob, cloud, wind, gust, dir, isDay;

        H(JSONObject h) throws Exception {
            JSONArray t = h.getJSONArray("time");
            time = new String[t.length()];
            for (int i = 0; i < time.length; i++) time[i] = t.getString(i);
            temp = arr(h, "temperature_2m"); feels = arr(h, "apparent_temperature"); rain = arr(h, "precipitation");
            prob = arr(h, "precipitation_probability"); cloud = arr(h, "cloud_cover"); wind = arr(h, "wind_speed_10m");
            gust = arr(h, "wind_gusts_10m"); dir = arr(h, "wind_direction_10m"); isDay = arr(h, "is_day");
        }

        List<Integer> hours(String date, int from, int to) {
            List<Integer> r = new ArrayList<>();
            for (int i = 0; i < time.length; i++) {
                if (!time[i].startsWith(date)) continue;
                int hr = Integer.parseInt(time[i].substring(11, 13));
                if (hr >= from && hr <= to) r.add(i);
            }
            return r;
        }
    }

    static double v(double[] a, int i) { return a == null || Double.isNaN(a[i]) ? 0 : a[i]; }

    static class Desc { String look, rain, wind; double max, min; int pmax = -1; }

    static Desc describe(H h, List<Integer> idx, boolean night, boolean kmh) {
        if (idx.isEmpty()) return null;
        Desc x = new Desc();
        x.max = -999; x.min = 999;
        double rain = 0, wMax = 0, gMax = 0, sx = 0, cx = 0, cc = 0;
        int ccN = 0;
        List<Integer> wet = new ArrayList<>();
        for (int i : idx) {
            if (!Double.isNaN(h.temp[i])) { x.max = Math.max(x.max, h.temp[i]); x.min = Math.min(x.min, h.temp[i]); }
            double r = v(h.rain, i);
            rain += r;
            if (r >= 0.2) wet.add(Integer.parseInt(h.time[i].substring(11, 13)));
            if (h.prob != null && !Double.isNaN(h.prob[i])) x.pmax = Math.max(x.pmax, (int) h.prob[i]);
            wMax = Math.max(wMax, v(h.wind, i));
            gMax = Math.max(gMax, v(h.gust, i));
            if (h.dir != null && !Double.isNaN(h.dir[i])) { sx += Math.sin(Math.toRadians(h.dir[i])); cx += Math.cos(Math.toRadians(h.dir[i])); }
            if (h.cloud != null && !Double.isNaN(h.cloud[i])) { cc += h.cloud[i]; ccN++; }
        }
        double ccAvg = ccN > 0 ? cc / ccN : -1;
        if (wet.size() >= 3 || rain >= 1) x.look = wet.size() >= Math.max(3, idx.size() * 0.6) ? (night ? "Wet" : "Wet day") : "Showers";
        else if (ccAvg < 0) x.look = "Dry";
        else if (ccAvg < 15) x.look = night ? "Clear" : "Sunny";
        else if (ccAvg < 40) x.look = night ? "Mostly clear" : "Mostly sunny";
        else if (ccAvg < 75) x.look = night ? "Partly cloudy" : "Sunny spells";
        else x.look = "Cloudy, dry";
        x.rain = "Dry";
        if (rain >= 0.2) {
            String span = wet.isEmpty() ? "light" : String.format(Locale.UK, "mainly %02d–%02d", wet.get(0) % 24, (wet.get(wet.size() - 1) + 1) % 24);
            x.rain = String.format(Locale.UK, "%.1f mm, %s", rain, span);
        }
        String dir = (sx == 0 && cx == 0) ? "" : " " + compass8((Math.toDegrees(Math.atan2(sx, cx)) + 360) % 360);
        double f = kmh ? 1.609344 : 1;
        x.wind = Math.round(wMax * f) + (kmh ? " km/h" : " mph") + dir + ", gusts " + Math.round(gMax * f);
        return x;
    }

    static String compass8(double d) {
        String[] c = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return c[(int) Math.round(d / 45) % 8];
    }

    static String bestRide(H h, List<Integer> idx) {
        int best = -1;
        double bv = 1e9;
        for (int n = 0; n + 1 < idx.size(); n++) {
            int a = idx.get(n), b = idx.get(n + 1);
            if (b != a + 1) continue;
            if (h.isDay != null && (v(h.isDay, a) == 0 || v(h.isDay, b) == 0)) continue;
            double sc = (score(h, a) + score(h, b)) / 2;
            if (sc < bv) { bv = sc; best = a; }
        }
        if (best < 0) return "no dry daylight window";
        int hr = Integer.parseInt(h.time[best].substring(11, 13));
        return h.time[best].substring(11, 16) + "–" + String.format(Locale.UK, "%02d:00", hr + 2);
    }

    static double score(H h, int i) {
        double p = v(h.rain, i), w = v(h.wind, i), g = v(h.gust, i);
        double t = h.feels != null && !Double.isNaN(h.feels[i]) ? h.feels[i] : v(h.temp, i);
        double sc = p * 3 + (h.prob != null && !Double.isNaN(h.prob[i]) ? h.prob[i] * 0.02 : (p > 0 ? 1 : 0))
                + Math.max(0, w - 8) * 0.25 + Math.max(0, g - 22) * 0.15;
        if (t < 3) sc += (3 - t) * 0.4;
        return sc;
    }

    // ---------- Met Office warnings ----------
    static final Object[][] REGIONS = {
            {"os", 59.5, -2.0, "Orkney & Shetland"}, {"he", 57.5, -5.0, "Highlands & Eilean Siar"}, {"gr", 57.2, -2.8, "Grampian"},
            {"ta", 56.4, -3.6, "Central, Tayside & Fife"}, {"st", 55.8, -4.8, "Strathclyde"}, {"dg", 55.4, -3.2, "SW Scotland, Lothian & Borders"},
            {"ni", 54.6, -6.7, "Northern Ireland"}, {"ne", 54.9, -1.8, "North East England"}, {"nw", 54.0, -2.8, "North West England"},
            {"yh", 53.9, -1.2, "Yorkshire & Humber"}, {"wm", 52.5, -2.2, "West Midlands"}, {"em", 52.9, -1.0, "East Midlands"},
            {"ee", 52.3, 0.6, "East of England"}, {"wl", 52.3, -3.7, "Wales"}, {"sw", 50.9, -3.6, "South West England"},
            {"se", 51.3, -0.5, "London & South East England"}};

    static String checkWarnings(Context c, boolean force) throws Exception {
        Settings s = settings(c);
        if (!force && (!s.on || !s.warn)) return "warnings off";
        SharedPreferences p = Weather.prefs(c);
        if (!force && System.currentTimeMillis() - p.getLong("lastWarnCheck", 0) < 55 * 60_000L) return "checked recently";
        Object[] reg = REGIONS[REGIONS.length - 1];
        if (!"auto".equals(s.region)) {
            for (Object[] r : REGIONS) if (r[0].equals(s.region)) reg = r;
        } else {
            double[] ll = Weather.where(c);
            double bd = 1e9;
            for (Object[] r : REGIONS) {
                double dl = ll[0] - (double) r[1], dn = (ll[1] - (double) r[2]) * Math.cos(Math.toRadians(ll[0]));
                double dd = dl * dl + dn * dn;
                if (dd < bd) { bd = dd; reg = r; }
            }
        }
        String xml = Weather.get("https://www.metoffice.gov.uk/public/data/PWSCache/WarningsRSS/Region/" + reg[0]);
        Set<String> seen = new HashSet<>(p.getStringSet("seenWarn", new HashSet<>()));
        Set<String> nowSet = new HashSet<>();
        Matcher it = Pattern.compile("<item>([\\s\\S]*?)</item>").matcher(xml);
        int sent = 0;
        while (it.find()) {
            String item = it.group(1);
            String title = tag(item, "title"), desc = tag(item, "description"), guid = tag(item, "guid");
            if (guid.isEmpty()) guid = title;
            nowSet.add(guid);
            if (seen.contains(guid)) continue;
            Matcher lv = Pattern.compile("(?i)\\b(yellow|amber|red)\\b").matcher(title);
            String level = lv.find() ? lv.group(1).toLowerCase(Locale.UK) : "yellow";
            String lvl = level.substring(0, 1).toUpperCase(Locale.UK) + level.substring(1);
            notify(c, CH_WARN, 100 + Math.abs(guid.hashCode() % 1000), "⚠️ " + lvl + " weather warning – " + reg[3],
                    (desc.isEmpty() ? title : desc));
            sent++;
        }
        p.edit().putStringSet("seenWarn", nowSet).putLong("lastWarnCheck", System.currentTimeMillis()).apply();
        return sent == 0 ? "no new warnings" : sent + " warning(s) sent";
    }

    static String tag(String s, String name) {
        Matcher m = Pattern.compile("<" + name + "[^>]*>([\\s\\S]*?)</" + name + ">").matcher(s);
        if (!m.find()) return "";
        String t = m.group(1).replaceAll("<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>", "$1").replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'").replaceAll("\\s+", " ").trim();
        return t;
    }
}
