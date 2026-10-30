import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/*
 * Open-Meteo current conditions + multi-day forecast for a lat/lon. Cached via
 * Http (30 min). Mirrors Terrascope's weather layer / forecast panel.
 */
final class Weather {
    private Weather() { }

    record Day(String date, int code, double tmax, double tmin, int precip, double wind, double gust,
               double uv, String sunrise, String sunset, double[] hourly) { }
    record Data(double temp, int code, double wind, double windDir, List<Day> days) { }

    static Data fetch(double lat, double lon) {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
            + "&current=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m"
            + "&hourly=temperature_2m"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,"
            + "wind_speed_10m_max,wind_gusts_10m_max,uv_index_max,sunrise,sunset&timezone=auto&forecast_days=4";
        Http.Result r = Http.getString(url, 1800);
        if (r.status() != 200 || r.body().isEmpty()) return null;
        try {
            JsonObject root = JsonParser.parseString(r.body()).getAsJsonObject();
            JsonObject cur = root.getAsJsonObject("current");
            JsonObject d = root.getAsJsonObject("daily");
            var date = d.getAsJsonArray("time");
            var code = d.getAsJsonArray("weather_code");
            var tmax = d.getAsJsonArray("temperature_2m_max");
            var tmin = d.getAsJsonArray("temperature_2m_min");
            var precip = d.getAsJsonArray("precipitation_probability_max");
            var wind = d.getAsJsonArray("wind_speed_10m_max");
            var gust = d.getAsJsonArray("wind_gusts_10m_max");
            var uv = d.getAsJsonArray("uv_index_max");
            var sr = d.getAsJsonArray("sunrise");
            var ss = d.getAsJsonArray("sunset");
            var hTemp = root.getAsJsonObject("hourly").getAsJsonArray("temperature_2m");
            List<Day> days = new ArrayList<>();
            for (int i = 0; i < date.size(); i++) {
                double[] hourly = new double[24];      // this day's hourly temps for the sparkline
                for (int h = 0; h < 24 && i * 24 + h < hTemp.size(); h++)
                    hourly[h] = hTemp.get(i * 24 + h).getAsDouble();
                days.add(new Day(date.get(i).getAsString(), code.get(i).getAsInt(),
                    tmax.get(i).getAsDouble(), tmin.get(i).getAsDouble(),
                    precip.get(i).isJsonNull() ? 0 : precip.get(i).getAsInt(),
                    wind.get(i).getAsDouble(), gust.get(i).isJsonNull() ? 0 : gust.get(i).getAsDouble(),
                    uv.get(i).isJsonNull() ? 0 : uv.get(i).getAsDouble(),
                    time(sr.get(i).getAsString()), time(ss.get(i).getAsString()), hourly));
            }
            return new Data(cur.get("temperature_2m").getAsDouble(), cur.get("weather_code").getAsInt(),
                cur.get("wind_speed_10m").getAsDouble(), cur.get("wind_direction_10m").getAsDouble(), days);
        } catch (Exception e) { return null; }
    }

    private static String time(String iso) {   // "2026-10-07T07:09" -> "07:09"
        int t = iso.indexOf('T');
        return t >= 0 && iso.length() >= t + 6 ? iso.substring(t + 1, t + 6) : iso;
    }

    // WMO weather code -> short label + icon.
    static String condition(int code) {
        return switch (code) {
            case 0 -> "Clear"; case 1 -> "Mainly clear"; case 2 -> "Partly cloudy"; case 3 -> "Overcast";
            case 45, 48 -> "Fog"; case 51, 53, 55 -> "Drizzle"; case 56, 57 -> "Freezing drizzle";
            case 61, 63, 65 -> "Rain"; case 66, 67 -> "Freezing rain"; case 71, 73, 75 -> "Snow";
            case 77 -> "Snow grains"; case 80, 81, 82 -> "Showers"; case 85, 86 -> "Snow showers";
            case 95 -> "Thunderstorm"; case 96, 99 -> "Thunderstorm, hail"; default -> "\u2014";
        };
    }
    static String icon(int code) {
        if (code == 0) return "\u2600";          // sun
        if (code <= 2) return "\u26c5";          // sun behind cloud
        if (code == 3 || code == 45 || code == 48) return "\u2601";  // cloud
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "\u2744";  // snow
        if (code >= 95) return "\u26a1";         // thunder
        return "\u2614";                          // rain
    }
}
