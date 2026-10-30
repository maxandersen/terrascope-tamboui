///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS dev.tamboui:tamboui-toolkit:0.7.0-SNAPSHOT
//DEPS dev.tamboui:tamboui-jline3-backend:0.7.0-SNAPSHOT
//DEPS dev.tamboui:tamboui-widgets:0.7.0-SNAPSHOT
//DEPS dev.tamboui:tamboui-image:0.7.0-SNAPSHOT
//DEPS com.google.code.gson:gson:2.13.1
//SOURCES View.java Layer.java MapData.java Astronomy.java Json.java VectorLayers.java LandLayer.java RadarLayer.java Config.java ReferenceData.java Clock.java Http.java Weather.java Assets.java
//FILES reference_data.json default.yaml
//DEPS org.yaml:snakeyaml:2.3

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.BackendFactory;
import dev.tamboui.terminal.Frame;
import dev.tamboui.terminal.Terminal;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.canvas.Canvas;
import dev.tamboui.widgets.canvas.Marker;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.tabs.Tabs;
import dev.tamboui.widgets.tabs.TabsState;
import dev.tamboui.widgets.Clear;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/*
 * Terrascope on TamboUI -- a braille world map with live weather, aircraft and
 * earthquake layers. The shell: loads data, owns the viewport and the layer
 * stack, runs the event loop, routes keys. Each feature lives in its own Layer.
 *
 *   jbang Terrascope.java              interactive (arrows pan, +/- zoom, keys toggle layers)
 *   jbang -Dea Terrascope.java --dump [zoom lon lat]   headless one frame
 */
public final class Terrascope {

    private final Config cfg = new Config();
    private final View view = new View(cfg);
    private final List<BgLayer> bgLayers;         // raster backgrounds (land shade, radar), under overlays
    private final List<Layer> overlays;           // vector layers, drawn on the Canvas
    private final List<Toggleable> toggles;       // everything, for keys & footer
    private boolean running = true;

    // Chrome: preset tabs, a live info panel, a help modal.
    private record Preset(String name, String keys) { }   // keys = layer keys enabled by this tab
    private static final List<Preset> TABS = List.of(
        new Preset("World", "loc"), new Preset("Flights", "lofk"),
        new Preset("Weather", "lor"), new Preset("Seismic", "loe"),
        new Preset("Astro", "lon"), new Preset("All", "locpfenk"));
    private int tab = 0;
    private boolean showPanel = true, showHelp = false, showWelcome = true;
    private boolean searchActive = false;          // '/' text-input for plane search/filter
    private String searchText = "";
    private final RadarLayer radar;                // kept for the animate ('a') toggle
    private final FlightLayer flightLayer;         // kept for plane selection
    private Rect mapArea = new Rect(0, 0, 0, 0);   // last map rect, for click -> lon/lat
    private MapData.City selected;                 // clicked city, shown in the panel
    private FlightLayer.Flight selectedFlight;     // clicked aircraft (Flights tab)
    private volatile Weather.Data weather;         // forecast for the selected city
    private volatile String weatherKey = "";       // which city the forecast is for (de-dupes fetches)
    private int dragX = -1, dragY = -1, pressX, pressY;   // trackpad drag state
    private boolean dragging;
    private final MapData data;

    private Terrascope() throws IOException, InterruptedException {
        data = new MapData(cfg);
        ReferenceData ref = new ReferenceData();
        radar = new RadarLayer();
        flightLayer = new FlightLayer();
        // bg order: land shade -> day/night tint -> radar mosaic (radar on top).
        bgLayers = List.of(new LandLayer(data), new DayNightLayer(), radar);
        // Airports: bundled NE hubs (nicely ranked for low zoom) + OurAirports
        // large/medium (detail incl. Billund). Overlap is harmless (same spot).
        var airports = new ArrayList<>(ref.airports);
        airports.addAll(data.airports);
        airports.sort((a, b) -> b.rank() - a.rank());   // draw majors last -> they win label collisions
        overlays = List.of(
            new CoastlineLayer(data), new ProvinceLayer(data, cfg), new CityLayer(data, cfg, ref),
            flightLayer, new QuakeLayer(), new AirportLayer(airports));   // airports last = on top
        toggles = new ArrayList<>();
        toggles.addAll(bgLayers);
        toggles.addAll(overlays);
        overlays.forEach(Layer::start);           // kick off live-API threads
        radar.start();
        applyTab();
    }

    /** Set each layer's enabled state to match the selected tab's preset. */
    private void applyTab() {
        String on = TABS.get(tab).keys();
        for (Toggleable t : toggles)
            if (t.enabled() != (on.indexOf(t.key()) >= 0)) t.toggle();
    }

