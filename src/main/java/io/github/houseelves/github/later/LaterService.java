package io.github.houseelves.github.later;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.houseelves.github.elf.Json;

/**
 * The items you put aside for later, in state/later.json: while they'd be your move, the board shows them under
 * Later instead. Doing anything with one (any action but reading it) takes it back out ({@link #back}).
 */
public class LaterService {

    private final Path file;
    private Map<String, Instant> items;

    public LaterService(Path stateDir) {
        this.file = stateDir.resolve("later.json");
    }

    private synchronized Map<String, Instant> all() {
        if (items == null) items = new LinkedHashMap<>(Json.read(file, new TypeReference<Map<String, Instant>>() {}, Map.of()));
        return items;
    }

    public synchronized Set<String> keys() {
        return Set.copyOf(all().keySet());
    }

    public synchronized void later(String itemKey) {
        all().put(itemKey, Instant.now());
        Json.write(file, all());
    }

    /** Back to your move. True when it was set aside. */
    public synchronized boolean back(String itemKey) {
        if (all().remove(itemKey) == null) return false;
        Json.write(file, all());
        return true;
    }
}
