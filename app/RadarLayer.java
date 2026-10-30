import com.google.gson.JsonObject;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Weather radar from RainViewer as a per-cell background mosaic. The distinct
 * capability vs local polygons: remote Web-Mercator PNG tiles reprojected onto
 * the equirectangular view. Each terminal cell samples its tile pixel, so the
 * radar is full map resolution (a true mosaic), painted as cell backgrounds on
 * top of the land shade and under the glyph overlays.
 *
 * ponytail: one mercator zoom per view, nearest-pixel sample. Good enough for a
 * terminal; bilinear/true tile stitching only if you need pixel accuracy.
 */
final class RadarLayer implements BgLayer {
    private static final String INDEX = "https://api.rainviewer.com/public/weather-maps.json";
    private static final long FRAME_MS = 600, HOLD_LAST_MS = 1800;   // default.yaml radar timings
    private volatile String[] frames = new String[0];  // tile base per past frame, oldest -> newest
    private final Map<String, BufferedImage> tiles = new ConcurrentHashMap<>();
    private boolean enabled = false;                  // network-heavy, off by default
    private boolean animate = false;                  // a = loop frames vs hold the newest
    private int manualIndex = -1;                     // >=0 = scrubbed frame (overrides live/animate)

    public char key() { return 'r'; }
    public String label() { return animate ? "radar▶" : "radar"; }
    public boolean enabled() { return enabled; }
    public void toggle() { enabled = !enabled; }
    void toggleAnimate() { animate = !animate; manualIndex = -1; }
    boolean animating() { return animate; }
    int frameCount() { return frames.length; }
    int frameIndex() { return manualIndex >= 0 ? manualIndex : Math.max(0, frames.length - 1); }
    void scrub(int delta) {                           // step the shown frame; stops animation
        animate = false;
        int n = frames.length; if (n == 0) return;
        int cur = manualIndex >= 0 ? manualIndex : n - 1;
        manualIndex = Math.max(0, Math.min(n - 1, cur + delta));
    }
    String frameLabel() {                             // age of the shown frame, e.g. "-70m"
        int n = frames.length; if (n == 0) return "";
        int idx = frameIndex();
        int mins = (n - 1 - idx) * 10;               // RainViewer past frames are 10 min apart
        return mins == 0 ? "now" : "-" + mins + "m";
    }

    public void start() {
        Json.pollAsync(INDEX, "radar", 120, root -> {
            String host = root.get("host").getAsString();
            var radar = root.getAsJsonObject("radar");
            var past = radar.getAsJsonArray("past");
            var nowcast = radar.has("nowcast") && radar.get("nowcast").isJsonArray()
                ? radar.getAsJsonArray("nowcast") : new com.google.gson.JsonArray();
            String[] f = new String[past.size() + nowcast.size()];
            for (int i = 0; i < past.size(); i++)
                f[i] = host + past.get(i).getAsJsonObject().get("path").getAsString();
            for (int i = 0; i < nowcast.size(); i++)   // future prediction frames, when published
                f[past.size() + i] = host + nowcast.get(i).getAsJsonObject().get("path").getAsString();
            frames = f;
        });
    }

    // Which frame to show now: newest when still, else cycle with a hold on the last.
    private String currentBase() {
        String[] f = frames;
        if (f.length == 0) return null;
        if (manualIndex >= 0) return f[Math.min(manualIndex, f.length - 1)];   // scrubbed
        if (!animate || f.length == 1) return f[f.length - 1];
        long cycle = (f.length - 1) * FRAME_MS + HOLD_LAST_MS;
        long t = System.currentTimeMillis() % cycle;
        int idx = t < (f.length - 1) * FRAME_MS ? (int) (t / FRAME_MS) : f.length - 1;
        return f[idx];
    }

    private static final Color WATER = Color.rgb(8, 18, 38);

