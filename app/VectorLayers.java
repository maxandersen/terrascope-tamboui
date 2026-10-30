import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.canvas.Context;
import dev.tamboui.widgets.canvas.shapes.Line;
import dev.tamboui.widgets.canvas.shapes.Points;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*
 * The vector overlays. Each is small and self-contained; adding a layer is
 * adding a class here and one entry in Terrascope's layer list.
 */

/** Shared polyline draw with an antimeridian-seam guard. */
final class Polyline {
    private Polyline() { }
    static void draw(Context ctx, List<double[]> lines, Color color) {
        for (double[] ln : lines)
            for (int i = 0; i + 3 < ln.length; i += 2) {
                if (Math.abs(ln[i + 2] - ln[i]) > 180) continue;
                ctx.draw(new Line(ln[i], ln[i + 1], ln[i + 2], ln[i + 3], color));
            }
    }
}

/*
 * Label collision placement (basemap/layer.py): place highest priority first,
 * keep a label only if its span (+/-1 col padding, rows y-1..y+1) doesn't
 * overlap one already placed. Crowded views thin themselves out instead of
 * piling names up. Shared by the city and airport layers.
 */
final class LabelPlacer {
    private final Map<Integer, List<int[]>> occupied = new java.util.HashMap<>();
    private final java.util.Set<String> names = new java.util.HashSet<>();
    private static final int PAD = 1;

    boolean place(View view, double lon, double lat, String text) {
        if (!names.add(text)) return false;              // dedup (country + same-name city)
        int[] c = view.cellOf(lon, lat);
        if (c == null) return false;
        int x = c[0], y = c[1], len = text.length();
        if (x + len > view.cols) return false;           // would run off the edge
        int start = x - PAD, end = x + len + PAD;
        for (int row = y - 1; row <= y + 1; row++)
            for (int[] seg : occupied.getOrDefault(row, List.of()))
                if (start < seg[1] && seg[0] < end) return false;
        occupied.computeIfAbsent(y, k -> new java.util.ArrayList<>()).add(new int[]{x, x + len});
        return true;
    }
}

/** Country coastline outlines. */
final class CoastlineLayer extends AbstractLayer {
    private final MapData data;
    CoastlineLayer(MapData data) { super(true); this.data = data; }
    public char key() { return 'o'; }
    public String label() { return "coast"; }
    public void draw(Context ctx, View view) { Polyline.draw(ctx, data.countryRings, Color.GREEN); }
}

/** Admin-1 province borders, shown only when zoomed in. */
final class ProvinceLayer extends AbstractLayer {
    private final MapData data;
    private final double zoomMin;
    ProvinceLayer(MapData data, Config cfg) { super(true); this.data = data; this.zoomMin = cfg.provinceMin; }
    public char key() { return 'p'; }
    public String label() { return "provinces"; }
    public void draw(Context ctx, View view) {
        if (view.zoom >= zoomMin) Polyline.draw(ctx, data.provinceLines, Color.DARK_GRAY);
    }
}

/** Cities: dots above a zoom-dependent population floor, biggest ones labelled. */
final class CityLayer extends AbstractLayer {
    private final MapData data;
    private final Config cfg;
    private final ReferenceData ref;
    CityLayer(MapData data, Config cfg, ReferenceData ref) { super(true); this.data = data; this.cfg = cfg; this.ref = ref; }
    public char key() { return 'c'; }
    public String label() { return "cities"; }

    private record Cand(double lon, double lat, String text, double prio, Color color, boolean dot) { }

