package io.github.houseelves.github.elf;

import java.nio.file.Path;

/**
 * Where the elf keeps everything: its config file and the folders its tab reads, in {@code ~/.config/github/}.
 * Tests give it a temp directory.
 *
 * <ul>
 * <li>{@code board/}: board.json (what the tab shows) and cache-{login}.json per account (what it's built from);</li>
 * <li>{@code threads/}: one ThreadView per item with a thread;</li>
 * <li>{@code state/}: what the desk kept in its tables;</li>
 * <li>{@code cache/}: answers too big for an action's one line;</li>
 * <li>{@code agents/}: the runner's reports on the agents this elf started (the runner writes it).</li>
 * </ul>
 */
public record Home(Path dir) {

    public static Home user() {
        String override = System.getenv("GITHUB_ELF_HOME");
        if (override != null && !override.isBlank()) return new Home(Path.of(override));
        return new Home(Path.of(System.getProperty("user.home"), ".config", "github"));
    }

    public Path config() {
        return dir.resolve("config");
    }

    public Path board() {
        return dir.resolve("board");
    }

    public Path threads() {
        return dir.resolve("threads");
    }

    public Path state() {
        return dir.resolve("state");
    }

    public Path cache() {
        return dir.resolve("cache");
    }

    public Path agents() {
        return dir.resolve("agents");
    }

    /** An item key (owner/repo#123) as a file name: every character outside [A-Za-z0-9._-] made _. */
    public static String fileName(String itemKey) {
        return itemKey.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
