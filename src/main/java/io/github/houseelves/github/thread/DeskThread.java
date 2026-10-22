package io.github.houseelves.github.thread;

import java.time.Instant;

/** The thread for one issue or PR: one resumable Claude session. */
public class DeskThread {

    public Long id;

    public String itemKey;

    /** PR or ISSUE. */
    public String itemKind;

    public String title;

    public String url;

    /** Set from the first finished job; every later job resumes it. */
    public String claudeSession;

    public Instant created = Instant.now();

    public Instant updated = Instant.now();

    /** When the thread page last showed it: an agent answer after this is new. Null until it was opened. */
    public Instant seen;

    public String repo() {
        return itemKey.substring(0, itemKey.indexOf('#'));
    }

    public int number() {
        return Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
    }
}