    public void draw(Context ctx, View view) {
        long popFloor = cfg.cityPopFloor(view.zoom);
        List<Cand> cands = new ArrayList<>();
        // Country ISO codes at world zoom -- highest priority, ranked by importance.
        if (view.zoom < 3)
            for (ReferenceData.Country c : ref.countries)
                if (view.contains(c.lon(), c.lat()))
                    cands.add(new Cand(c.lon(), c.lat(), c.code(), 1e12 + c.size(), Color.WHITE, false));
        // Cities: capitals boosted, otherwise by population.
        for (MapData.City c : data.cities) {
            if (c.pop() < popFloor && !c.capital()) continue;
            if (!view.contains(c.lon(), c.lat())) continue;
            cands.add(new Cand(c.lon(), c.lat(), " " + c.name(), c.pop() + (c.capital() ? 5e8 : 0),
                c.capital() ? Color.WHITE : Color.GRAY, true));
        }
        cands.sort((a, b) -> Double.compare(b.prio(), a.prio()));   // highest priority first

        LabelPlacer placer = new LabelPlacer();
        List<double[]> dots = new ArrayList<>();
        for (Cand c : cands) {
            if (c.dot()) dots.add(new double[]{c.lon(), c.lat()});
            if (placer.place(view, c.lon(), c.lat(), c.text()))      // keep only if it fits w/o collision
                ctx.print(c.lon(), c.lat(), Span.styled(c.text(), Style.EMPTY.fg(c.color())));
        }
        if (!dots.isEmpty()) ctx.draw(new Points(dots.toArray(new double[0][]), Color.MAGENTA));
    }
}

/** Live OpenSky aircraft as directional arrows coloured by category. */
final class FlightLayer extends AbstractLayer {
    private static final String URL = "https://opensky-network.org/api/states/all";
    record Flight(double lon, double lat, double heading, int cat, String call,
                  double alt, double speed, double vrate, String squawk, String icao, String country) { }
    private volatile List<Flight> flights = List.of();
    private volatile String status = "";                  // "", "rate-limited", "offline"
    private volatile String filter = "";                  // callsign / ICAO / country substring
    FlightLayer() { super(true); }
    void setFilter(String s) { filter = s == null ? "" : s.toLowerCase(); }
    boolean matches(Flight f) {
        return filter.isEmpty() || f.call().toLowerCase().contains(filter)
            || f.icao().toLowerCase().contains(filter) || f.country().toLowerCase().contains(filter);
    }
    long matchCount() { return flights.stream().filter(this::matches).count(); }
    public char key() { return 'f'; }
    public String label() {
        if (!enabled()) return "flights";
        return status.isEmpty() ? "flights:" + flights.size() : "flights(" + status + ")";
    }
    List<Flight> flights() { return flights; }

    public void start() {
        Json.pollRaw(URL, "flights", 30, res -> {
            if (res.status() == 429) { status = "rate-limited"; return; }       // OpenSky anon cap
            if (res.status() != 200 || res.body().isEmpty()) { status = "offline"; return; }
            if (res.cached()) status = "cached";
            var states = com.google.gson.JsonParser.parseString(res.body()).getAsJsonObject().get("states");
            List<Flight> fs = new ArrayList<>();
            if (states != null && states.isJsonArray())
                for (var s : states.getAsJsonArray()) {
                    var a = s.getAsJsonArray();          // OpenSky state vector
                    if (a.get(5).isJsonNull() || a.get(6).isJsonNull()) continue;
                    fs.add(new Flight(a.get(5).getAsDouble(), a.get(6).getAsDouble(),
                        d(a, 10), a.size() > 17 && !a.get(17).isJsonNull() ? a.get(17).getAsInt() : 0,
                        str(a, 1).trim(), d(a, 7), d(a, 9), d(a, 11), str(a, 14), str(a, 0), str(a, 2)));
                }
            flights = fs; if (!res.cached()) status = "";
        });
    }
    private static double d(com.google.gson.JsonArray a, int i) {
        return i < a.size() && !a.get(i).isJsonNull() ? a.get(i).getAsDouble() : Double.NaN;
    }
    private static String str(com.google.gson.JsonArray a, int i) {
        return i < a.size() && !a.get(i).isJsonNull() ? a.get(i).getAsString() : "";
    }

    // heading 0=N; 8 compass arrows. Unknown heading -> filled dot (like Python).
    private static final String[] ARROWS = {"\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196"};

    // Full category model (settings.py): emergency squawk, then ADS-B category
    // code, else a guess from speed/altitude. 8 categories, exact colours.
    static final List<String> CATEGORY_ORDER = List.of(
        "emergency", "heavy", "airliner", "regional", "light", "helicopter", "fast", "other");
    static final Map<String, Color> COLORS = Map.of(
        "emergency", hex("ff3b3b"), "heavy", hex("c77dff"), "airliner", hex("41aee1"),
        "regional", hex("2ec4b6"), "light", hex("6bcb77"), "helicopter", hex("ffd93d"),
        "fast", hex("ff9f43"), "other", hex("9aa7b4"));
    static String label(String cat) { return cat.equals("fast") ? "fast/mil" : cat; }
    private static Color hex(String h) {
        return Color.rgb(Integer.parseInt(h.substring(0, 2), 16),
            Integer.parseInt(h.substring(2, 4), 16), Integer.parseInt(h.substring(4, 6), 16));
    }

