package uk.skoorbydna.rideweather;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** The Ride Weather web app, full screen, with a bridge that feeds the home-screen widget. */
public class MainActivity extends Activity {
    static final String APP_URL = "https://skoorbydna-lab.github.io/weather/";
    static final String APP_HOST = "skoorbydna-lab.github.io";

    private WebView web;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private long pausedAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1E3D);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setTextZoom(100);   // ignore the phone's font size – the app's own S/M/L/XL setting decides
        s.setUserAgentString(s.getUserAgentString() + " RideWeatherApp");

        web.addJavascriptInterface(new Bridge(getApplicationContext(),
                () -> runOnUiThread(this::askBackground),
                () -> runOnUiThread(this::askNotifications),
                msg -> runOnUiThread(() -> web.evaluateJavascript(
                        "window.appAlertResult&&appAlertResult(" + org.json.JSONObject.quote(msg) + ")", null))),
                "RideWeatherAndroid");
        Alerts.channels(this);
        Alerts.schedule(this);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (APP_HOST.equals(u.getHost())) return false;
                // anything else (Met Office, Pushover, links) opens in the browser
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocation()) {
                    callback.invoke(origin, true, false);
                } else {
                    geoCallback = callback;
                    geoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
                }
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(APP_URL + "?v=" + System.currentTimeMillis() / 60000);   // always the newest page
        hideBars();
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (geoCallback != null) {
            geoCallback.invoke(geoOrigin, hasLocation(), false);
            geoCallback = null;
        }
        if (requestCode == 2) RainWidget.requestUpdate(this);
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(this)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
        }
        askBackground();
    }

    /** The widget can only follow you if location is allowed "all the time". Ask once, with a reason. */
    private void askBackground() {
        if (Build.VERSION.SDK_INT < 29 || isFinishing() || !hasLocation()) return;
        if (Weather.hasBackgroundLoc(this)) return;
        SharedPreferences p = Weather.prefs(this);
        if (p.getBoolean("askedBg", false)) return;
        p.edit().putBoolean("askedBg", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Let the widget follow you?")
                .setMessage("So the home-screen widget shows the weather where you are, choose \"Allow all the time\" on the next screen.")
                .setPositiveButton("Continue", (d, w) -> requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 2))
                .setNegativeButton("Not now", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
        web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        RainWidget.requestUpdate(this);   // keep the widget in step whenever you open the app
        // back after a while: get a fresh forecast
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > 10 * 60_000L) {
            web.evaluateJavascript("try{load()}catch(e){}", null);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