    public void renderBg(Buffer buffer, Rect area, View view) {
        if (!enabled) return;
        String base = currentBase();
        if (base == null) return;
        int w = area.width(), h = area.height();
        int z = Math.max(1, Math.min(7, (int) Math.round(Math.log(view.zoom) / Math.log(2)) + 2));
        int n = 1 << z;
        double x0 = view.x0(), y1 = view.y1(), lonSpan = view.lonSpan(), latSpan = view.latSpan();
        for (int cy = 0; cy < h; cy++) {
            // Two samples per cell (top + bottom half) -> half-block = 2x vertical resolution.
            double latTop = y1 - (cy + 0.25) / h * latSpan, latBot = y1 - (cy + 0.75) / h * latSpan;
            double dLon = lonSpan / w, dLat = latSpan / (2 * h);   // half-cell footprint
            for (int cx = 0; cx < w; cx++) {
                double lon = x0 + (cx + 0.5) / w * lonSpan;
                Color top = half(base, z, n, lon, latTop, dLon, dLat);
                Color bot = half(base, z, n, lon, latBot, dLon, dLat);
                if (top == null && bot == null) continue;
                int ax = area.x() + cx, ay = area.y() + cy;
                String sym = buffer.get(ax, ay).symbol();
                boolean hasGlyph = !sym.isEmpty() && !sym.equals(" ") && !sym.equals("\u2580") && !sym.equals("\u2584");
                if (hasGlyph) {                              // keep the overlay glyph, tint its bg
                    buffer.setStyle(new Rect(ax, ay, 1, 1), Style.EMPTY.bg(bot != null ? bot : top));
                    continue;
                }
                Color baseBg = buffer.get(ax, ay).style().bg().orElse(WATER);  // land/water under the half
                if (top != null && bot != null)
                    buffer.setString(ax, ay, "\u2580", Style.EMPTY.fg(top).bg(bot));     // upper half = top
                else if (top != null)
                    buffer.setString(ax, ay, "\u2580", Style.EMPTY.fg(top).bg(baseBg));
                else
                    buffer.setString(ax, ay, "\u2584", Style.EMPTY.fg(bot).bg(baseBg));  // lower half = bottom
            }
        }
    }

    // Supersampled radar over a half-cell footprint: average SSxSS visible
    // intensities, then map to the magma palette -> smooth blended gradients.
    private static final int SS = 2;
    private Color half(String base, int z, int n, double lonC, double latC, double dLon, double dLat) {
        double sum = 0; int cnt = 0;
        for (int i = 0; i < SS; i++)
            for (int j = 0; j < SS; j++) {
                double lon = lonC - dLon / 2 + (i + 0.5) / SS * dLon;
                double lat = latC - dLat / 2 + (j + 0.5) / SS * dLat;
                double t = sampleIntensity(base, z, n, lon, lat);
                if (t >= 0) { sum += t; cnt++; }     // t<0 = transparent (no echo), skip
            }
        if (cnt == 0) return null;
        // Floor at dim purple (magma 0.2) so light precip is purple, never black.
        return magma(0.2 + 0.8 * (sum / cnt));
    }