    static String category(Flight f) {
        String sq = f.squawk();
        if (sq.equals("7500") || sq.equals("7600") || sq.equals("7700")) return "emergency";
        String byCode = switch (f.cat()) {          // OpenSky ADS-B emitter category
            case 2, 12 -> "light"; case 3 -> "regional"; case 4 -> "airliner";
            case 5, 6 -> "heavy"; case 7 -> "fast"; case 8 -> "helicopter";
            case 9, 10, 11, 14 -> "other"; default -> null;
        };
        if (byCode != null) return byCode;
        double sp = f.speed();                      // guess from velocity (m/s) + baro altitude (m)
        if (Double.isNaN(sp)) return "other";
        if (sp < 95) return "light";
        if (sp < 150 && (Double.isNaN(f.alt()) || f.alt() < 7000)) return "regional";
        return "airliner";
    }
    static Color categoryColor(Flight f) { return COLORS.get(category(f)); }

    private static final double ARROW_ZOOM = 8;   // default.yaml: dots below, heading arrows above
    public void draw(Context ctx, View view) {
        if (view.zoom >= ARROW_ZOOM) {            // zoomed in: heading arrows (one glyph per plane)
            for (Flight f : flights) {
                if (!view.contains(f.lon(), f.lat()) || !matches(f)) continue;
                String arrow = Double.isNaN(f.heading()) ? "\u25cf"
                    : ARROWS[((int) Math.round(f.heading() / 45) % 8 + 8) % 8];
                ctx.print(f.lon(), f.lat(), Span.styled(arrow, Style.EMPTY.fg(categoryColor(f))));
            }
        } else {                                 // zoomed out: fine braille dots, grouped by colour
            Map<Color, List<double[]>> byColor = new java.util.LinkedHashMap<>();
            for (Flight f : flights) {
                if (!view.contains(f.lon(), f.lat()) || !matches(f)) continue;
                byColor.computeIfAbsent(categoryColor(f), k -> new ArrayList<>())
                    .add(new double[]{f.lon(), f.lat()});
            }
            for (var e : byColor.entrySet())
                ctx.draw(new Points(e.getValue().toArray(new double[0][]), e.getKey()));
        }
    }
}

/** Airports as orange triangles with IATA codes, denser as you zoom in. */
final class AirportLayer extends AbstractLayer {
    private final List<ReferenceData.Airport> airports;
    AirportLayer(List<ReferenceData.Airport> airports) { super(true); this.airports = airports; }
    public char key() { return 'k'; }
    public String label() { return "airports"; }
    private static final Color COLOR = Color.rgb(0xf2, 0xc1, 0x4e);  // default.yaml airport colour
    public void draw(Context ctx, View view) {
        // default.yaml levels [[1,2],[3,4],[5,6],[8,8],[12,10]]; labels from zoom 5.
        double z = view.zoom;
        int rankMax = z >= 12 ? 10 : z >= 8 ? 8 : z >= 5 ? 6 : z >= 3 ? 4 : 2;
        boolean labels = z >= 5;
        LabelPlacer placer = new LabelPlacer();          // thin out colliding IATA labels
        for (ReferenceData.Airport a : airports) {       // already sorted majors-last upstream; iterate as given
            if (a.rank() > rankMax || !view.contains(a.lon(), a.lat())) continue;
            String text = labels && placer.place(view, a.lon(), a.lat(), "\u25b2 " + a.iata())
                ? "\u25b2 " + a.iata() : "\u25b2";
            ctx.print(a.lon(), a.lat(), Span.styled(text, Style.EMPTY.fg(COLOR)));
        }
    }
}

