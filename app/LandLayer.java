import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;

/*
 * Filled land as coverage-shaded cell backgrounds -- the Terrascope look:
 * water dark, land green, soft blurred coasts (world.py land_cover + blur). The
 * polygon raster (Graphics2D, even-odd holes for lakes) is the one thing Canvas
 * can't do; here it feeds a per-cell land fraction that's blurred and mapped to
 * a background colour, drawn UNDER the glyph overlays.
 */
final class LandLayer implements BgLayer {
    private final MapData data;
    private boolean enabled = true;
    LandLayer(MapData data) { this.data = data; }

    public char key() { return 'l'; }
    public String label() { return "land"; }
    public boolean enabled() { return enabled; }
    public void toggle() { enabled = !enabled; }

    private static final int[] WATER = {8, 18, 38};
    private static final int[] LAND = {34, 82, 54};

    public void renderBg(Buffer buffer, Rect area, View view) {
        if (!enabled || area.isEmpty()) return;
        int w = area.width(), h = area.height();
        float[][] cov = coverage(view, w, h);
        blur(cov, w, h);                             // soft coasts: wider horizontally
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float c = cov[y][x];
                Color bg = Color.rgb(lerp(0, c), lerp(1, c), lerp(2, c));
                buffer.setStyle(new Rect(area.x() + x, area.y() + y, 1, 1), Style.EMPTY.bg(bg));
            }
    }

    /** Per-cell land fraction: rasterise polygons at 2x4 sub-pixels, average per cell. */
    private float[][] coverage(View view, int w, int h) {
        int pw = w * 2, ph = h * 4;
        double x0 = view.x0(), y1 = view.y1(), lonSpan = view.lonSpan(), latSpan = view.latSpan();
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        for (List<double[]> poly : data.countryPolygons) {
            Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);  // lakes punch holes
            for (double[] ring : poly) {
                for (int i = 0; i + 1 < ring.length; i += 2) {
                    double px = (ring[i] - x0) / lonSpan * pw, py = (y1 - ring[i + 1]) / latSpan * ph;
                    if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
                }
                path.closePath();
            }
            g.fill(path);
        }
        // Punch lakes back out to water (Great Lakes, Caspian, Baikal, Victoria...).
        g.setComposite(java.awt.AlphaComposite.Clear);
        for (List<double[]> lake : data.lakePolygons) {
            Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            for (double[] ring : lake) {
                for (int i = 0; i + 1 < ring.length; i += 2) {
                    double px = (ring[i] - x0) / lonSpan * pw, py = (y1 - ring[i + 1]) / latSpan * ph;
                    if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
                }
                path.closePath();
            }
            g.fill(path);
        }
        g.dispose();
        float[][] cov = new float[h][w];
        for (int cy = 0; cy < h; cy++)
            for (int cx = 0; cx < w; cx++) {
                int on = 0;
                for (int dy = 0; dy < 4; dy++)
                    for (int dx = 0; dx < 2; dx++)
                        if ((img.getRGB(cx * 2 + dx, cy * 4 + dy) >>> 24) > 0) on++;
                cov[cy][cx] = on / 8f;
            }
        return cov;
    }

    // Separable box blur; wider horizontally since cells are ~2x taller than wide.
    private static void blur(float[][] a, int w, int h) {
        box(a, w, h, 2, true);
        box(a, w, h, 1, false);
    }

    private static void box(float[][] a, int w, int h, int r, boolean horizontal) {
        if (horizontal) {
            for (int y = 0; y < h; y++) {
                float[] row = a[y].clone();
                for (int x = 0; x < w; x++) {
                    float s = 0; int n = 0;
                    for (int k = -r; k <= r; k++) { int xx = x + k; if (xx >= 0 && xx < w) { s += row[xx]; n++; } }
                    a[y][x] = s / n;
                }
            }
        } else {
            for (int x = 0; x < w; x++) {
                float[] col = new float[h];
                for (int y = 0; y < h; y++) col[y] = a[y][x];
                for (int y = 0; y < h; y++) {
                    float s = 0; int n = 0;
                    for (int k = -r; k <= r; k++) { int yy = y + k; if (yy >= 0 && yy < h) { s += col[yy]; n++; } }
                    a[y][x] = s / n;
                }
            }
        }
    }

    private static int lerp(int ch, float t) {
        return Math.round(WATER[ch] + (LAND[ch] - WATER[ch]) * t);
    }
}
