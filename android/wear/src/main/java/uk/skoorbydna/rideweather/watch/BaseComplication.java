package uk.skoorbydna.rideweather.watch;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/** Shared bits for the watch-face complications: cached data, tap to open, background refresh when stale. */
public abstract class BaseComplication extends ComplicationDataSourceService {

    abstract ComplicationData make(ComplicationType type, WData d);

    static PlainComplicationText t(String s) { return new PlainComplicationText.Builder(s).build(); }

    PendingIntent open() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        WData d = WData.load(this);
        try { listener.onComplicationData(make(request.getComplicationType(), d)); } catch (Exception ignored) { }
        if (d == null || System.currentTimeMillis() - d.fetchedAt > 20 * 60_000L) {
            final Context app = getApplicationContext();
            new Thread(() -> { try { WData.fetch(app); RainTileService.update(app); } catch (Exception ignored) { } }).start();
        }
    }
}