/** Live USGS earthquakes (last 24h), coloured by magnitude. */
final class QuakeLayer extends AbstractLayer {
    private static final String URL =
        "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson";
    private volatile double[][] quakes = new double[0][];   // {lon,lat,mag}
    QuakeLayer() { super(true); }
    public char key() { return 'e'; }
    public String label() { return "quakes"; }
    public void start() {
        Json.pollAsync(URL, "quakes", 120, root -> {
            List<double[]> pts = new ArrayList<>();
            for (var f : root.getAsJsonArray("features")) {
                var o = f.getAsJsonObject();
                var c = o.getAsJsonObject("geometry").getAsJsonArray("coordinates");
                var mag = o.getAsJsonObject("properties").get("mag");
                pts.add(new double[]{c.get(0).getAsDouble(), c.get(1).getAsDouble(),
                    mag.isJsonNull() ? 0 : mag.getAsDouble()});
            }
            quakes = pts.toArray(new double[0][]);
        });
    }
    public void draw(Context ctx, View view) {
        double[][] q = quakes;
        band(ctx, q, 5.0, Double.MAX_VALUE, Color.RED);
        band(ctx, q, 3.0, 5.0, Color.YELLOW);
        band(ctx, q, Double.MIN_VALUE, 3.0, Color.DARK_GRAY);
    }
    private void band(Context ctx, double[][] q, double lo, double hi, Color color) {
        List<double[]> b = new ArrayList<>();
        for (double[] p : q) if (p[2] >= lo && p[2] < hi) b.add(new double[]{p[0], p[1]});
        if (!b.isEmpty()) ctx.draw(new Points(b.toArray(new double[0][]), color));
    }
}

/*
 * Day/night as a filled background shade by sun elevation (astronomy.py
 * sun_elevation), with soft twilight bands and a smooth terminator -- not just
 * a line. A BgLayer: darkens the night hemisphere over land/water.
 */
final class DayNightLayer implements BgLayer {
    private boolean enabled = true;
    public char key() { return 'n'; }
    public String label() { return "day/night"; }
    public boolean enabled() { return enabled; }
    public void toggle() { enabled = !enabled; }

    private static final int[] NIGHT = {8, 12, 30};

    public void renderBg(dev.tamboui.buffer.Buffer buffer, dev.tamboui.layout.Rect area, View view) {
        if (!enabled || area.isEmpty()) return;
        var utc = Clock.nowUtc();                    // scrubbable for the time slider
        double[] sp = Astronomy.solarPosition(utc);
        double dec = sp[0], eqTime = sp[1];
        double minutes = utc.getHour() * 60 + utc.getMinute() + utc.getSecond() / 60.0;
        int w = area.width(), h = area.height();
        for (int cy = 0; cy < h; cy++) {
            double lat = view.y1() - (cy + 0.5) / h * view.latSpan();
            double phi = Math.toRadians(lat);
            for (int cx = 0; cx < w; cx++) {
                double lon = view.x0() + (cx + 0.5) / w * view.lonSpan();
                double solarMin = (((minutes + eqTime + 4 * lon) % 1440) + 1440) % 1440;
                double H = Math.toRadians(solarMin / 4 - 180);
                double elev = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1,
                    Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(H)))));
                double f = nightFactor(elev);
                if (f <= 0) continue;                       // day side unchanged
                int ax = area.x() + cx, ay = area.y() + cy;
                var bg = buffer.get(ax, ay).style().bg();
                var base = bg.isPresent() ? bg.get().toRgb() : Color.rgb(8, 18, 38).toRgb();
                buffer.setStyle(new dev.tamboui.layout.Rect(ax, ay, 1, 1), Style.EMPTY.bg(Color.rgb(
                    (int) (base.r() * (1 - f) + NIGHT[0] * f),
                    (int) (base.g() * (1 - f) + NIGHT[1] * f),
                    (int) (base.b() * (1 - f) + NIGHT[2] * f))));
            }
        }
    }

    // Darkening by twilight band: day 0 .. civil/nautical/astro .. full night.
    private static double nightFactor(double elev) {
        if (elev >= 0) return 0;
        if (elev >= -6) return 0.25 + (-elev / 6) * 0.15;    // civil twilight
        if (elev >= -12) return 0.45 + (-elev - 6) / 6 * 0.15;  // nautical
        if (elev >= -18) return 0.60 + (-elev - 12) / 6 * 0.15; // astronomical
        return 0.78;                                          // night
    }
}
