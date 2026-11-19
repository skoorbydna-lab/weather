package uk.skoorbydna.rideweather.watch;

import android.graphics.drawable.Icon;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.LongTextComplicationData;
import androidx.wear.watchface.complications.data.MonochromaticImage;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;

/** Best 2-hour daylight window to ride in the next 12 hours. */
public class RideComplicationService extends BaseComplication {

    @Override
    ComplicationData make(ComplicationType type, WData d) {
        String[] w = d == null ? null : d.bestRide();
        String main = w == null ? "–" : w[0];
        String title = w == null ? (d == null ? "Ride" : "Dark") : "Ride";
        String full = d == null ? "Open Ride Weather" : w == null ? "No daylight ride window in the next 12 h" : "Best ride " + w[0] + "–" + w[1];
        MonochromaticImage bike = new MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_bike)).build();
        if (type == ComplicationType.LONG_TEXT) {
            return new LongTextComplicationData.Builder(t(full), t(full)).setMonochromaticImage(bike).setTapAction(open()).build();
        }
        return new ShortTextComplicationData.Builder(t(main), t(full))
                .setTitle(t(title)).setMonochromaticImage(bike).setTapAction(open()).build();
    }

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        String full = "Best ride 13:00–15:00";
        MonochromaticImage bike = new MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_bike)).build();
        if (type == ComplicationType.LONG_TEXT) return new LongTextComplicationData.Builder(t(full), t(full)).setMonochromaticImage(bike).build();
        return new ShortTextComplicationData.Builder(t("13:00"), t(full)).setTitle(t("Ride")).setMonochromaticImage(bike).build();
    }
}
