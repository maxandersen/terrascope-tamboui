import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/*
 * Loads Terrascope's own src/terrascope/assets/default.yaml, unchanged, and
 * exposes the handful of values this port actually uses. Proves the config
 * ports too: same file drives both the Python app and this one.
 *
 * Resolution order: TERRASCOPE_CONFIG env, ../../src/.../default.yaml next to
 * the repo, else a bundled fallback of the same defaults.
 */
final class Config {
    final double zoomMin, zoomMax, zoomStep, panStep, provinceMin;
    final double latMin, latMax;
    final String dataBaseUrl, countriesFile, provincesFile, placesFile;
    final List<List<Number>> cityLevels;   // [[zoom, minPop], ...]
    static String source = "built-in defaults";

    @SuppressWarnings("unchecked")
    Config() {
        Map<String, Object> root = load();
        Map<String, Object> map = (Map<String, Object>) root.getOrDefault("map", Map.of());
        Map<String, Object> zoom = (Map<String, Object>) map.getOrDefault("zoom", Map.of());
        zoomMin = num(zoom.get("min"), 1);
        zoomMax = num(zoom.get("max"), 64);
        zoomStep = num(zoom.get("step"), 1.5);
        panStep = num(zoom.get("pan_step"), 0.05);
        provinceMin = num(zoom.get("province_min"), 2);
        latMin = num(map.get("lat_min"), -58);
        latMax = num(map.get("lat_max"), 84);

        Map<String, Object> data = (Map<String, Object>) root.getOrDefault("data", Map.of());
        dataBaseUrl = str(data.get("natural_earth_base_url"),
            "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/");
        countriesFile = str(data.get("countries_file"), "ne_50m_admin_0_countries.geojson");
        // Terrascope upgrades provinces 50m -> 10m (the 50m file barely covers Europe).
        String prov = str(data.get("provinces_file"), "ne_50m_admin_1_states_provinces_lines.geojson");
        provincesFile = prov.equals("ne_50m_admin_1_states_provinces_lines.geojson")
            ? "ne_10m_admin_1_states_provinces_lines.geojson" : prov;
        placesFile = str(data.get("places_file"), "ne_10m_populated_places_simple.geojson");

        Map<String, Object> basemap = ((Map<String, Object>) root.getOrDefault("layers", Map.of()));
        cityLevels = extractCityLevels(basemap);
    }

    private static Map<String, Object> load() {
        try (InputStream in = open()) {
            if (in != null) return new Yaml().load(in);
        } catch (Exception ignored) { }
        return Map.of();   // all fallbacks apply
    }

    private static InputStream open() {
        String env = System.getenv("TERRASCOPE_CONFIG");
        source = env != null ? env : "default.yaml";
        return Assets.open("default.yaml", "TERRASCOPE_CONFIG");
    }

    @SuppressWarnings("unchecked")
    private static List<List<Number>> extractCityLevels(Map<String, Object> layers) {
        try {
            Map<String, Object> basemap = (Map<String, Object>) layers.get("basemap");
            Object lv = basemap.get("city_levels");
            if (lv instanceof List<?> l) return (List<List<Number>>) lv;
        } catch (Exception ignored) { }
        return List.of(List.of(2.0, 5_000_000), List.of(3.0, 2_000_000), List.of(4.5, 1_000_000),
            List.of(7.0, 500_000), List.of(10.0, 200_000), List.of(15.0, 100_000),
            List.of(22.0, 50_000), List.of(35.0, 15_000), List.of(50.0, 0));
    }

    /** Minimum city population visible at a given zoom, from city_levels. */
    long cityPopFloor(double zoom) {
        long floor = Long.MAX_VALUE;
        for (List<Number> lvl : cityLevels)
            if (zoom >= lvl.get(0).doubleValue()) floor = lvl.get(1).longValue();
        return floor == Long.MAX_VALUE ? cityLevels.get(0).get(1).longValue() : floor;
    }

    private static double num(Object o, double dflt) { return o instanceof Number n ? n.doubleValue() : dflt; }
    private static String str(Object o, String dflt) { return o instanceof String s ? s : dflt; }
}
