package io.github.houseelves.github.board;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The mvnpm elf (mvnpm-dependabot): when it's installed, Dependabot's org.mvnpm bumps in the repos it checks are its
 * tab's, so the board leaves them out. Not installed, they're on the board like any other PR.
 *
 * <p>
 * Installed means its config file is there (as the runner counts it). Only its REPOS line is read, nothing else.
 */
public final class MvnpmElf {

    private static final Pattern DEPENDABOT = Pattern.compile("(app/)?dependabot(\\[bot])?");
    /** Its own default, when its config has no REPOS line. */
    static final String DEFAULT_REPOS = "quarkusio/quarkus";

    private MvnpmElf() {
    }

    static Path config() {
        return Path.of(System.getProperty("user.home"), ".config", "mvnpm-dependabot", "config");
    }

    /** The repos (lower case) whose mvnpm bumps the mvnpm elf checks; empty when it isn't installed. */
    public static Set<String> repos() {
        return repos(config());
    }

    static Set<String> repos(Path config) {
        if (!Files.isRegularFile(config)) return Set.of();
        String value = DEFAULT_REPOS;
        try {
            for (String line : Files.readAllLines(config)) {
                if (line.startsWith("REPOS=")) value = line.substring("REPOS=".length());
            }
        } catch (Exception e) {
            // Installed but unreadable: its default, as it would itself.
        }
        Set<String> out = new HashSet<>();
        for (String r : value.split(",")) {
            if (!r.isBlank()) out.add(r.strip().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }

    /**
     * The mvnpm elf's rule: a Dependabot PR whose title or branch names an org.mvnpm artifact (org.mvnpm:lit, and
     * scoped packages as org.mvnpm.at.hpcc-js:wasm), in a repo it checks.
     */
    public static boolean handles(Set<String> repos, String repo, String author, String title, String headRef) {
        if (repo == null || !repos.contains(repo.toLowerCase(Locale.ROOT))) return false;
        if (author == null || !DEPENDABOT.matcher(author.toLowerCase(Locale.ROOT)).matches()) return false;
        return (title != null && title.contains("org.mvnpm")) || (headRef != null && headRef.contains("org.mvnpm"));
    }
}
