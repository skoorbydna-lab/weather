package uk.skoorbydna.rideweather;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/** Called from the web page (window.RideWeatherAndroid) to tell the widget which place to show. */
public class Bridge {
    private final Context ctx;

    Bridge(Context ctx) { this.ctx = ctx; }

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
            if (changed) RainWidget.requestUpdate(ctx);
            else if (age > 15 * 60_000L) RainWidget.requestUpdate(ctx);
        } catch (Exception ignored) { }
    }
}