    public static void main(String[] args) throws Exception {
        Terrascope app = new Terrascope();
        if (args.length > 0 && args[0].equals("--dump")) {
            // --dump [zoom [lon lat]] [layerKeys...]; numbers are positional, letters toggle.
            List<Double> nums = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                try { nums.add(Double.parseDouble(args[i])); }
                catch (NumberFormatException e) { keys.add(args[i]); }
            }
            if (nums.size() > 0) app.view.zoom = nums.get(0);
            if (nums.size() > 2) { app.view.centerLon = nums.get(1); app.view.centerLat = nums.get(2); }
            app.view.clamp();
            app.showWelcome = false;                      // off unless explicitly requested
            for (String k : keys) {
                switch (k) {                              // chrome pseudo-keys for headless checks
                    case "help" -> { app.showHelp = true; continue; }
                    case "welcome" -> { app.showWelcome = true; continue; }
                    case "nopanel" -> { app.showPanel = false; continue; }
                    case "sel" -> { app.selected = app.nearestCity(app.view.centerLon, app.view.centerLat); continue; }
                    case "anim" -> { if (!app.radar.enabled()) app.radar.toggle(); app.radar.toggleAnimate(); continue; }
                    default -> { if (k.startsWith("tab")) { app.tab = Integer.parseInt(k.substring(3)); app.applyTab(); continue; } }
                }
                for (Toggleable t : app.toggles) if (k.charAt(0) == t.key()) t.toggle();
            }
            Thread.sleep(3500);                         // let the live feeds / tiles land
            app.dump(110, 40);
            return;
        }
        app.run();
    }

    // ---- event loop ----------------------------------------------------

    private void run() throws Exception {
        try (Backend backend = BackendFactory.create()) {
            backend.enableRawMode();
            backend.enterAlternateScreen();
            backend.hideCursor();
            backend.enableMouseCapture();
            Terminal<Backend> terminal = new Terminal<>(backend);
            // No onResize draw: the callback runs on another thread and would race
            // the main loop over the shared diff buffer (NPE in DiffResult.cellAt).
            // terminal.draw() re-checks the size and resizes itself each iteration,
            // and the loop ticks at least every 100ms, so resizes are picked up.
            while (running) {
                terminal.draw(this::ui);
                handleKey(backend);
            }
        }
    }

    private void handleKey(Backend backend) throws IOException {
        int c = backend.read(100);
        if (c == -2) return;
        if (searchActive) { handleSearch(c); return; }
        if (showWelcome) { showWelcome = false; if (c != 27) return; }   // any key dismisses splash
        switch (c) {
            case '/' -> searchActive = true;        // plane search/filter
            case 'q', 'Q', 3 -> running = false;
            case '+', '=' -> view.zoomBy(view.zoomStep);
            case '-', '_' -> view.zoomBy(1 / view.zoomStep);
            case '0' -> view.reset();
            case '\t' -> { tab = (tab + 1) % TABS.size(); applyTab(); }
            case 'i', 'I' -> showPanel = !showPanel;
            case '?' -> showHelp = !showHelp;
            case 'a', 'A' -> { radar.toggleAnimate(); if (radar.animating() && !radar.enabled()) radar.toggle(); }
            case ',', '<' -> scrubTime(-1);           // time slider back
            case '.', '>' -> scrubTime(1);            // time slider forward
            case 27 -> {                                  // ESC [ ... : arrows or SGR mouse
                if (backend.read(0) == '[') {
                    int n = backend.read(0);
                    switch (n) {
                        case 'A' -> view.panLat(view.panFrac);
                        case 'B' -> view.panLat(-view.panFrac);
                        case 'C' -> view.panLon(view.panFrac);
                        case 'D' -> view.panLon(-view.panFrac);
                        case '<' -> readMouse(backend);
                        default -> { }
                    }
                }
            }
            default -> {
                for (Toggleable t : toggles)
                    if (Character.toLowerCase(c) == t.key()) { t.toggle(); break; }
            }
        }
    }

    // SGR mouse: ESC[<b;x;y(M|m). Trackpad: click selects a city, drag pans,
    // two-finger scroll zooms (terminals don't deliver pinch gestures).
    private void readMouse(Backend backend) throws IOException {
        int[] v = {0, 0, 0}; int idx = 0, cur = 0; boolean press = false;
        while (idx < 3) {
            int d = backend.read(0);
            if (d >= '0' && d <= '9') cur = cur * 10 + (d - '0');
            else if (d == ';') { v[idx++] = cur; cur = 0; }
            else { v[idx] = cur; press = (d == 'M'); break; }
        }
        int b = v[0], sx = v[1] - 1, sy = v[2] - 1;
        boolean wheel = (b & 64) != 0, motion = (b & 32) != 0, shift = (b & 4) != 0; int btn = b & 3;

        if (wheel) {                                   // two-finger scroll. low bits = direction.
            int dir = b & 3;                           // 0 up, 1 down, 2 left, 3 right
            if (shift) {                               // Shift+scroll -> zoom
                if (dir == 0) view.zoomBy(view.zoomStep);
                else if (dir == 1) view.zoomBy(1 / view.zoomStep);
            } else switch (dir) {                      // plain scroll -> pan
                case 0 -> view.panLat(view.panFrac);   // up = north
                case 1 -> view.panLat(-view.panFrac);
                case 2 -> view.panLon(-view.panFrac);  // left = west
                case 3 -> view.panLon(view.panFrac);
                default -> { }
            }
            return;
        }
        boolean inMap = mapArea.width() > 0
            && sx >= mapArea.x() && sx < mapArea.x() + mapArea.width()
            && sy >= mapArea.y() && sy < mapArea.y() + mapArea.height();
        if (!press) {                                  // release: a non-drag click selects
            if (!dragging && inMap) selectAt(pressX, pressY);
            dragging = false; dragX = -1;
            return;
        }
        if (btn == 0 && motion) {                       // drag -> pan by the pixel delta
            if (dragX >= 0 && mapArea.width() > 0) {
                view.centerLon -= (sx - dragX) * view.lonSpan() / mapArea.width();
                view.centerLat += (sy - dragY) * view.latSpan() / mapArea.height();
                view.clamp();
                dragging = true;
            }
            dragX = sx; dragY = sy;
        } else if (btn == 0) {                          // initial left press
            pressX = sx; pressY = sy; dragX = sx; dragY = sy; dragging = false;
        }
    }

    private void selectAt(int sx, int sy) {
        if (mapArea.width() == 0) return;
        double lon = view.x0() + (sx - mapArea.x() + 0.5) / mapArea.width() * view.lonSpan();
        double lat = view.y1() - (sy - mapArea.y() + 0.5) / mapArea.height() * view.latSpan();
        if (TABS.get(tab).name().equals("Flights")) selectedFlight = nearestFlight(lon, lat);
        else selected = nearestCity(lon, lat);
    }

    // Plane search / filter: type a callsign, ICAO or country; applied live.
    private void handleSearch(int c) {
        if (c == 13 || c == 10 || c == 27) { searchActive = false; return; }   // Enter / Esc close (filter stays)
        else if (c == 127 || c == 8) { if (!searchText.isEmpty()) searchText = searchText.substring(0, searchText.length() - 1); }
        else if (c >= 32 && c < 127) searchText += (char) c;
        flightLayer.setFilter(searchText);
    }

    // Bottom time slider: radar frames on Weather, the day/night clock elsewhere.
    private void scrubTime(int dir) {
        if (TABS.get(tab).name().equals("Weather")) { if (!radar.enabled()) radar.toggle(); radar.scrub(dir); }
        else Clock.offsetMinutes = Math.max(-720, Math.min(720, Clock.offsetMinutes + dir * 30));
    }

    private FlightLayer.Flight nearestFlight(double lon, double lat) {
        FlightLayer.Flight best = null; double bestD = Double.MAX_VALUE;
        for (FlightLayer.Flight f : flightLayer.flights()) {
            if (!flightLayer.matches(f)) continue;    // only select visible (filtered) planes
            double dx = (f.lon() - lon) * Math.cos(Math.toRadians(lat)), dy = f.lat() - lat;
            double d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = f; }
        }
        return best;
    }

    // Gesture math (drag pan + scroll zoom), independent of a terminal.
    private static void selfCheck() {
        Config cfg = new Config();
        View v = new View(cfg); v.zoom = 4; v.centerLon = 0; v.centerLat = 20;
        v.panPixels(10, 0, 100, 40);            // drag content east -> view moves west
        assert v.centerLon < 0 : "drag-east should move center west: " + v.centerLon;
        v.panPixels(0, 10, 100, 40);            // drag content down -> view moves north
        assert v.centerLat > 20 : "drag-down should move center north: " + v.centerLat;
        double z = v.zoom; v.zoomBy(v.zoomStep);
        assert v.zoom > z : "scroll up should zoom in";
        v.zoomBy(1 / v.zoomStep);
        assert Math.abs(v.zoom - z) < 1e-9 : "scroll down should undo";
        System.out.println("selfcheck OK: drag-pan + scroll-zoom math");
    }

    private MapData.City nearestCity(double lon, double lat) {
        MapData.City best = null; double bestD = Double.MAX_VALUE;
        for (MapData.City c : data.cities) {
            double dx = (c.lon() - lon) * Math.cos(Math.toRadians(lat)), dy = c.lat() - lat;
            double d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = c; }
        }
        return best;
    }

    // ---- rendering -----------------------------------------------------

    private void ui(Frame frame) {
        var rows = Layout.vertical()
            .constraints(Constraint.length(3), Constraint.fill(), Constraint.length(3))
            .split(frame.area());
        renderTabs(frame, rows.get(0));
        Rect mid = rows.get(1);
        if (showPanel) {
            var cols = Layout.horizontal()
                .constraints(Constraint.length(22), Constraint.fill())
                .split(mid);
            frame.renderWidget(panel(), cols.get(0));
            mid = cols.get(1);
        }
        mapArea = mid;
        renderMap(frame.buffer(), mid);
        drawMapChrome(frame.buffer(), mid);
        frame.renderWidget(footer(), rows.get(2));
        if (showHelp) renderHelp(frame);
        if (showWelcome) renderWelcome(frame);
    }

    private void renderWelcome(Frame frame) {
        Rect a = frame.area();
        int w = 52, h = 12;
        Rect r = new Rect(a.x() + (a.width() - w) / 2, a.y() + (a.height() - h) / 2, w, h);
        frame.renderWidget(Clear.clear(), r);
        List<Line> lines = List.of(
            Line.from(Span.raw("A braille terminal world map with live weather,").gray()),
            Line.from(Span.raw("aircraft and earthquake layers — on TamboUI.").gray()),
            Line.from(Span.raw("")),
            Line.from(Span.raw("  ←↑↓→").yellow(), Span.raw(" pan   ").dim(), Span.raw("+/-").yellow(), Span.raw(" zoom   ").dim(), Span.raw("Tab").yellow(), Span.raw(" views").dim()),
            Line.from(Span.raw("  click").yellow(), Span.raw(" a city   ").dim(), Span.raw("?").yellow(), Span.raw(" help   ").dim(), Span.raw("q").yellow(), Span.raw(" quit").dim()),
            Line.from(Span.raw("")),
            Line.from(Span.raw("      press any key to begin").cyan().bold()));
        frame.renderWidget(Paragraph.builder().text(Text.from(lines.toArray(new Line[0])))
            .block(Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(Color.CYAN))
                .title(Title.from(Line.from(Span.raw(" Terrascope · TamboUI ").cyan().bold()))).build())
            .build(), r);
    }

    private void renderTabs(Frame frame, Rect area) {
        List<dev.tamboui.text.Line> titles = new ArrayList<>();
        for (Preset p : TABS) titles.add(Line.from(Span.raw(" " + p.name() + " ")));
        Tabs tabs = Tabs.builder()
            .titles(titles).divider(Span.raw("\u2502"))
            .highlightStyle(Style.EMPTY.fg(Color.CYAN).reversed())
            .block(Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(Color.DARK_GRAY))
                .title(Title.from(Line.from(Span.raw(" Terrascope ").cyan().bold(),
                    Span.raw("\u00b7 TamboUI ").dim(), Span.raw("[Tab] view  [?] help ").dim())))
                .build())
            .build();
        frame.renderStatefulWidget(tabs, area, new TabsState(tab));
    }

    private Paragraph panel() {
        List<Line> lines = switch (TABS.get(tab).name()) {
            case "Astro" -> panelAstro();
            case "Flights" -> panelFlights();
            case "Weather" -> panelWeather();
            default -> panelDefault();
        };
        return Paragraph.builder().text(Text.from(lines.toArray(new Line[0])))
            .block(Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(Color.DARK_GRAY))
                .title(Title.from(Line.from(Span.raw(" " + TABS.get(tab).name() + " ").dim()))).build())
            .build();
    }

    private static Line kv(String k, String v) {
        return Line.from(Span.styled(String.format("%-8s", k), Style.EMPTY.fg(Color.GRAY)), Span.raw(v).white());
    }
    private static Line head(String s) { return Line.from(Span.raw(s).cyan().bold()); }
    private static Line blank() { return Line.from(Span.raw("")); }

    private List<Line> panelDefault() {
        List<Line> lines = new ArrayList<>(List.of(
            kv("View", TABS.get(tab).name()), kv("Zoom", String.format("%.1fx", view.zoom)),
            kv("Lon", String.format("%.1f", view.centerLon)), kv("Lat", String.format("%.1f", view.centerLat)),
            blank(), Line.from(Span.raw("Layers").dim())));
        for (Toggleable t : toggles)
            lines.add(Line.from(
                Span.raw(t.enabled() ? " \u25cf " : " \u25cb ").fg(t.enabled() ? Color.GREEN : Color.DARK_GRAY),
                Span.styled(t.label(), Style.EMPTY.fg(t.enabled() ? Color.GRAY : Color.DARK_GRAY))));
        if (selected != null) {
            lines.add(blank()); lines.add(Line.from(Span.raw("Selected").dim()));
            lines.add(Line.from(Span.raw(" " + selected.name()).cyan().bold()));
            if (!selected.country().isEmpty()) lines.add(Line.from(Span.raw(" " + selected.country()).gray()));
            lines.add(Line.from(Span.raw(selected.capital() ? " capital" : " city").dim(),
                Span.raw("  " + fmtPop(selected.pop())).white()));
        }
        return lines;
    }

    private List<Line> panelAstro() {
        var utc = Clock.nowUtc();                    // scrubbable
        var local = utc.withZoneSameInstant(java.time.ZoneId.systemDefault());
        double[] sp = Astronomy.solarPosition(utc);
        double dec = sp[0], eq = sp[1];
        double elev = Astronomy.sunElevation(view.centerLat, view.centerLon, utc, dec, eq);
        double[] rs = Astronomy.sunriseSunset(view.centerLat, view.centerLon, dec, eq);
        double[] sub = Astronomy.subsolar(utc, dec, eq);
        Astronomy.Moon m = Astronomy.moon(utc);
        String off = local.getOffset().getId().replace(":", "");
        return new ArrayList<>(List.of(
            head("CLOCK"),
            kv("DATE", utc.toLocalDate().toString()),
            kv("UTC", utc.toLocalTime().withNano(0).toString()),
            kv("LOCAL", local.toLocalTime().withNano(0) + " " + (off.equals("Z") ? "+0000" : off)),
            kv("DAY", local.getDayOfYear() + " of year"),
            blank(), head("SUN"),
            kv("CENTRE", Astronomy.hemisphere(view.centerLat, 'N', 'S') + " " + Astronomy.hemisphere(view.centerLon, 'E', 'W')),
            kv("PHASE", String.format("%s (%+.1f\u00b0)", Astronomy.sunPhase(elev), elev)),
            kv("RISE", rs == null ? "\u2014" : Astronomy.hhmm(rs[0]) + " UTC"),
            kv("SET", rs == null ? "\u2014" : Astronomy.hhmm(rs[1]) + " UTC"),
            kv("LENGTH", rs == null ? (elev > 0 ? "polar day" : "polar night") : dayLen(rs)),
            kv("SOLAR", Astronomy.hhmm(Astronomy.solarMinutes(view.centerLon, utc, eq))),
            kv("SUBSOLAR", Astronomy.hemisphere(sub[1], 'N', 'S') + " " + Astronomy.hemisphere(sub[0], 'E', 'W')),
            blank(), head("MOON"),
            kv("PHASE", m.name()),
            kv("LIT", Math.round(m.illumination() * 100) + "%"),
            kv("AGE", String.format("%.1f d", m.age())),
            kv("NEW IN", String.format("%.1f d", m.toNew())),
            kv("FULL IN", String.format("%.1f d", m.toFull()))));
    }
    private static String dayLen(double[] rs) {
        int mins = (int) Math.round(((rs[1] - rs[0]) % 1440 + 1440) % 1440);
        return mins / 60 + "h" + String.format("%02dm", mins % 60);
    }

    private List<Line> panelFlights() {
        List<Line> lines = new ArrayList<>(List.of(head("AIRCRAFT")));
        FlightLayer.Flight f = selectedFlight;
        if (f == null) {
            lines.add(Line.from(Span.raw("click a plane").dim()));
        } else {
            lines.add(kv("CALLSIGN", f.call().isEmpty() ? "\u2014" : f.call()));
            lines.add(kv("TYPE", FlightLayer.label(FlightLayer.category(f))));
            lines.add(kv("COUNTRY", f.country()));
            lines.add(kv("ALT", nz(f.alt()) ? Math.round(f.alt() * 3.281) + " ft" : "\u2014"));
            lines.add(kv("SPEED", nz(f.speed()) ? Math.round(f.speed() * 1.944) + " kt" : "\u2014"));
            lines.add(kv("HEADING", nz(f.heading()) ? Math.round(f.heading()) + "\u00b0" : "\u2014"));
            lines.add(kv("VRATE", nz(f.vrate()) ? String.format("%+.0f ft/m", f.vrate() * 196.85) : "\u2014"));
            lines.add(kv("POSITION", String.format("%.2f, %.2f", f.lat(), f.lon())));
            lines.add(kv("ICAO24", f.icao()));
            lines.add(kv("SQUAWK", f.squawk().isEmpty() ? "\u2014" : f.squawk()));
        }
        lines.add(blank());
        lines.add(Line.from(Span.raw("TOTAL ").dim(), Span.raw(String.valueOf(flightLayer.flights().size())).cyan().bold()));
        lines.add(blank());
        lines.add(Line.from(Span.raw(searchActive ? "SEARCH ▸ " : "/ filter  ").dim(),
            Span.raw(searchText + (searchActive ? "\u2588" : "")).white()));
        if (!searchText.isEmpty())
            lines.add(Line.from(Span.raw(flightLayer.matchCount() + " match").cyan()));
        // Top countries among the flights that match the current filter (so the
        // list narrows as you type a country / callsign / ICAO in /).
        var counts = new java.util.HashMap<String, Integer>();
        for (FlightLayer.Flight fl : flightLayer.flights())
            if (!fl.country().isEmpty() && flightLayer.matches(fl)) counts.merge(fl.country(), 1, Integer::sum);
        if (!counts.isEmpty()) {
            lines.add(blank());
            lines.add(Line.from(Span.raw("COUNTRIES").dim()));
            counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue()).limit(7)
                .forEach(e -> lines.add(Line.from(
                    Span.raw(String.format(" %-13s ", trunc(e.getKey(), 12)))
                        .fg(e.getKey().toLowerCase().contains(searchText.toLowerCase()) && !searchText.isEmpty() ? Color.CYAN : Color.GRAY),
                    Span.raw(String.valueOf(e.getValue())).white())));
        }
        return lines;
    }
    private static String trunc(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "\u2026"; }
    private static boolean nz(double d) { return !Double.isNaN(d); }

    private List<Line> panelWeather() {
        List<Line> lines = new ArrayList<>(List.of(head("CITY")));
        if (selected == null) { lines.add(Line.from(Span.raw("click a city").dim())); return lines; }
        ensureWeather(selected);
        lines.add(Line.from(Span.raw(" " + selected.name()).cyan().bold()));
        if (!selected.country().isEmpty()) lines.add(Line.from(Span.raw(" " + selected.country()).gray()));
        lines.add(kv("TYPE", selected.capital() ? "capital" : "city"));
        lines.add(kv("POP", fmtPop(selected.pop())));
        lines.add(kv("POS", String.format("%.2f, %.2f", selected.lat(), selected.lon())));
        Weather.Data w = weather;
        boolean ready = w != null && weatherKey.equals(cityKey(selected));
        if (!ready) { lines.add(blank()); lines.add(Line.from(Span.raw("loading forecast\u2026").dim())); return lines; }
        lines.add(kv("TEMP", String.format("%.1f\u00b0C", w.temp())));
        lines.add(kv("SKY", Weather.condition(w.code())));
        lines.add(kv("WIND", String.format("%.0f km/h@%.0f\u00b0", w.wind(), w.windDir())));
        lines.add(blank());
        // Common temperature scale across all days so the graphs are comparable.
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Weather.Day d : w.days()) { lo = Math.min(lo, d.tmin()); hi = Math.max(hi, d.tmax()); }
        var today = java.time.LocalDate.now().toString();
        for (Weather.Day d : w.days()) {
            String dow = dayName(d.date());
            lines.add(Line.from(Span.raw((d.date().equals(today) ? "TODAY " : dow + " ")).cyan().bold(),
                Span.raw(d.date().substring(5)).dim()));
            lines.add(Line.from(Span.raw(Weather.icon(d.code()) + " ").white(),
                Span.styled(Weather.condition(d.code()), Style.EMPTY.fg(Color.GRAY))));
            lines.add(Line.from(
                Span.raw(String.format("\u25bc%.0f\u00b0 ", d.tmin())).fg(Color.CYAN),
                Span.raw(String.format("\u25b2%.0f\u00b0 ", d.tmax())).fg(Color.rgb(230, 150, 60)),
                Span.styled(sparkline(d.hourly(), lo, hi), Style.EMPTY.fg(Color.rgb(230, 110, 140)))));
            lines.add(Line.from(
                Span.raw(String.format(" \u2602%d%%  ", d.precip())).dim(),
                Span.raw(String.format("\u2248%.0f g%.0f", d.wind(), d.gust())).fg(Color.GRAY)));
            lines.add(Line.from(Span.raw(String.format(" \u2191%s \u2193%s UV%.0f", d.sunrise(), d.sunset(), d.uv())).dim()));
            lines.add(blank());
        }
        return lines;
    }

    private static final String BLOCKS = " \u2581\u2582\u2583\u2584\u2585\u2586\u2587\u2588";
    private static String sparkline(double[] vals, double lo, double hi) {
        if (vals == null || hi <= lo) return "";
        StringBuilder sb = new StringBuilder();
        for (double v : vals) {
            int lvl = (int) Math.round((v - lo) / (hi - lo) * (BLOCKS.length() - 1));
            sb.append(BLOCKS.charAt(Math.max(0, Math.min(BLOCKS.length() - 1, lvl))));
        }
        return sb.toString();
    }
    private static String dayName(String iso) {
        try { return java.time.LocalDate.parse(iso).getDayOfWeek()
            .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH); }
        catch (Exception e) { return iso.substring(5); }
    }

    private static String cityKey(MapData.City c) { return c.name() + "," + c.lat(); }
    private void ensureWeather(MapData.City c) {
        String key = cityKey(c);
        if (key.equals(weatherKey)) return;         // already fetched / fetching
        weatherKey = key; weather = null;
        Thread t = new Thread(() -> {
            Weather.Data w = Weather.fetch(c.lat(), c.lon());
            if (key.equals(weatherKey)) weather = w;  // ignore if selection moved on
        }, "weather");
        t.setDaemon(true); t.start();
    }

    private static String fmtPop(long p) {
        if (p >= 1_000_000) return String.format("%.1fM", p / 1e6);
        if (p >= 1_000) return String.format("%dk", p / 1000);
        return String.valueOf(p);
    }

    private void renderHelp(Frame frame) {
        Rect a = frame.area();
        int w = 54, h = 20;
        Rect r = new Rect(a.x() + (a.width() - w) / 2, a.y() + (a.height() - h) / 2, w, h);
        frame.renderWidget(Clear.clear(), r);
        List<Line> lines = new ArrayList<>();
        String[][] keys = {
            {"\u2190\u2191\u2193\u2192", "pan the map"}, {"+ / -", "zoom in / out"}, {"0", "reset view"},
            {"Tab", "next view preset"}, {"i", "toggle info panel"}, {"?", "toggle this help"},
            {"l o p c", "land coast provinces cities"}, {"r f e n", "radar flights quakes day/night"},
            {"a", "animate radar frames (loop)"},
            {", .", "time slider (radar frames / terminator)"},
            {"/", "search planes (callsign / ICAO / country)"},
            {"mouse", "scroll pan · shift+scroll zoom · drag pan · click select"},
            {"q", "quit"}};
        for (String[] k : keys)
            lines.add(Line.from(Span.styled(String.format(" %-9s ", k[0]), Style.EMPTY.fg(Color.YELLOW)),
                Span.raw(k[1]).dim()));
        frame.renderWidget(Paragraph.builder().text(Text.from(lines.toArray(new Line[0])))
            .block(Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(Color.CYAN))
                .title(Title.from(Line.from(Span.raw(" Keybindings ").cyan().bold()))).build())
            .build(), r);
    }

    private void renderMap(Buffer buffer, Rect area) {
        Block block = Block.builder()
            .borders(Borders.ALL).borderType(BorderType.ROUNDED)
            .borderStyle(Style.EMPTY.fg(Color.BLUE))
            .title(Title.from(Line.from(Span.raw(" Terrascope \u00b7 TamboUI ").cyan().bold())))
            .build();
        block.render(area, buffer);
        Rect inner = block.inner(area);

        // Glyph overlays first (Canvas setString sets symbol+fg)...
        Canvas.builder()
            .xBounds(view.x0(), view.x1())
            .yBounds(view.y0(), view.y1())
            .marker(Marker.BRAILLE)
            .paint(ctx -> { for (Layer l : overlays) if (l.enabled()) l.draw(ctx, view); })
            .build()
            .render(inner, buffer);
        // ...then raster backgrounds patch cell bg under the glyphs (setStyle keeps the symbol).
        for (BgLayer bg : bgLayers) if (bg.enabled()) bg.renderBg(buffer, inner, view);
    }

    // Scale bar (bottom-right) + attribution (bottom-left), drawn over the map.
    private void drawMapChrome(Buffer buffer, Rect area) {
        if (area.width() < 20 || area.height() < 3) return;
        int y = area.y() + area.height() - 2;   // last content row, inside the border
        // Attribution.
        buffer.setString(area.x() + 1, y, "\u00a9 OpenStreetMap · RainViewer · OpenSky · USGS",
            Style.EMPTY.fg(Color.DARK_GRAY));
        // Scale bar: pick a round km distance close to ~16 cells wide.
        double kmPerCell = view.lonSpan() / area.width() * 111.32 * Math.cos(Math.toRadians(view.centerLat));
        double target = kmPerCell * 16;
        double[] nice = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000};
        double km = nice[0];
        for (double v : nice) if (v <= target) km = v;
        int cells = Math.max(4, (int) Math.round(km / kmPerCell));
        int bx = area.x() + area.width() - cells - 12;
        if (bx > area.x()) {
            buffer.setString(bx, y, "\u251c" + "\u2500".repeat(Math.max(0, cells - 2)) + "\u2524 "
                + (km >= 1000 ? (int) (km / 1000) + ",000" : (int) km) + " km", Style.EMPTY.fg(Color.GRAY));
        }
        drawLegend(buffer, area);
        drawTimeSlider(buffer, area, y - 1);   // one row above attribution/scale
    }

    // Per-tab colour key, bottom-right above the scale bar.
    private void drawLegend(Buffer buffer, Rect area) {
        List<Span[]> items = switch (TABS.get(tab).name()) {
            case "Flights" -> {
                List<Span[]> fl = new ArrayList<>();
                for (String cat : FlightLayer.CATEGORY_ORDER)
                    fl.add(leg(FlightLayer.label(cat).toUpperCase(), FlightLayer.COLORS.get(cat)));
                fl.add(leg("AIRPORT", Color.rgb(230, 150, 40)));
                yield fl;
            }
            case "Weather" -> List.of(            // magma intensity ramp, light -> extreme
                leg("LIGHT", Color.rgb(101, 21, 110)), leg("MODERATE", Color.rgb(212, 72, 66)),
                leg("HEAVY", Color.rgb(245, 125, 21)), leg("EXTREME", Color.rgb(252, 253, 191)));
            case "Seismic" -> List.of(
                leg("M5+", Color.RED), leg("M3-5", Color.YELLOW), leg("M<3", Color.DARK_GRAY));
            case "Astro" -> List.of(
                leg("DAY", Color.rgb(230, 200, 90)), leg("CIVIL", Color.rgb(90, 90, 150)),
                leg("NAUTICAL", Color.rgb(50, 55, 110)), leg("NIGHT", Color.rgb(20, 24, 50)));
            default -> List.of(
                leg("BORDER", Color.GREEN), leg("PROVINCE", Color.DARK_GRAY),
                leg("CAPITAL", Color.WHITE), leg("CITY", Color.MAGENTA));
        };
        int top = area.y() + area.height() - 2 - items.size();
        if (top <= area.y()) return;
        for (int i = 0; i < items.size(); i++) {
            Span[] it = items.get(i);
            int len = it[0].content().length() + 2;
            int x = area.x() + area.width() - len - 1;
            if (x > area.x()) { buffer.setString(x, top + i, it[0].content(), Style.EMPTY.fg(Color.GRAY));
                buffer.setString(x + it[0].content().length() + 1, top + i, "\u25a0", it[1].style()); }
        }
    }
    private static Span[] leg(String label, Color c) { return new Span[]{Span.raw(label), Span.styled("\u25a0", Style.EMPTY.fg(c))}; }

    // Bottom-centre slider: radar frame age on Weather, the day/night clock elsewhere.
    private void drawTimeSlider(Buffer buffer, Rect area, int y) {
        boolean weather = TABS.get(tab).name().equals("Weather");
        boolean astro = TABS.get(tab).name().equals("Astro");
        if (!weather && !astro) return;
        int track = 24, x0 = area.x() + area.width() / 2 - track / 2;
        if (x0 < area.x() + 20) return;
        double frac; String left, right;
        if (weather) {
            int n = radar.frameCount();
            frac = n <= 1 ? 1 : (double) radar.frameIndex() / (n - 1);
            left = "RADAR"; right = radar.frameLabel();
        } else {
            frac = (Clock.offsetMinutes + 720) / 1440.0;
            left = "TIME"; right = Clock.label();
        }
        int knob = (int) Math.round(frac * (track - 1));
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < track; i++) bar.append(i == knob ? '\u25cf' : '\u2500');
        buffer.setString(x0 - left.length() - 1, y, left, Style.EMPTY.fg(Color.GRAY));
        buffer.setString(x0, y, bar.toString(), Style.EMPTY.fg(Color.CYAN));
        buffer.setString(x0 + track + 1, y, right, Style.EMPTY.fg(Color.GRAY));
    }

    private Paragraph footer() {
        List<Span> spans = new ArrayList<>(List.of(
            Span.raw(" z").cyan(), Span.raw(String.format("%.1fx", view.zoom)).dim(),
            Span.raw("  \u2190\u2191\u2193\u2192").yellow(), Span.raw(" pan").dim(),
            Span.raw(" +/-").yellow(), Span.raw(" zoom").dim(),
            Span.raw(" 0").yellow(), Span.raw(" reset").dim(), Span.raw("  ").dim()));
        for (Toggleable t : toggles) {
            spans.add(Span.raw(" " + t.key()).yellow());
            spans.add(Span.styled(" " + t.label(), Style.EMPTY.fg(t.enabled() ? Color.GREEN : Color.DARK_GRAY)));
        }
        spans.add(Span.raw("  q").yellow());
        spans.add(Span.raw(" quit").dim());
        return Paragraph.builder()
            .text(Text.from(Line.from(spans.toArray(new Span[0]))))
            .block(Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(Color.DARK_GRAY)).build())
            .build();
    }

    // ---- headless dump (verifiable without a terminal) -----------------

    private void dump(int w, int h) {
        Rect area = new Rect(0, 0, w, h);
        // First pass triggers async tile/feed fetches; wait, then render for real.
        ui(Frame.forTesting(Buffer.empty(area)));
        try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
        Buffer buffer = Buffer.empty(area);
        ui(Frame.forTesting(buffer));             // full chrome: tabs, panel, map, footer, modal
        int lit = 0, bg = 0;
        java.util.Set<Object> shades = new java.util.HashSet<>();
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                var cell = buffer.get(x, y);
                String s = cell.symbol();
                if (!s.isEmpty() && !s.equals(" ")) lit++;
                if (cell.style().bg().isPresent()) { bg++; cell.style().bg().ifPresent(shades::add); }
                sb.append(s.isEmpty() ? ' ' : s.charAt(0));
            }
            sb.append('\n');
        }
        System.out.print(sb);
        System.err.printf("zoom=%.1f center=%.0f,%.0f glyph-cells=%d bg-cells=%d distinct-shades=%d  config=%s (zMax=%.0f step=%.1f provMin=%.0f)%n",
            view.zoom, view.centerLon, view.centerLat, lit, bg, shades.size(), Config.source, cfg.zoomMax, cfg.zoomStep, cfg.provinceMin);
        assert lit > 50 : "expected a visible map, got " + lit;
    }
}
