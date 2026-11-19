package uk.skoorbydna.rideweather.watch;

import android.content.Context;

import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.tiles.EventBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Swipe-to tile: temperature, wind, and the next 3 hours of rain. Tap it to open the full app. */
public class RainTileService extends TileService {

    static void update(Context c) {
        try { TileService.getUpdater(c).requestUpdate(RainTileService.class); } catch (Exception ignored) { }
        RainComplicationService.update(c);
    }

    @Override
    public void onTileAddEvent(EventBuilders.TileAddEvent e) {
        RefreshJob.schedule(this);
    }

    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest req) {
        WData d = WData.load(this);
        // stale or missing: fetch in the background, then ask for a redraw
        if (d == null || System.currentTimeMillis() - d.fetchedAt > 20 * 60_000L) {
            final Context app = getApplicationContext();
            new Thread(() -> {
                try { WData.fetch(app); update(app); } catch (Exception ignored) { }
            }).start();
        }
        LayoutElementBuilders.LayoutElement root = layout(d, screenDp(req.getDeviceConfiguration()));
        String ver = d == null ? "none" : d.fetchedAt + "-" + (System.currentTimeMillis() / 300_000L);
        TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
                .setResourcesVersion(ver)
                .setFreshnessIntervalMillis(5 * 60_000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build();
        return Futures.immediateFuture(tile);
    }

    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(RequestBuilders.ResourcesRequest req) {
        ResourceBuilders.Resources.Builder b = new ResourceBuilders.Resources.Builder().setVersion(req.getVersion());
        WData d = WData.load(this);
        if (d != null) {
            float dens = getResources().getDisplayMetrics().density;
            int as = Math.round(24 * dens), rs = Math.max(Math.round(screenDp(req.getDeviceConfiguration()) * dens), 480);
            b.addIdToImageMapping("ring", image(WData.png(WData.ring(rs, dens, d)), rs, rs));
            b.addIdToImageMapping("arrow", image(WData.png(WData.arrow(as, d.dir, WData.windColour(d.wind))), as, as));
        }
        return Futures.immediateFuture(b.build());
    }

    private static ResourceBuilders.ImageResource image(byte[] png, int w, int h) {
        return new ResourceBuilders.ImageResource.Builder()
                .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
                        .setData(png)
                        .setWidthPx(w)
                        .setHeightPx(h)
                        .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                        .build())
                .build();
    }

    private static LayoutElementBuilders.Text text(String s, float sp, int colour, boolean bold) {
        LayoutElementBuilders.FontStyle.Builder fs = new LayoutElementBuilders.FontStyle.Builder()
                .setSize(DimensionBuilders.sp(sp))
                .setColor(ColorBuilders.argb(colour));
        if (bold) fs.setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD);
        return new LayoutElementBuilders.Text.Builder().setText(s).setFontStyle(fs.build()).setMaxLines(1).build();
    }

    private static LayoutElementBuilders.Spacer gap(float dp) {
        return new LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(dp)).build();
    }

    static float screenDp(androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters dp) {
        try { int w = dp.getScreenWidthDp(); return w > 0 ? w : 192; } catch (Exception e) { return 192; }
    }

    private LayoutElementBuilders.LayoutElement layout(WData d, float screen) {
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER);
        if (d == null) {
            col.addContent(text("Ride Weather", 15, 0xFFFFFFFF, true));
            col.addContent(gap(6));
            col.addContent(text("Tap to load the forecast", 12, 0xFF9FB2D6, false));
        } else {
            String at = new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(d.fetchedAt));
            col.addContent(text(d.name + " · " + at, 12, 0xFF9FB2D6, false));
            col.addContent(gap(2));
            col.addContent(new LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(text(Math.round(d.temp) + "°", 36, 0xFFFFFFFF, true))
                    .addContent(new LayoutElementBuilders.Spacer.Builder().setWidth(DimensionBuilders.dp(10)).build())
                    .addContent(new LayoutElementBuilders.Image.Builder().setResourceId("arrow")
                            .setWidth(DimensionBuilders.dp(24)).setHeight(DimensionBuilders.dp(24)).build())
                    .addContent(new LayoutElementBuilders.Column.Builder()
                            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                            .addContent(text(" " + Math.round(d.wind) + " mph " + WData.compass(d.dir), 14, WData.windColour(d.wind), true))
                            .addContent(text(" gusts " + Math.round(d.gust), 11, 0xFF9FB2D6, false))
                            .build())
                    .build());
            col.addContent(text(d.desc, 12, 0xFFDDE7FA, false));
            col.addContent(gap(6));
            col.addContent(text(d.shortRainLine(), 14, d.wetSoon() ? 0xFF7DB8FF : 0xFF4ADE80, true));

        }
        ModifiersBuilders.Clickable open = new ModifiersBuilders.Clickable.Builder()
                .setId("open")
                .setOnClick(new ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(getPackageName())
                                .setClassName(MainActivity.class.getName())
                                .build())
                        .build())
                .build();
        LayoutElementBuilders.Box.Builder box = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder().setClickable(open).build());
        if (d != null) box.addContent(new LayoutElementBuilders.Image.Builder().setResourceId("ring")
                .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
                .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT).build());
        return box.addContent(col.build()).build();
    }
}
