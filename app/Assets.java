import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/*
 * Locates bundled data files (reference_data.json, default.yaml) regardless of
 * the working directory. Order: an explicit env override, then the jbang-bundled
 * resource (//FILES), then a search up from the CWD for the Terrascope repo.
 */
final class Assets {
    private Assets() { }

    static InputStream open(String name, String envVar) {
        try {
            // 1. explicit override
            String env = envVar == null ? null : System.getenv(envVar);
            if (env != null && Files.exists(Path.of(env))) return Files.newInputStream(Path.of(env));
            // 2. bundled with the script (jbang //FILES) -> classpath resource
            InputStream res = Assets.class.getResourceAsStream("/" + name);
            if (res != null) return res;
            // 3. search up from the working dir for src/terrascope/assets/<name>
            Path dir = Path.of("").toAbsolutePath();
            for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
                Path p = dir.resolve("src/terrascope/assets/" + name);
                if (Files.exists(p)) return Files.newInputStream(p);
                p = dir.resolve("tamboui-port/app/" + name);
                if (Files.exists(p)) return Files.newInputStream(p);
            }
        } catch (Exception ignored) { }
        return null;
    }
}
