package uk.skoorbydna.rideweather;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Morning and evening summary alarms; also re-plans everything after a restart or app update. */
public class SummaryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        final Context app = c.getApplicationContext();
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Alerts.schedule(app);
            return;
        }
        final String kind = intent.getStringExtra("kind");
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                if (kind != null) Alerts.summary(app, kind, false);
            } catch (Exception e) {
                // one retry a few minutes later if the network wasn't ready
                try { Thread.sleep(20_000); Alerts.summary(app, kind, false); } catch (Exception ignored) { }
            } finally {
                Alerts.schedule(app);          // plan tomorrow's
                pr.finish();
            }
        }).start();
    }
}
