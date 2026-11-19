package uk.skoorbydna.rideweather.watch;

import android.graphics.drawable.Icon;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.LongTextComplicationData;
import androidx.wear.watchface.complications.data.MonochromaticImage;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;

/** Wind: speed, with an arrow pointing the way it's blowing. */
public class WindComplicationService extends BaseComplication {

    @Override
    ComplicationData make(ComplicationType type, WData d) {
        double w = d == null ? Double.NaN : d.wind, g = d == null ? Double.NaN : d.gust, dir = d == null ? 0 : d.dir;
        String speed = Double.isNaN(w) ? "--" : String.valueOf(Math.round(w));
        String compass = d == null ? "Wind" : WData.compass(dir);
        MonochromaticImage arrow = new MonochromaticImage.Builder(Icon.createWithBitmap(WData.arrow(64, dir, 0xFFFFFFFF))).build();
        String full = d == null ? "Open Ride Weather" : "Wind " + speed + " mph " + compass + ", gusts " + Math.round(g);
        if (type == ComplicationType.LONG_TEXT) {
            return new LongTextComplicationData.Builder(t(full), t(full)).setMonochromaticImage(arrow).setTapAction(open()).build();
        }
        return new ShortTextComplicationData.Builder(t(speed), t(full))
                .setTitle(t(compass)).setMonochromaticImage(arrow).setTapAction(open()).build();
    }

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        WData d = new WData();
        d.wind = 9; d.gust = 18; d.dir = 22;
        return make(type, d);
    }
}
