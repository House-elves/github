package io.github.houseelves.github.elf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.github.board.BoardMerge;
import io.github.houseelves.github.thread.DeskThread;
import io.github.houseelves.github.thread.ThreadView;

/**
 * The files the tab reads, written once a run is done: board/board.json (what DeskApi.board returned),
 * threads/{key}.json (DeskApi.thread) for each thread that changed, and state/settings-view.json
 * (DeskApi.settings). The board is built from the cache and local state, never by asking GitHub here.
 */
public class Views {

    /** Answers in cache/ are kept this long. */
    static final Duration KEEP_CACHE = Duration.ofDays(1);

    private Views() {
    }

    /** Writes what this run changed. */
    public static void write(Elf elf) {
        if (elf.live.board() && anyCached(elf)) board(elf);
        for (DeskThread t : elf.store.saved()) thread(elf, t.itemKey);
        if (elf.live.settings() || !Files.exists(settingsFile(elf))) settings(elf);
    }

    /** Writes everything: after a refresh, or an import. */
    public static void writeAll(Elf elf) {
        if (anyCached(elf)) board(elf);
        for (DeskThread t : elf.store.threads()) thread(elf, t.itemKey);
        settings(elf);
        pruneCache(elf.home.cache());
    }

    /**
     * board.json: the board, with the accounts it's from ({login, name}, the first one GITHUB_ACCOUNT), and for each
     * item the accounts that found it, the one it's done as first ({@code found}). Which account each item belongs to
     * is kept for the actions too (state/owners.json).
     */
    static void board(Elf elf) {
        BoardMerge.Merged m = elf.api.boards(false);
        ObjectNode json = Json.MAPPER.valueToTree(m.board());
        json.set("accounts", Json.MAPPER.valueToTree(elf.accounts.all()));
        json.set("found", Json.MAPPER.valueToTree(m.found()));
        Json.write(elf.home.board().resolve("board.json"), json);
        elf.accounts.owners(m.owners());
    }

    private static boolean anyCached(Elf elf) {
        return elf.accounts.logins().stream().anyMatch(elf.boards::cached);
    }

    static void thread(Elf elf, String itemKey) {
        ThreadView v = elf.threads.view(itemKey);
        if (v != null) Json.write(elf.home.threads().resolve(Home.fileName(itemKey) + ".json"), v);
    }

    static void settings(Elf elf) {
        Json.write(settingsFile(elf), elf.api.settings());
    }

    private static Path settingsFile(Elf elf) {
        return elf.home.state().resolve("settings-view.json");
    }

    /** Drops answers older than a day: the tab read them long ago. */
    static void pruneCache(Path dir) {
        if (!Files.isDirectory(dir)) return;
        Instant before = Instant.now().minus(KEEP_CACHE);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                if (Files.isRegularFile(f) && Files.getLastModifiedTime(f).toInstant().isBefore(before)) Files.deleteIfExists(f);
            }
        } catch (IOException ignored) {
            // the next refresh tries again
        }
    }
}
