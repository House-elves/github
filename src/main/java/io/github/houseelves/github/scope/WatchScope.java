package io.github.houseelves.github.scope;

import java.time.Instant;

/** One row of what the board watches, kept in state/scope.json (the desk's watch_scope table). */
public class WatchScope {

    public Long id;

    public enum Kind { owner, repo }

    public enum Mode { include, exclude }

    public Kind kind;

    public String name;

    public Mode mode;

    public Instant added = Instant.now();
}
