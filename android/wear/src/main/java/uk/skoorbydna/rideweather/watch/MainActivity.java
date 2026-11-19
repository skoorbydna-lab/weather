package uk.skoorbydna.rideweather.watch;

import android.Manifest;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Ride Weather on the watch: now, rain bars for 3 hours, next 12 hours, next 3 days. Turn the crown to scroll. */
public class MainActivity extends Activity {
    static final int MUTED = 0xFF9FB2D6, BLUE = 0xFF7DB8FF;

    private ScrollView scroll;
    private LinearLayout col;
    private float dens;
    private int screenW;
    private boolean loading;
    private View ring;
    private WData current;

    /** move the ring with the clock every minute */
    private void tick() {
        if (ring == null) return;
        ring.invalidate();
        ring.postDelayed(this::tick, 60_000L);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dens = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF000000);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = (int) (screenW * 0.12);
        col.setPadding(side, (int) (screenW * 0.10), side, (int) (screenW * 0.22));
        scroll.addView(col);
        // the rain ring stays fixed round the edge while the page scrolls underneath
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(scroll);
        ring = new View(this) {
            @Override
            protected void onDraw(android.graphics.Canvas cv) {
                WData.drawRing(cv, Math.min(getWidth(), getHeight()), dens, current);
            }
        };
        root.addView(ring);
        setContentView(root);
        tick();
        scroll.requestFocus();

        RefreshJob.schedule(this);
        render(WData.load(this));

