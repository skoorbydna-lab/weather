package uk.skoorbydna.rideweather;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/** Called from the web page (window.RideWeatherAndroid) to tell the widget which place to show. */
public class Bridge {
    private final Context ctx;
    private final Runnable onGps, onAlertsOn;
    private final java.util.function.Consumer<String> reply;

    Bridge(Context ctx, Runnable onGps, Runnable onAlertsOn, java.util.function.Consumer<String> reply) {
        this.ctx = ctx; this.onGps = onGps; this.onAlertsOn = onAlertsOn; this.reply = reply;
    }

    /** Notification settings from the page (same fields as the alert server, plus appMode). */
    @JavascriptInterface
    public void setAlerts(String json) {
        Alerts.save(ctx, json);
        try { if (new JSONObject(json).optBoolean("appMode", false) && onAlertsOn != null) onAlertsOn.run(); } catch (Exception ignored) { }
    }

    /** "Send test" / "Check rain now" / "Send one now" buttons. Answer comes back via window.appAlertResult(text). */
    @JavascriptInterface
    public void runNow(String kind) {
        new Thread(() -> {
            String r;
            try {
                switch (kind) {
                    case "test": Alerts.notify(ctx, Alerts.CH_RAIN, 9, "Test alert", "Notifications from Ride Weather are working."); r = "Test sent – check your notifications"; break;
                    case "check": r = "Checked: " + Alerts.checkRain(ctx, true); break;
                    case "morning": Alerts.summary(ctx, "morning", true); r = "Morning summary sent"; break;
                    case "evening": Alerts.summary(ctx, "evening", true); r = "Evening update sent"; break;
                    case "warnings": r = "Warnings: " + Alerts.checkWarnings(ctx, true); break;
                    default: r = "Unknown";
                }
                if (!Alerts.canNotify(ctx)) r = "Notifications are blocked – allow them in Settings → Apps → Ride Weather → Notifications";
            } catch (Exception e) {
                r = "Couldn't do that: " + e.getMessage();
            }
            if (reply != null) reply.accept(r);
        }).start();
    }

    @JavascriptInterface
    public boolean isApp() { return true; }

    @JavascriptInterface
    public void setPlace(String json) {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences p = Weather.prefs(ctx);
            String name = o.optString("name", "London");
            float lat = (float) o.optDouble("lat", 51.5074), lon = (float) o.optDouble("lon", -0.1278);
            String unit = o.optString("unit", "mph");
            boolean gps = o.optBoolean("gps", false);
            boolean changed = !name.equals(p.getString("name", ""))
                    || Math.abs(lat - p.getFloat("lat", 0)) > 0.005f || Math.abs(lon - p.getFloat("lon", 0)) > 0.005f
                    || !unit.equals(p.getString("unit", "")) || gps != p.getBoolean("gps", false);
            p.edit()
                    .putString("name", name).putFloat("lat", lat).putFloat("lon", lon)
                    .putString("unit", unit).putBoolean("gps", gps)
                    .putString("srv", o.optString("srv", "")).putString("key", o.optString("key", ""))
                    .apply();
            long age = System.currentTimeMillis() - p.getLong("fetchedAt", 0);
            if (gps && onGps != null) onGps.run();
            if (changed) RainWidget.requestUpdate(ctx);
            else if (age > 15 * 60_000L) RainWidget.requestUpdate(ctx);
        } catch (Exception ignored) { }
    }
}
