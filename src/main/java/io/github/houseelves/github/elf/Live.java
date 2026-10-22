package io.github.houseelves.github.elf;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What changed during one run of the elf, for the files the tab reads. The desk pushed these to open pages over
 * JSON-RPC; the elf instead rewrites the files once its action is done (see {@link Views}), and the runner tells
 * the tab its data changed.
 */
public class Live {

    private final Set<String> changed = new LinkedHashSet<>();
    private boolean board;
    private boolean settings;

    /** A thread changed: its threads/ file is written again. */
    public synchronized void changed(String itemKey) {
        if (itemKey != null) changed.add(itemKey);
        board = true; // the board shows each thread's state
    }

    /** What the board shows changed: board.json is written again from the cache. */
    public synchronized void boardUpdated() {
        board = true;
    }

    /** The board's settings changed: state/settings-view.json is written again. */
    public synchronized void settingsChanged() {
        settings = true;
        board = true;
    }

    public synchronized Set<String> changedThreads() {
        return Set.copyOf(changed);
    }

    public synchronized boolean board() {
        return board;
    }

    public synchronized boolean settings() {
        return settings;
    }
}
