package io.github.houseelves.github.ignore;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.thread.DeskThread;
import io.github.houseelves.github.thread.ThreadStore;
import io.github.houseelves.github.thread.Turn;

/**
 * The items you chose to ignore, in state/ignored.json. An ignored item comes back to its lane when, after you
 * ignored it, someone requests your review or mentions you ({@link #bringBack}); the board shows why for a day.
 */
public class IgnoreService {

    /** How long the board shows why an item came back. */
    static final Duration SHOW_RETURN_FOR = Duration.ofDays(1);

    private final Path file;
    private final ThreadStore store;
    private List<IgnoredItem> items;

    public IgnoreService(Path stateDir, ThreadStore store) {
        this.file = stateDir.resolve("ignored.json");
        this.store = store;
    }

    private synchronized List<IgnoredItem> all() {
        if (items == null) items = new ArrayList<>(Json.read(file, new TypeReference<List<IgnoredItem>>() {}, List.of()));
        return items;
    }

    private void save() {
        Json.write(file, all());
    }

    private List<IgnoredItem> active() {
        return all().stream().filter(i -> i.returnedAt == null).toList();
    }

    private IgnoredItem find(String itemKey) {
        return all().stream().filter(i -> i.itemKey.equals(itemKey)).findFirst().orElse(null);
    }

    public Set<String> keys() {
        return active().stream().map(i -> i.itemKey).collect(Collectors.toUnmodifiableSet());
    }

    /** Still-ignored items and when you ignored them: what to check for new activity. */
    public Map<String, Instant> ignoredSince() {
        return active().stream().collect(Collectors.toUnmodifiableMap(i -> i.itemKey, i -> i.ignored));
    }

    /** Items that came back in the last day, with why ("Back: you were mentioned"). */
    public Map<String, String> recentlyReturned() {
        Instant since = Instant.now().minus(SHOW_RETURN_FOR);
        return all().stream().filter(i -> i.returnedAt != null && i.returnedAt.isAfter(since))
                .collect(Collectors.toUnmodifiableMap(i -> i.itemKey, i -> i.returnedReason));
    }

    public boolean isIgnored(String itemKey) {
        return keys().contains(itemKey);
    }

    public void ignore(String itemKey, String login) {
        IgnoredItem i = find(itemKey);
        if (i == null) {
            i = new IgnoredItem();
            i.itemKey = itemKey;
            all().add(i);
        }
        // Ignoring again (also after it came back) starts the clock again.
        i.login = login;
        i.ignored = Instant.now();
        i.returnedAt = null;
        i.returnedReason = null;
        save();
    }

    public void unignore(String itemKey) {
        if (all().removeIf(i -> i.itemKey.equals(itemKey))) save();
    }

    /** New activity aimed at you: back to its lane, with the reason noted. */
    public void bringBack(String itemKey, String reason) {
        IgnoredItem i = find(itemKey);
        if (i == null || i.returnedAt != null) return;
        i.returnedAt = Instant.now();
        i.returnedReason = reason;
        save();
        DeskThread t = store.byKey(itemKey);
        if (t != null) store.addTurn(t.id, Turn.SYSTEM, null, "No longer ignored: " + reason.toLowerCase() + ".");
    }
}
