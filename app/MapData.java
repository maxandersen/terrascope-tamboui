import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/*
 * Natural Earth data loading + cache. Replaces Terrascope's mapdata.py.
 * Downloads once to the temp dir, parses GeoJSON into flat lon/lat arrays.
 */
final class MapData {
    private static String base = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/";

    record City(double lon, double lat, long pop, boolean capital, String name, String country) { }

    final List<double[]> countryRings;          // all rings (outer + holes) for coastline outlines
    final List<List<double[]>> countryPolygons; // grouped [outer, hole, hole...] for filled land
    final List<List<double[]>> lakePolygons;    // lakes, subtracted from the land fill (water)
    final List<double[]> provinceLines;         // admin-1 borders
    final List<City> cities;
    final List<ReferenceData.Airport> airports; // OurAirports: large + medium w/ scheduled service

    MapData(Config cfg) throws IOException, InterruptedException {
        base = cfg.dataBaseUrl;
        countryPolygons = loadPolygons(cfg.countriesFile, "ne_50m.geojson");
        countryRings = new ArrayList<>();
        for (List<double[]> poly : countryPolygons) countryRings.addAll(poly);
        lakePolygons = loadPolygonsSafe("ne_50m_lakes.geojson", "ne_50m_lakes.geojson");
        provinceLines = loadLines(cfg.provincesFile, cfg.provincesFile);   // cache keyed by filename
        cities = loadCities(cfg.placesFile, cfg.placesFile);
        airports = loadOurAirports();
    }

    // OurAirports (~86k rows): keep commercial large/medium airports that have an
    // IATA code. Ranked by type so large show first, medium when zoomed in.
    private static List<ReferenceData.Airport> loadOurAirports() {
        List<ReferenceData.Airport> out = new ArrayList<>();
        Http.Result r = Http.getString("https://davidmegginson.github.io/ourairports-data/airports.csv", 604800);
        if (r.status() != 200 || r.body().isEmpty()) return out;
        String[] lines = r.body().split("\n");
        for (int i = 1; i < lines.length; i++) {
            List<String> f = csv(lines[i]);
            if (f.size() < 14) continue;
            String type = f.get(2), iata = f.get(13), sched = f.get(11);
            if (iata.isEmpty() || !sched.equals("yes")) continue;
            int rank = type.equals("large_airport") ? 5 : type.equals("medium_airport") ? 8 : -1;
            if (rank < 0) continue;
            try {
                out.add(new ReferenceData.Airport(Double.parseDouble(f.get(5)), Double.parseDouble(f.get(4)),
                    f.get(3), iata, rank));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    // Minimal CSV line split honouring double-quoted fields (names contain commas).
    private static List<String> csv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') { if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = !q; }
            else if (c == ',' && !q) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    private static List<List<double[]>> loadPolygonsSafe(String file, String cache) {
        try { return loadPolygons(file, cache); } catch (Exception e) { return new ArrayList<>(); }
    }

    // ---- fetch + cache -------------------------------------------------

    static JsonObject fetch(String file, String cacheName) throws IOException, InterruptedException {
        Path cache = Path.of(System.getProperty("java.io.tmpdir"), cacheName);
        if (!Files.exists(cache)) {
            HttpResponse<Path> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + file)).build(),
                HttpResponse.BodyHandlers.ofFile(cache));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + file);
        }
        return JsonParser.parseString(Files.readString(cache)).getAsJsonObject();
    }

    // ---- parsers -------------------------------------------------------

    // Each polygon kept as [outer ring, hole, hole, ...] so lakes render as water.
    private static List<List<double[]>> loadPolygons(String file, String cache)
            throws IOException, InterruptedException {
        List<List<double[]>> out = new ArrayList<>();
        for (JsonElement f : fetch(file, cache).getAsJsonArray("features")) {
            JsonObject geom = obj(f.getAsJsonObject(), "geometry");
            if (geom == null) continue;
            JsonArray c = geom.getAsJsonArray("coordinates");
            switch (geom.get("type").getAsString()) {
                case "Polygon" -> out.add(rings(c));
                case "MultiPolygon" -> {
                    for (JsonElement poly : c) out.add(rings(poly.getAsJsonArray()));
                }
                default -> { }
            }
        }
        return out;
    }

    private static List<double[]> rings(JsonArray polygon) {
        List<double[]> r = new ArrayList<>();
        for (JsonElement ring : polygon) r.add(flatten(ring.getAsJsonArray()));
        return r;
    }

    private static List<double[]> loadLines(String file, String cache)
            throws IOException, InterruptedException {
        List<double[]> out = new ArrayList<>();
        for (JsonElement f : fetch(file, cache).getAsJsonArray("features")) {
            JsonObject geom = obj(f.getAsJsonObject(), "geometry");
            if (geom == null) continue;
            JsonArray c = geom.getAsJsonArray("coordinates");
            switch (geom.get("type").getAsString()) {
                case "LineString" -> out.add(flatten(c));
                case "MultiLineString" -> {
                    for (JsonElement seg : c) out.add(flatten(seg.getAsJsonArray()));
                }
                default -> { }
            }
        }
        return out;
    }

    private static List<City> loadCities(String file, String cache)
            throws IOException, InterruptedException {
        List<City> out = new ArrayList<>();
        for (JsonElement f : fetch(file, cache).getAsJsonArray("features")) {
            JsonObject o = f.getAsJsonObject();
            JsonArray c = o.getAsJsonObject("geometry").getAsJsonArray("coordinates");
            JsonObject p = o.getAsJsonObject("properties");
            long pop = p.has("pop_max") && !p.get("pop_max").isJsonNull() ? p.get("pop_max").getAsLong() : 0;
            boolean cap = p.has("adm0cap") && p.get("adm0cap").getAsInt() == 1;
            String country = p.has("adm0name") && !p.get("adm0name").isJsonNull() ? p.get("adm0name").getAsString() : "";
            out.add(new City(c.get(0).getAsDouble(), c.get(1).getAsDouble(), pop, cap, p.get("name").getAsString(), country));
        }
        return out;
    }

    // Safe nested-object access: returns null for an absent or JSON-null member.
    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static double[] flatten(JsonArray line) {
        double[] flat = new double[line.size() * 2];
        int i = 0;
        for (JsonElement pt : line) {
            JsonArray p = pt.getAsJsonArray();
            flat[i++] = p.get(0).getAsDouble();
            flat[i++] = p.get(1).getAsDouble();
        }
        return flat;
    }
}
