import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/*
 * HTTP with a disk cache and optional OpenSky OAuth2. The cache is keyed by URL
 * with a per-call TTL; on a 429 / network error it serves the last good copy
 * instead of blanking, so restarts (and rate limits) don't keep hammering the
 * endpoints. Set OPENSKY_CLIENT_ID / OPENSKY_CLIENT_SECRET for authenticated
 * OpenSky access (much higher limits); without them it stays anonymous.
 */
final class Http {
    private Http() { }
    static final String UA = "terrascope-tamboui/0.1 (+https://github.com/tamboui/tamboui)";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final Path DIR = Path.of(System.getProperty("java.io.tmpdir"), "terrascope-cache");

    record Result(int status, String body, boolean cached) { }

    static Result getString(String url, long ttlSeconds) {
        Path f = cacheFile(url, ".txt");
        if (fresh(f, ttlSeconds)) return new Result(200, read(f), true);
        try {
            HttpResponse<String> r = CLIENT.send(request(url).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) { write(f, r.body().getBytes(StandardCharsets.UTF_8)); return new Result(200, r.body(), false); }
            if (Files.exists(f)) return new Result(200, read(f), true);   // serve stale on 429 / error
            return new Result(r.statusCode(), r.body(), false);
        } catch (Exception e) {
            if (Files.exists(f)) return new Result(200, read(f), true);
            return new Result(-1, "", false);
        }
    }

    static byte[] getBytes(String url, long ttlSeconds) {
        Path f = cacheFile(url, ".bin");
        if (fresh(f, ttlSeconds)) return readBytes(f);
        try {
            HttpResponse<byte[]> r = CLIENT.send(request(url).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() == 200) { write(f, r.body()); return r.body(); }
            if (Files.exists(f)) return readBytes(f);
            return null;
        } catch (Exception e) {
            return Files.exists(f) ? readBytes(f) : null;
        }
    }

    // ---- request building (adds UA + OpenSky bearer when configured) ----

    private static HttpRequest.Builder request(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA);
        if (url.contains("opensky-network.org")) {
            String t = openskyToken();
            if (t != null) b.header("Authorization", "Bearer " + t);
        }
        return b;
    }

    private static final String TOKEN_URL =
        "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token";
    private static String token;
    private static long tokenExpiry;

    private static synchronized String openskyToken() {
        String[] creds = openskyCreds();
        if (creds == null) return null;
        String id = creds[0], secret = creds[1];
        if (token != null && System.currentTimeMillis() < tokenExpiry) return token;
        try {
            String form = "grant_type=client_credentials&client_id=" + enc(id) + "&client_secret=" + enc(secret);
            HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", UA)
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            var o = JsonParser.parseString(r.body()).getAsJsonObject();
            token = o.get("access_token").getAsString();
            long ttl = o.has("expires_in") ? o.get("expires_in").getAsLong() : 1800;
            tokenExpiry = System.currentTimeMillis() + (ttl - 30) * 1000;   // refresh 30s early
            return token;
        } catch (Exception e) { return null; }
    }

    // Credentials from OPENSKY_CLIENT_ID/SECRET, else an opensky.json file
    // (cwd, ~, or ~/.config/opensky/) holding {"clientId":..,"clientSecret":..}.
    private static String[] openskyCreds() {
        String id = System.getenv("OPENSKY_CLIENT_ID"), secret = System.getenv("OPENSKY_CLIENT_SECRET");
        if (id != null && secret != null) return new String[]{id, secret};
        String home = System.getProperty("user.home");
        for (Path p : new Path[]{Path.of("opensky.json"), Path.of(home, "opensky.json"),
                Path.of(home, ".config", "opensky", "opensky.json")}) {
            try {
                if (!Files.exists(p)) continue;
                var o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                String cid = field(o, "clientId", "client_id"), cs = field(o, "clientSecret", "client_secret");
                if (cid != null && cs != null) return new String[]{cid, cs};
            } catch (Exception ignored) { }
        }
        return null;
    }
    private static String field(com.google.gson.JsonObject o, String... keys) {
        for (String k : keys) if (o.has(k) && !o.get(k).isJsonNull()) return o.get(k).getAsString();
        return null;
    }

    // ---- cache plumbing -------------------------------------------------

    private static boolean fresh(Path f, long ttlSeconds) {
        try { return Files.exists(f) && System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < ttlSeconds * 1000; }
        catch (Exception e) { return false; }
    }
    private static Path cacheFile(String url, String ext) { return DIR.resolve(Integer.toHexString(url.hashCode()) + ext); }
    private static String read(Path f) { try { return Files.readString(f); } catch (Exception e) { return ""; } }
    private static byte[] readBytes(Path f) { try { return Files.readAllBytes(f); } catch (Exception e) { return null; } }
    private static void write(Path f, byte[] data) {
        try { Files.createDirectories(DIR); Files.write(f, data); } catch (Exception ignored) { }
    }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
