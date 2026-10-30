/*
 * Viewport state: center + zoom fully describe the map; lon/lat bounds are
 * derived. Replaces Terrascope's view.py + the pan/zoom/clamp of world.py.
 */
final class View {
    double centerLon = 0, centerLat = 20, zoom = 1;   // zoom 1 = whole world

    final double zoomMin, zoomMax, zoomStep, panFrac;
    int cols = 1, rows = 1;                        // map area cell size, set each frame
    View(Config cfg) {
        zoomMin = cfg.zoomMin; zoomMax = cfg.zoomMax; zoomStep = cfg.zoomStep; panFrac = cfg.panStep;
        zoom = zoomMin;
    }

    // Cell (x,y) of a lon/lat within the map area, matching Canvas label placement,
    // or null if outside the view.
    int[] cellOf(double lon, double lat) {
        if (!contains(lon, lat)) return null;
        return new int[]{
            (int) Math.round((lon - x0()) / lonSpan() * (cols - 1)),
            (int) Math.round((y1() - lat) / latSpan() * (rows - 1))};
    }

    double lonSpan() { return 360 / zoom; }
    double latSpan() { return 180 / zoom; }
    double x0() { return centerLon - lonSpan() / 2; }
    double x1() { return centerLon + lonSpan() / 2; }
    double y0() { return centerLat - latSpan() / 2; }
    double y1() { return centerLat + latSpan() / 2; }

    void panLon(double frac) { centerLon += lonSpan() * frac; clamp(); }
    void panLat(double frac) { centerLat += latSpan() * frac; clamp(); }

    /** Drag pan: moving the content right (positive cols) shifts the view west. */
    void panPixels(double dCols, double dRows, int mapW, int mapH) {
        centerLon -= dCols * lonSpan() / mapW;
        centerLat += dRows * latSpan() / mapH;
        clamp();
    }
    void zoomBy(double f) { zoom = Math.max(zoomMin, Math.min(zoomMax, zoom * f)); clamp(); }
    void reset() { centerLon = 0; centerLat = 20; zoom = zoomMin; }

    void clamp() {
        double lat = latSpan();
        centerLat = Math.max(-90 + lat / 2, Math.min(90 - lat / 2, centerLat));
        if (lonSpan() >= 360) { centerLon = 0; return; }
        if (centerLon > 180) centerLon -= 360;
        if (centerLon < -180) centerLon += 360;
    }

    boolean contains(double lon, double lat) {
        return lon >= x0() && lon <= x1() && lat >= y0() && lat <= y1();
    }
}