        if (!WData.hasLoc(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        } else {
            refresh();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        scroll.requestFocus();
        WData d = WData.load(this);
        if (d == null || System.currentTimeMillis() - d.fetchedAt > 10 * 60_000L) refresh();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        refresh();
    }

    /** The crown scrolls the page. */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            float delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
                    * ViewConfiguration.get(this).getScaledVerticalScrollFactor();
            scroll.scrollBy(0, Math.round(delta));
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    private void refresh() {
        if (loading) return;
        loading = true;
        setStatus("updating…");
        new Thread(() -> {
            String err = null;
            try { WData.fetch(getApplicationContext()); } catch (Exception e) { err = "couldn't update"; }
            final String fe = err;
            runOnUiThread(() -> {
                loading = false;
                render(WData.load(this));
                if (fe != null) setStatus(fe);
                RainTileService.update(getApplicationContext());
            });
        }).start();
    }

    private TextView status;

    private void setStatus(String s) { if (status != null) status.setText(s); }

    // ---------- building the screen ----------
    private int dp(float v) { return Math.round(v * dens); }

    private TextView text(String s, float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void heading(String s) {
        TextView t = text(s, 11, MUTED, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, dp(14), 0, dp(6));
        col.addView(t);
    }

    private LinearLayout.LayoutParams w(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    private void render(WData d) {
        current = d;
        if (ring != null) ring.invalidate();
        col.removeAllViews();

        TextView place = text(d != null ? d.name : "Ride Weather", 14, Color.WHITE, true);
        place.setGravity(Gravity.CENTER);
        place.setSingleLine(true);
        col.addView(place);
        status = text(d != null ? "updated " + new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(d.fetchedAt)) : "loading…", 11, MUTED, false);
        status.setPadding(0, dp(2), 0, dp(4));
        col.addView(status);

        if (d == null) {
            TextView t = text("Getting your forecast…\nAllow location when asked.", 13, Color.WHITE, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(16), 0, 0);
            col.addView(t);
            return;
        }

        // now
        LinearLayout now = row();
        now.setGravity(Gravity.CENTER);
        now.addView(text(d.icon, 26, Color.WHITE, false));
        TextView temp = text(Math.round(d.temp) + "°", 40, Color.WHITE, true);
        temp.setPadding(dp(4), 0, 0, 0);
        now.addView(temp);
        col.addView(now);
        TextView desc = text(d.desc + " · feels " + Math.round(d.feels) + "°", 13, 0xFFDDE7FA, false);
        desc.setGravity(Gravity.CENTER);
        col.addView(desc);
        if (!Double.isNaN(d.hi)) {
            TextView hl = text("High " + Math.round(d.hi) + "° · Low " + Math.round(d.lo) + "°", 12, MUTED, false);
            hl.setPadding(0, dp(2), 0, 0);
            col.addView(hl);
        }

        LinearLayout wind = row();
        wind.setGravity(Gravity.CENTER);
        wind.setPadding(0, dp(8), 0, 0);
        ImageView arrow = new ImageView(this);
        arrow.setImageBitmap(WData.arrow(dp(26), d.dir, WData.windColour(d.wind)));
        wind.addView(arrow, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView ws = text(" " + Math.round(d.wind) + " mph " + WData.compass(d.dir), 16, WData.windColour(d.wind), true);
        wind.addView(ws);
        col.addView(wind);
        col.addView(text("gusts " + Math.round(d.gust) + " mph", 12, MUTED, false));
        TextView hint = text("Ring: next 60 min of rain, from 12 o'clock", 10, MUTED, false);
        hint.setPadding(0, dp(6), 0, 0);
        col.addView(hint);

        // rain next 3 hours
        heading("RAIN · NEXT 3 HOURS");
        TextView rl = text(d.rainLine(), 14, d.wetSoon() ? BLUE : 0xFF4ADE80, true);
        rl.setGravity(Gravity.CENTER);
        col.addView(rl);
        int bw = screenW - 2 * (int) (screenW * 0.12);
        ImageView bars = new ImageView(this);
        bars.setImageBitmap(d.bars(bw, dp(46), dens));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(bw, dp(46));
        bp.topMargin = dp(6);
        col.addView(bars, bp);

        // next 12 hours
        heading("NEXT 12 HOURS");
        for (WData.Hour h : d.hours) {
            LinearLayout r = row();
            r.setPadding(0, dp(3), 0, dp(3));
            r.addView(text(h.time, 13, MUTED, false), w(1.15f));
            r.addView(text(h.icon, 14, Color.WHITE, false), w(0.6f));
            r.addView(text(Math.round(h.temp) + "°", 14, Color.WHITE, true), w(0.8f));
            boolean wet = !Double.isNaN(h.rain) && h.rain >= 0.1;
            r.addView(text(wet ? String.format(Locale.UK, "%.1f", h.rain) : "–", 13, wet ? BLUE : MUTED, wet), w(0.8f));
            ImageView a = new ImageView(this);
            a.setImageBitmap(WData.arrow(dp(14), h.dir, WData.windColour(h.wind)));
            r.addView(a, new LinearLayout.LayoutParams(dp(14), dp(14)));
            TextView wt = text(String.valueOf(Math.round(h.wind)), 13, WData.windColour(h.wind), true);
            wt.setGravity(Gravity.END);
            r.addView(wt, w(0.6f));
            col.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        TextView key = text("mm of rain · wind mph", 10, MUTED, false);
        key.setPadding(0, dp(4), 0, 0);
        col.addView(key);

        // next 3 days
        heading("NEXT 3 DAYS");
        for (WData.Day x : d.days) {
            LinearLayout r = row();
            r.setPadding(0, dp(4), 0, dp(4));
            r.addView(text(x.name, 14, Color.WHITE, true), w(1f));
            r.addView(text(x.icon, 15, Color.WHITE, false), w(0.7f));
            r.addView(text(Math.round(x.max) + "°/" + Math.round(x.min) + "°", 13, Color.WHITE, false), w(1.3f));
            boolean wet = !Double.isNaN(x.rain) && x.rain >= 0.2;
            TextView rr = text(wet ? String.format(Locale.UK, "%.1f mm", x.rain) : "dry", 12, wet ? BLUE : MUTED, false);
            rr.setGravity(Gravity.END);
            r.addView(rr, w(1.2f));
            col.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        if (d.sunrise != null && !d.sunrise.isEmpty()) {
            TextView sun = text("Sunrise " + d.sunrise + " · Sunset " + d.sunset, 11, MUTED, false);
            sun.setPadding(0, dp(10), 0, 0);
            col.addView(sun);
        }

        // buttons
        col.addView(button("Refresh", v -> refresh()));
        if (WData.hasLoc(this) && !WData.hasBgLoc(this)) {
            TextView note = text("Let the tile follow you when the app is closed:", 11, MUTED, false);
            note.setGravity(Gravity.CENTER);
            note.setPadding(0, dp(12), 0, dp(4));
            col.addView(note);
            col.addView(button("Allow location all the time",
                    v -> requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 2)));
        }
    }

    private View button(String label, View.OnClickListener l) {
        TextView t = text(label, 14, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2B4A8A);
        bg.setCornerRadius(dp(22));
        t.setBackground(bg);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(12);
        t.setLayoutParams(p);
        return t;
    }
}
