package io.github.houseelves.github.elf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

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
        if (elf.live.board() && elf.boards.cached(elf.login)) board(elf);
        for (DeskThread t : elf.store.saved()) thread(elf, t.itemKey);
        if (elf.live.settings() || !Files.exists(settingsFile(elf))) settings(elf);
    }

    /** Writes everything: after a refresh, or an import. */
    public static void writeAll(Elf elf) {
        if (elf.boards.cached(elf.login)) board(elf);
        for (DeskThread t : elf.store.threads()) thread(elf, t.itemKey);
        settings(elf);
        pruneCache(elf.home.cache());
    }

    static void board(Elf elf) {
        Json.write(elf.home.board().resolve("board.json"), elf.api.board(false));
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
