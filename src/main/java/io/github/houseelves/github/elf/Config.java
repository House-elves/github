package io.github.houseelves.github.elf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** ~/.config/github/config: KEY=value lines, which Elf Desk writes from the elf's settings. */
public record Config(Map<String, String> values) {

    public static Config load(Path file) {
        Map<String, String> raw = new HashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file)) {
                    line = line.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) raw.putIfAbsent(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Couldn't read " + file, e);
            }
        }
        return new Config(Map.copyOf(raw));
    }

    /** The gh account the elf works as. */
    public String githubAccount() {
        return values.getOrDefault("GITHUB_ACCOUNT", "").strip();
    }
}
