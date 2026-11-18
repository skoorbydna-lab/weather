package uk.skoorbydna.rideweather;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs about every 15 minutes: rain alert check, warnings once an hour, and a widget refresh. */
public class CheckJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { Alerts.checkRain(getApplicationContext(), false); } catch (Exception ignored) { }
            try { Alerts.checkWarnings(getApplicationContext(), false); } catch (Exception ignored) { }
            try {
                Weather.fetch(getApplicationContext());
                Weather.Data d = Weather.load(getApplicationContext());
                for (int id : RainWidget.ids(getApplicationContext())) RainWidget.draw(getApplicationContext(), id, d, null);
            } catch (Exception ignored) { }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
