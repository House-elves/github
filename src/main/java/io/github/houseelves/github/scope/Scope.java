package io.github.houseelves.github.scope;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which repos the board shows. A repo is covered when its owner is watched and
 * the repo isn't excluded, or when the repo itself is watched. Names compare
 * case-insensitively, as GitHub's do.
 */
public record Scope(Set<String> owners, Set<String> repos, Set<String> excluded) {

    public Scope {
        owners = lower(owners);
        repos = lower(repos);
        excluded = lower(excluded);
    }

    public static Scope of(Iterable<WatchScope> rows) {
        Set<String> owners = new HashSet<>();
        Set<String> repos = new HashSet<>();
        Set<String> excluded = new HashSet<>();
        for (WatchScope w : rows) {
            if (w.kind == WatchScope.Kind.owner) owners.add(w.name);
            else if (w.mode == WatchScope.Mode.exclude) excluded.add(w.name);
            else repos.add(w.name);
        }
        return new Scope(owners, repos, excluded);
    }

    public boolean isEmpty() {
        return owners.isEmpty() && repos.isEmpty();
    }

    public boolean covers(String repo) {
        String r = repo.toLowerCase(Locale.ROOT);
        if (repos.contains(r)) return true;
        int slash = r.indexOf('/');
        return slash > 0 && owners.contains(r.substring(0, slash)) && !excluded.contains(r);
    }

    private static Set<String> lower(Set<String> names) {
        return names.stream().map(n -> n.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
