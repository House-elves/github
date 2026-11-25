package io.github.houseelves.github.board;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Dependabot elf: when it's installed, every Dependabot PR in the repos it handles is its tab's (with its merge
 * queue), so the board leaves them out. Not installed, or a repo it doesn't list, they're on the board like any other PR.
 *
 * <p>
 * Installed means its config file is there. Only its REPOS line is read, nothing else; it has no default.
 */
public final class DependabotElf {

    private static final Pattern DEPENDABOT = Pattern.compile("(app/)?dependabot(\\[bot])?");

    private DependabotElf() {
    }

    static Path config() {
        return Path.of(System.getProperty("user.home"), ".config", "dependabot", "config");
    }

    /** The repos (lower case) whose Dependabot PRs the Dependabot elf handles; empty when it isn't installed. */
    public static Set<String> repos() {
        return repos(config());
    }

    static Set<String> repos(Path config) {
        if (!Files.isRegularFile(config)) return Set.of();
        Set<String> out = new HashSet<>();
        try {
            for (String line : Files.readAllLines(config)) {
                if (!line.startsWith("REPOS=")) continue;
                for (String r : line.substring("REPOS=".length()).split(",")) {
                    if (!r.isBlank()) out.add(r.strip().toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception e) {
            // Unreadable: nothing is left out, so nothing goes missing from the board.
            return Set.of();
        }
        return Set.copyOf(out);
    }

    /** A Dependabot PR in a repo the Dependabot elf handles. */
    public static boolean handles(Set<String> repos, String repo, String author) {
        if (repo == null || !repos.contains(repo.toLowerCase(Locale.ROOT))) return false;
        return author != null && DEPENDABOT.matcher(author.toLowerCase(Locale.ROOT)).matches();
    }
}
