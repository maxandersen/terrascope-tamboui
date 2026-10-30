import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.function.Consumer;

/*
 * Tiny async JSON poller for the live API layers. Fetches on a daemon thread,
 * refreshes every 60s, swallows failures (the layer just keeps its last data).
 */
final class Json {
    private Json() { }

    static void pollAsync(String url, String name, long ttlSeconds, Consumer<JsonObject> onData) {
        pollRaw(url, name, ttlSeconds, res -> {
            if (res.status() == 200 && !res.body().isEmpty())
                onData.accept(JsonParser.parseString(res.body()).getAsJsonObject());
        });
    }

    // Hands the cached HTTP result so callers can see the status (e.g. 429) and
    // whether it came from the disk cache. The poll loop runs every ttl seconds.
    static void pollRaw(String url, String name, long ttlSeconds, Consumer<Http.Result> onResp) {
        Thread t = new Thread(() -> {
            while (true) {
                onResp.accept(Http.getString(url, ttlSeconds));
                try { Thread.sleep(Math.max(5_000, ttlSeconds * 1000)); } catch (InterruptedException e) { return; }
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }
}
