import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/*
 * Bundled country labels + airports, from Terrascope's
 * src/terrascope/assets/reference_data.json. Same file, loaded unchanged.
 */
final class ReferenceData {
    record Country(double lon, double lat, String code, double size, String name) { }
    record Airport(double lon, double lat, String name, String iata, int rank) { }

    final List<Country> countries;
    final List<Airport> airports;

    ReferenceData() {
        countries = new ArrayList<>();
        airports = new ArrayList<>();
        try (var in = Assets.open("reference_data.json", "TERRASCOPE_REFERENCE")) {
            if (in == null) return;
            var root = JsonParser.parseString(new String(in.readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("country_labels")) {
                JsonArray a = e.getAsJsonArray();        // [lon, lat, code, size, name]
                countries.add(new Country(a.get(0).getAsDouble(), a.get(1).getAsDouble(),
                    a.get(2).getAsString(), a.get(3).getAsDouble(), a.get(4).getAsString()));
            }
            for (JsonElement e : root.getAsJsonArray("airports")) {
                JsonArray a = e.getAsJsonArray();        // [lon, lat, name, iata, rank]
                airports.add(new Airport(a.get(0).getAsDouble(), a.get(1).getAsDouble(),
                    a.get(2).getAsString(), a.get(3).getAsString(), a.get(4).getAsInt()));
            }
        } catch (Exception ignored) { /* no reference data -> layers just stay empty */ }
    }
}
