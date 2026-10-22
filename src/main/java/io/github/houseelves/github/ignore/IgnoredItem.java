package io.github.houseelves.github.ignore;

import java.time.Instant;

/**
 * An issue or PR you've chosen to ignore: someone else is handling it. It comes back (returnedAt set) when
 * someone requests your review or mentions you after {@code ignored}. Kept in state/ignored.json.
 */
public class IgnoredItem {

    public String itemKey;

    public String login;

    public Instant ignored = Instant.now();

    /** When new activity brought it back; null while it's still ignored. */
    public Instant returnedAt;

    public String returnedReason;
}
