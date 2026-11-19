package uk.skoorbydna.rideweather.watch;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.RangedValueComplicationData;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;

/** Temperature on a gauge from today's low to today's high, with "feels like". */
public class TempComplicationService extends BaseComplication {

    @Override
    ComplicationData make(ComplicationType type, WData d) {
        String now = d == null ? "--" : Math.round(d.temp) + "°";
        String feels = d == null ? "Temp" : "fl " + Math.round(d.feels) + "°";
        String desc = d == null ? "Open Ride Weather" : "Temperature " + now + ", feels like " + Math.round(d.feels) + "°";
        if (type == ComplicationType.RANGED_VALUE && d != null && !Double.isNaN(d.hi) && !Double.isNaN(d.lo)) {
            float lo = (float) Math.min(d.lo, d.temp), hi = (float) Math.max(d.hi, d.temp);
            if (hi - lo < 1) hi = lo + 1;
            return new RangedValueComplicationData.Builder((float) d.temp, lo, hi, t(desc))
                    .setText(t(now)).setTitle(t(feels)).setTapAction(open()).build();
        }
        return new ShortTextComplicationData.Builder(t(now), t(desc)).setTitle(t(feels)).setTapAction(open()).build();
    }

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        WData d = new WData();
        d.temp = 6; d.feels = 3; d.lo = 2; d.hi = 9;
        return make(type, d);
    }
}
