package io.github.houseelves.github.mvnpm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.github.elf.Log;
import io.github.houseelves.github.github.Item;

/**
 * Read-only view of what mvnpm-dependabot knows: the repos it watches (its
 * config's REPOS) and the verdict it recorded per PR (its state.json). The
 * GitHub elf never writes either file; that elf owns them.
 */
public class MvnpmElf {

    private static final Log LOG = Log.of(MvnpmElf.class);

    /** The elf's default when REPOS is unset, and the repo whose legacy state keys are bare numbers. */
    static final String QUARKUS = "quarkusio/quarkus";

    private final Path configFile;
    private final Path stateFile;
    private final ObjectMapper mapper;

    /** Where the elf keeps them on this machine: its config and its state.json. */
    public static MvnpmElf onThisMachine(ObjectMapper mapper) {
        Path home = Path.of(System.getProperty("user.home"));
        return new MvnpmElf(home.resolve(".config/mvnpm-dependabot/config"), home.resolve(".local/state/mvnpm-dependabot"),
                mapper);
    }

    public MvnpmElf(Path configFile, Path stateDir, ObjectMapper mapper) {
        this.configFile = configFile;
        this.stateFile = stateDir.resolve("state.json");
        this.mapper = mapper;
    }

    /**
     * What the elf recorded the last time it checked a PR.
     *
     * @param verdict SAFE, NOT_SAFE or NEEDS_HUMAN
     * @param sha     the head commit it checked; a different head means it hasn't seen the latest push
     */
    public record Check(String sha, String verdict, boolean approved, String commentUrl, Instant at) {
    }

    /** True when the elf is set up on this machine (its config file exists). */
    public boolean installed() {
        return Files.isRegularFile(configFile);
    }

    /** The repos the elf validates, from REPOS (or the older REPO), defaulting to Quarkus as the elf does. */
    public List<String> repos() {
        if (!installed()) return List.of();
        try {
            String repos = null, repo = null;
            for (String line : Files.readAllLines(configFile)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).strip(), v = line.substring(eq + 1).strip();
                if (k.equals("REPOS")) repos = v;
                if (k.equals("REPO")) repo = v;
            }
            String value = repos != null ? repos : repo != null ? repo : QUARKUS;
            return Arrays.stream(value.split("\\s*,\\s*")).filter(s -> !s.isBlank()).toList();
        } catch (IOException e) {
            LOG.warnf("Cannot read %s: %s", configFile, e.getMessage());
            return List.of();
        }
    }

    /** The elf's state, read fresh; empty when it has never run. */
    public JsonNode state() {
        if (!Files.isRegularFile(stateFile)) return mapper.createObjectNode();
        try {
            return mapper.readTree(stateFile.toFile());
        } catch (IOException e) {
            LOG.warnf("Cannot read %s: %s", stateFile, e.getMessage());
            return mapper.createObjectNode();
        }
    }

    /** The recorded check for one PR, looked up in a state read by {@link #state()}. */
    public static Optional<Check> check(JsonNode state, String repo, int number) {
        JsonNode n = state.path(Item.key(repo, number));
        // Before the elf watched more than one repo, Quarkus PRs were keyed by the bare number.
        if (n.isMissingNode() && repo.equalsIgnoreCase(QUARKUS)) n = state.path(String.valueOf(number));
        if (!n.isObject()) return Optional.empty();
        Instant at = null;
        try {
            if (n.path("at").isTextual()) at = Instant.parse(n.path("at").asText());
        } catch (DateTimeParseException ignored) {
            // leave it unknown
        }
        return Optional.of(new Check(n.path("sha").asText(null), n.path("verdict").asText(null),
                n.path("approved").asBoolean(), n.path("comment").asText(null), at));
    }

    /** The elf's own rule (GitHub.mvnpmPrs): a Dependabot PR whose branch or title names an org.mvnpm artifact. */
    public static boolean isMvnpmBump(Item item) {
        if (!item.isPr() || !item.author().equals("dependabot")) return false;
        return (item.headRef() != null && item.headRef().contains("org.mvnpm")) || item.title().contains("org.mvnpm");
    }
}
