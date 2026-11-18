package uk.skoorbydna.rideweather;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen widget: temperature, wind with arrow, and the next 3 hours of rain. */
public class RainWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "uk.skoorbydna.rideweather.REFRESH";

    /** Ask the widget(s) to fetch fresh data. */
    static void requestUpdate(Context c) {
        Intent i = new Intent(c, RainWidget.class).setAction(ACTION_REFRESH);
        c.sendBroadcast(i);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        refresh(c, goAsync());
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            for (int id : ids(c)) {
                RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
                v.setTextViewText(R.id.updated, "updating…");
                mgr.partiallyUpdateAppWidget(id, v);
            }
            refresh(c, goAsync());
            return;
        }
        super.onReceive(c, intent);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager mgr, int id, Bundle newOptions) {
        draw(c, id, Weather.load(c), null);   // resized: redraw the bars at the new size
    }

    static int[] ids(Context c) {
        return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, RainWidget.class));
    }

    static void refresh(Context c, PendingResult pr) {
        final Context app = c.getApplicationContext();
        if (ids(app).length == 0) { if (pr != null) pr.finish(); return; }   // no widget on the home screen
        new Thread(() -> {
            String err = null;
            try {
                Weather.fetch(app);
            } catch (Exception e) {
                err = "couldn't update";
            }
            try {
                Weather.Data d = Weather.load(app);
                for (int id : ids(app)) draw(app, id, d, err);
            } catch (Exception ignored) {
            } finally {
                if (pr != null) pr.finish();
            }
        }).start();
    }

    static void draw(Context c, int id, Weather.Data d, String err) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);

        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        Intent ref = new Intent(c, RainWidget.class).setAction(ACTION_REFRESH);
        v.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(c, 1, ref,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        if (d == null) {
            v.setTextViewText(R.id.updated, err != null ? err : "");
            v.setTextViewText(R.id.rain, "Open the app once, then tap ↻");
            mgr.updateAppWidget(id, v);
            return;
        }

        boolean kmh = "km/h".equals(d.unit);
        double f = kmh ? 1.609344 : 1;
        String u = kmh ? "km/h" : "mph";

        v.setTextViewText(R.id.place, d.name);
        String at = new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(d.fetchedAt));
        v.setTextViewText(R.id.updated, err != null ? err + " · " + at : at);
        v.setTextViewText(R.id.icon, d.icon);
        v.setTextViewText(R.id.temp, Math.round(d.temp) + "°");
        v.setTextViewText(R.id.desc, d.desc + (Double.isNaN(d.feels) ? "" : " · feels " + Math.round(d.feels) + "°"));
        v.setTextViewText(R.id.hilo, Double.isNaN(d.hi) ? "" : "High " + Math.round(d.hi) + "° · Low " + Math.round(d.lo) + "°");
        v.setTextViewText(R.id.wind, Math.round(d.wind * f) + " " + u + " " + compass(d.dir));
        v.setTextViewText(R.id.gust, "gusts " + Math.round(d.gust * f));
        v.setTextColor(R.id.wind, windColour(d.wind));
        v.setImageViewBitmap(R.id.arrow, arrow(c, d.dir, windColour(d.wind)));
        v.setTextViewText(R.id.rain, Weather.rainLine(d));

        // size the bar chart to the widget so it stays sharp
        Bundle o = mgr.getAppWidgetOptions(id);
        float dens = c.getResources().getDisplayMetrics().density;
        int wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        int hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150);
        int w = Math.max(200, Math.round((wDp - 26) * dens));
        int h = Math.max(Math.round(26 * dens), Math.round((hDp - 100) * dens));
        h = Math.min(h, Math.round(110 * dens));
        v.setImageViewBitmap(R.id.bars, bars(d, w, h, dens));

        mgr.updateAppWidget(id, v);
    }

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

    /** Arrow pointing the way the wind is blowing (same as the app's compass). */
    static Bitmap arrow(Context c, double dir, int colour) {
        int s = Math.round(34 * c.getResources().getDisplayMetrics().density);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        if (Double.isNaN(dir)) return b;
        Canvas cv = new Canvas(b);
        cv.scale(s / 24f, s / 24f);
        cv.rotate((float) ((dir + 180) % 360), 12, 12);
        Path p = new Path();
        p.moveTo(12, 2); p.lineTo(18, 14); p.lineTo(13.2f, 12); p.lineTo(13.2f, 22);
        p.lineTo(10.8f, 22); p.lineTo(10.8f, 12); p.lineTo(6, 14); p.close();
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(colour);
        cv.drawPath(p, pt);
        return b;
    }

    /** 12 bars, one per 15 minutes, with time labels underneath. */
    static Bitmap bars(Weather.Data d, int w, int h, float dens) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFF8FA3C8);
        text.setTextSize(10 * dens);
        float labelH = 12 * dens;
        float top = 2 * dens, base = h - labelH - 1 * dens;
        int n = Weather.SLOTS;
        float slot = (float) w / n, gap = Math.max(2, slot * 0.18f);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(0x33FFFFFF);
        line.setStrokeWidth(Math.max(1, dens));
        cv.drawLine(0, base, w, base, line);

        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < n; i++) {
            double mm = d.rain[i];
            float x0 = i * slot + gap / 2, x1 = (i + 1) * slot - gap / 2;
            if (Double.isNaN(mm) || mm <= 0) {
                bar.setColor(0x22FFFFFF);
                cv.drawRoundRect(new RectF(x0, base - 2 * dens, x1, base), dens, dens, bar);
            } else {
                double rate = mm * 4;                               // mm per hour
                float frac = (float) Math.min(1, Math.sqrt(rate / 4)); // 4 mm/h fills the bar
                float y = base - Math.max(3 * dens, frac * (base - top));
                bar.setColor(rate < 0.5 ? 0xFF93C5FD : rate < 2 ? 0xFF60A5FA : rate < 4 ? 0xFF3B82F6 : 0xFF6366F1);
                cv.drawRoundRect(new RectF(x0, y, x1, base), 2 * dens, 2 * dens, bar);
            }
        }
        for (int i = 0; i < n; i += 4) {
            String t = i == 0 ? "now" : d.times[i];
            if (t == null || t.isEmpty()) continue;
            float x = i * slot + gap / 2;
            cv.drawText(t, x, h - 2 * dens, text);
        }
        return b;
    }
}
