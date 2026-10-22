package io.github.houseelves.github.settings;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.houseelves.github.elf.Json;

/**
 * Typed access to the board's settings, with defaults for anything never saved. They're kept as the desk's setting
 * table was, key to text, in state/settings.json.
 */
public class Settings {

    static final String REFRESH_MINUTES = "board.refresh-minutes";
    static final int DEFAULT_REFRESH_MINUTES = 5;
    static final String MVNPM_ENABLED = "mvnpm.enabled";
    static final String ALWAYS_INVOLVED = "board.always-involved";

    private final Path file;
    private Map<String, String> values;

    public Settings(Path stateDir) {
        this.file = stateDir.resolve("settings.json");
    }

    private synchronized Map<String, String> values() {
        if (values == null) values = new TreeMap<>(Json.read(file, new TypeReference<Map<String, String>>() {}, Map.of()));
        return values;
    }

    private String get(String key) {
        return values().get(key);
    }

    private void put(String key, String value) {
        if (value == null) values().remove(key);
        else values().put(key, value);
        Json.write(file, values());
    }

    /** Repos you maintain (owner/repo, lower case): every open PR and issue in them is on the board. */
    public List<String> alwaysInvolved() {
        String s = get(ALWAYS_INVOLVED);
        if (s == null) return List.of();
        return Arrays.stream(s.split("\n")).map(String::strip).filter(r -> !r.isEmpty()).toList();
    }

    public void setAlwaysInvolved(List<String> repos) {
        put(ALWAYS_INVOLVED, repos.isEmpty() ? null : String.join("\n", repos));
    }

    public int refreshMinutes() {
        String s = get(REFRESH_MINUTES);
        if (s == null) return DEFAULT_REFRESH_MINUTES;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return DEFAULT_REFRESH_MINUTES;
        }
    }

    /** The mvnpm lane and checks on the desk (on unless turned off; they also need mvnpm-dependabot set up). */
    public boolean mvnpmEnabled() {
        String s = get(MVNPM_ENABLED);
        return s == null || !s.equals("false");
    }

    public void setMvnpmEnabled(boolean on) {
        put(MVNPM_ENABLED, Boolean.toString(on));
    }

    public void setRefreshMinutes(int minutes) {
        if (minutes < 1 || minutes > 60) {
            throw new IllegalArgumentException("Refresh must be between 1 and 60 minutes");
        }
        put(REFRESH_MINUTES, Integer.toString(minutes));
    }
}