    // Radar intensity 0..1 at a lon/lat, or -1 if transparent / off-map / not loaded.
    private double sampleIntensity(String base, int z, int n, double lon, double lat) {
        if (lat <= -85 || lat >= 85) return -1;
        double latR = Math.toRadians(lat);
        double yt = (1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n;
        int ty = (int) Math.floor(yt);
        if (ty < 0 || ty >= n) return -1;
        double xt = (((lon + 180) % 360 + 360) % 360) / 360 * n;
        int tx = (int) Math.floor(xt);
        BufferedImage img = tile(base, z, tx, ty);
        if (img == null) return -1;
        int px = (int) ((xt - tx) * img.getWidth()), py = (int) ((yt - ty) * img.getHeight());
        int argb = img.getRGB(Math.min(px, img.getWidth() - 1), Math.min(py, img.getHeight() - 1));
        if ((argb >>> 24) < 40) return -1;
        return intensity(argb >> 16 & 0xff, argb >> 8 & 0xff, argb & 0xff);
    }

    // RainViewer "Universal Blue" RGB -> precipitation intensity 0..1 via dBZ
    // stops (radar.py rgba_to_intensity): nearest colour stop, normalised dBZ.
    private static final int[][] STOPS = {
        {130, 123, 105, -10}, {206, 192, 135, 10}, {136, 221, 238, 15}, {0, 163, 224, 20},
        {0, 119, 170, 25}, {0, 85, 136, 30}, {255, 238, 0, 35}, {255, 170, 0, 40},
        {255, 68, 0, 45}, {193, 0, 0, 50}, {255, 170, 255, 55}, {255, 119, 255, 60}, {255, 255, 255, 65}};
    private static double intensity(int r, int g, int b) {
        int best = 0; long bestD = Long.MAX_VALUE;
        for (int i = 0; i < STOPS.length; i++) {
            long d = (long) (r - STOPS[i][0]) * (r - STOPS[i][0]) + (long) (g - STOPS[i][1]) * (g - STOPS[i][1])
                + (long) (b - STOPS[i][2]) * (b - STOPS[i][2]);
            if (d < bestD) { bestD = d; best = i; }
        }
        if (best == 0) return -1;                                       // dBZ -10 = no echo -> transparent
        return Math.max(0, Math.min(1, (STOPS[best][3] - 10) / 55.0));   // dBZ 10..65 -> 0..1
    }

    // Magma colormap (matplotlib) -> the purple->pink->orange->yellow look.
    private static final int[][] MAGMA = {
        {0, 0, 4}, {40, 11, 84}, {101, 21, 110}, {159, 42, 99},
        {212, 72, 66}, {245, 125, 21}, {250, 193, 39}, {252, 253, 191}};
    private static Color magma(double t) {
        t = Math.max(0, Math.min(1, t)) * (MAGMA.length - 1);
        int i = (int) t; double f = t - i;
        int j = Math.min(i + 1, MAGMA.length - 1);
        return Color.rgb(
            (int) (MAGMA[i][0] + (MAGMA[j][0] - MAGMA[i][0]) * f),
            (int) (MAGMA[i][1] + (MAGMA[j][1] - MAGMA[i][1]) * f),
            (int) (MAGMA[i][2] + (MAGMA[j][2] - MAGMA[i][2]) * f));
    }

    private BufferedImage tile(String base, int z, int x, int y) {
        String id = base + "/" + z + "/" + x + "/" + y;           // keyed per frame, so animation caches all
        BufferedImage cached = tiles.get(id);
        if (cached != null && cached != EMPTY) return cached;
        if (cached == null) {                                     // not requested yet -> fetch
            tiles.put(id, EMPTY);
            fetchAsync(base, z, x, y, id);
        }
        // While this frame's tile loads (new zoom level or next animation frame),
        // show the newest already-loaded frame at the same position instead of a gap.
        return fallback(z, x, y, base);
    }

    private BufferedImage fallback(int z, int x, int y, String skip) {
        String[] f = frames;
        for (int i = f.length - 1; i >= 0; i--) {
            if (f[i].equals(skip)) continue;
            BufferedImage c = tiles.get(f[i] + "/" + z + "/" + x + "/" + y);
            if (c != null && c != EMPTY) return c;
        }
        return null;
    }

    private void fetchAsync(String base, int z, int x, int y, String id) {
        Thread t = new Thread(() -> {
            try {
                // Tiles are immutable per frame path -> cache aggressively (1h).
                byte[] bytes = Http.getBytes(base + "/256/" + z + "/" + x + "/" + y + "/2/1_1.png", 3600);
                BufferedImage img = bytes == null ? null : ImageIO.read(new ByteArrayInputStream(bytes));
                if (img != null) tiles.put(id, img);
            } catch (Exception ignored) { /* retried next frame */ }
        }, "radar-tile");
        t.setDaemon(true);
        t.start();
    }

    private static final BufferedImage EMPTY = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
}
