package io.github.houseelves.github.scope;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.houseelves.github.elf.Json;

/** Reads and edits what the board watches, in state/scope.json. Names are stored lower-case. */
public class ScopeService {

    // GitHub's own rules: owners are alphanumeric with single hyphens; repo names add dots and underscores.
    private static final Pattern OWNER = Pattern.compile("[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){0,38}");
    private static final Pattern REPO = Pattern.compile(OWNER.pattern() + "/[a-z0-9._-]{1,100}");

    private final Path file;
    private List<WatchScope> rows;

    public ScopeService(Path stateDir) {
        this.file = stateDir.resolve("scope.json");
    }

    /** Every row, read once per run. */
    private synchronized List<WatchScope> all() {
        if (rows == null) rows = new ArrayList<>(Json.read(file, new TypeReference<List<WatchScope>>() {}, List.of()));
        return rows;
    }

    private void save() {
        Json.write(file, all());
    }

    public Scope scope() {
        return Scope.of(all());
    }

    public List<WatchScope> rows() {
        return all().stream().sorted(Comparator.comparing((WatchScope w) -> w.kind).thenComparing(w -> w.name)).toList();
    }

    /**
     * Watches an owner ("quarkusio") or a single repo ("mvnpm/mvnpm"). Watching a
     * repo that a watched owner excludes lifts the exclusion instead.
     */
    public void watch(String nameOrRepo) {
        String name = normalise(nameOrRepo);
        if (OWNER.matcher(name).matches()) {
            if (find(WatchScope.Kind.owner, name) == null) add(WatchScope.Kind.owner, name, WatchScope.Mode.include);
            return;
        }
        if (!REPO.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + nameOrRepo + "' is not an org, a user, or an owner/repo");
        }
        WatchScope existing = find(WatchScope.Kind.repo, name);
        if (existing != null && existing.mode == WatchScope.Mode.exclude) {
            all().remove(existing);
            save();
            return;
        }
        if (existing != null) return;
        if (find(WatchScope.Kind.owner, owner(name)) != null) {
            throw new IllegalArgumentException(name + " is already covered by " + owner(name));
        }
        add(WatchScope.Kind.repo, name, WatchScope.Mode.include);
    }

    /** First-run save: watch each of these owners. */
    public void watchAll(List<String> owners) {
        owners.forEach(this::watch);
    }

    /** Stops showing one repo of a watched owner. */
    public void exclude(String repo) {
        String name = normalise(repo);
        if (!REPO.matcher(name).matches()) throw new IllegalArgumentException("'" + repo + "' is not an owner/repo");
        if (find(WatchScope.Kind.owner, owner(name)) == null) {
            throw new IllegalArgumentException(owner(name) + " isn't watched, so there's nothing to exclude");
        }
        WatchScope existing = find(WatchScope.Kind.repo, name);
        if (existing != null) all().remove(existing);
        add(WatchScope.Kind.repo, name, WatchScope.Mode.exclude);
    }

    /** Removes a row. Removing an owner also drops its exclusions, which mean nothing without it. */
    public void remove(long id) {
        WatchScope row = all().stream().filter(w -> w.id == id).findFirst().orElse(null);
        if (row == null) return;
        if (row.kind == WatchScope.Kind.owner) {
            all().removeIf(w -> w.kind == WatchScope.Kind.repo && w.mode == WatchScope.Mode.exclude
                    && w.name.startsWith(row.name + "/"));
        }
        all().remove(row);
        save();
    }

    private WatchScope find(WatchScope.Kind kind, String name) {
        return all().stream().filter(w -> w.kind == kind && w.name.equals(name)).findFirst().orElse(null);
    }

    private void add(WatchScope.Kind kind, String name, WatchScope.Mode mode) {
        WatchScope w = new WatchScope();
        w.id = all().stream().mapToLong(x -> x.id).max().orElse(0) + 1;
        w.kind = kind;
        w.name = name;
        w.mode = mode;
        all().add(w);
        save();
    }

    private static String owner(String repo) {
        return repo.substring(0, repo.indexOf('/'));
    }

    /** An owner/repo as typed or pasted (a GitHub URL works too), lower case; refuses anything else. */
    public static String repoName(String raw) {
        String name = normalise(raw);
        if (!REPO.matcher(name).matches()) throw new IllegalArgumentException("'" + raw + "' is not an owner/repo");
        return name;
    }

    private static String normalise(String raw) {
        if (raw == null) throw new IllegalArgumentException("Enter an org, a user, or an owner/repo");
        String s = raw.trim().toLowerCase(Locale.ROOT);
        // Accept a pasted URL too: https://github.com/quarkusio/quarkus
        s = s.replaceFirst("^https?://github\\.com/", "").replaceFirst("/+$", "");
        return s;
    }
}
