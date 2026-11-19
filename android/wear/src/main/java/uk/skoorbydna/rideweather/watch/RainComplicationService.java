package uk.skoorbydna.rideweather.watch;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.LongTextComplicationData;
import androidx.wear.watchface.complications.data.MonochromaticImage;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/** Watch-face complication: temperature or when rain starts/stops. */
public class RainComplicationService extends ComplicationDataSourceService {

    static void update(Context c) {
        for (Class<?> k : new Class<?>[]{RainComplicationService.class, WindComplicationService.class,
                TempComplicationService.class, RideComplicationService.class}) {
            try { ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, k)).requestUpdateAll(); } catch (Exception ignored) { }
        }
    }

    private static PlainComplicationText t(String s) { return new PlainComplicationText.Builder(s).build(); }

    private ComplicationData build(ComplicationType type, String main, String title, String longText) {
        MonochromaticImage icon = new MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_rain)).build();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (type == ComplicationType.LONG_TEXT) {
            return new LongTextComplicationData.Builder(t(longText), t(longText))
                    .setTitle(t("Ride Weather")).setMonochromaticImage(icon).setTapAction(open).build();
        }
        return new ShortTextComplicationData.Builder(t(main), t(title + " " + main))
                .setTitle(t(title)).setMonochromaticImage(icon).setTapAction(open).build();
    }

    @Override
    public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        WData d = WData.load(this);
        ComplicationData data;
        if (d == null) {
            data = build(request.getComplicationType(), "--", "Rain", "Open Ride Weather");
        } else {
            String[] c = d.complication();
            data = build(request.getComplicationType(), c[0], c[1], d.shortRainLine() + " · " + Math.round(d.temp) + "°");
        }
        try { listener.onComplicationData(data); } catch (Exception ignored) { }
        // stale? get a fresh forecast in the background
        if (d == null || System.currentTimeMillis() - d.fetchedAt > 20 * 60_000L) {
            final Context app = getApplicationContext();
            new Thread(() -> { try { WData.fetch(app); RainTileService.update(app); } catch (Exception ignored) { } }).start();
        }
    }

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        return build(type, "18:45", "Stops", "Rain until 18:45 · 6°");
    }
}
