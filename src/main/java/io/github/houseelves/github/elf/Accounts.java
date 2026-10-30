package io.github.houseelves.github.elf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * The gh accounts the board works as: GITHUB_ACCOUNT (the elf's setting) first, then the ones added in its
 * Settings, each with a name you gave it (Work, Personal). Kept in state/accounts.json; their tokens come from gh
 * each run, never stored.
 *
 * <p>
 * Each item on the board belongs to the account that found it (state/owners.json, written with the board), and
 * everything done with it is done as that account: {@link #actAs} picks it, and {@link #login} and {@link #token}
 * answer for it from then on.
 */
public class Accounts {

    static final String LOGIN = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})";
    static final String NAME = "[\\p{L}\\p{N} ._&'()-]{1,30}";

    /** An account and what you call it. */
    public record Named(String login, String name) {
    }

    private record Saved(List<Named> accounts) {
    }

    private final Path file;
    private final Path ownersFile;
    private final String primary;
    private final Function<String, String> tokenFor;
    private final Map<String, String> tokens = new HashMap<>();
    private String current;
    private Map<String, String> owners;

    /** @param tokenFor a login's token, from gh (asked for once per run, and only when needed) */
    public Accounts(Path stateDir, String primary, Function<String, String> tokenFor) {
        if (primary == null || !primary.matches(LOGIN)) {
            throw new IllegalStateException("Set the GitHub account in the GitHub elf's settings");
        }
        this.file = stateDir.resolve("accounts.json");
        this.ownersFile = stateDir.resolve("owners.json");
        this.primary = primary;
        this.tokenFor = tokenFor;
        this.current = primary;
    }

    /** The real ones: tokens from {@code gh auth token -u <login>}. */
    public static Accounts real(Home home, String primary) {
        return new Accounts(home.state(), primary, login -> new GhAccount(login).token());
    }

    public String primary() {
        return primary;
    }

    /** Every account, GITHUB_ACCOUNT first, each named (by its login until you name it). */
    public List<Named> all() {
        List<Named> saved = Json.read(file, Saved.class, new Saved(List.of())).accounts();
        List<Named> out = new ArrayList<>();
        out.add(new Named(primary, nameIn(saved, primary)));
        for (Named n : saved == null ? List.<Named>of() : saved) {
            if (out.stream().noneMatch(o -> o.login().equalsIgnoreCase(n.login()))) out.add(new Named(n.login(), nameIn(saved, n.login())));
        }
        return List.copyOf(out);
    }

    public List<String> logins() {
        return all().stream().map(Named::login).toList();
    }

    public boolean has(String login) {
        return login != null && all().stream().anyMatch(n -> n.login().equalsIgnoreCase(login));
    }

    private static String nameIn(List<Named> saved, String login) {
        if (saved != null) {
            for (Named n : saved) {
                if (n.login().equalsIgnoreCase(login) && n.name() != null && !n.name().isBlank()) return n.name();
            }
        }
        return login;
    }

    /** Adds an account gh is logged in to, under {@code name}. */
    public List<Named> add(String login, String name) {
        login = login == null ? "" : login.strip();
        if (!login.matches(LOGIN)) throw new IllegalArgumentException("That isn't a GitHub login: " + login);
        if (has(login)) throw new IllegalArgumentException(login + " is on the board already.");
        String n = name(name, login);
        // Only one gh can give a token for: the elf works with gh's logins and keeps no token of its own.
        token(login);
        List<Named> saved = new ArrayList<>(saved());
        saved.add(new Named(login, n));
        save(saved);
        return all();
    }

    /** Names an account (the first one too). */
    public List<Named> rename(String login, String name) {
        if (!has(login)) throw new IllegalArgumentException(login + " isn't one of the board's accounts.");
        String n = name(name, login);
        List<Named> saved = new ArrayList<>(saved());
        boolean found = false;
        for (int i = 0; i < saved.size(); i++) {
            if (saved.get(i).login().equalsIgnoreCase(login)) {
                saved.set(i, new Named(saved.get(i).login(), n));
                found = true;
            }
        }
        // Only GITHUB_ACCOUNT can be missing from the file: it comes from the elf's settings.
        if (!found) saved.addFirst(new Named(primary, n));
        save(saved);
        return all();
    }

    /** Takes an account off the board. Not GITHUB_ACCOUNT: that one is changed in the elf's settings. */
    public List<Named> remove(String login) {
        if (login != null && login.equalsIgnoreCase(primary)) {
            throw new IllegalArgumentException(primary + " is the elf's own account: change it in the GitHub elf's settings.");
        }
        if (!has(login)) throw new IllegalArgumentException(login + " isn't one of the board's accounts.");
        List<Named> saved = new ArrayList<>(saved());
        saved.removeIf(s -> s.login().equalsIgnoreCase(login));
        save(saved);
        return all();
    }

    private List<Named> saved() {
        List<Named> s = Json.read(file, Saved.class, new Saved(List.of())).accounts();
        return s == null ? List.of() : s;
    }

    private void save(List<Named> saved) {
        Json.write(file, new Saved(saved));
    }

    private static String name(String name, String login) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) return login;
        if (!n.matches(NAME)) throw new IllegalArgumentException("A name is up to 30 letters, digits and spaces: " + n);
        return n;
    }

    // ---- who acts ----

    /** The account everything is done as now. */
    public String login() {
        return current;
    }

    /** Its token. */
    public String token() {
        return token(current);
    }

    public synchronized String token(String login) {
        return tokens.computeIfAbsent(login.toLowerCase(Locale.ROOT), l -> tokenFor.apply(login));
    }

    /** From now on, act as {@code login} (one of the accounts). */
    public void actAs(String login) {
        current = all().stream().filter(n -> n.login().equalsIgnoreCase(login)).map(Named::login).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(login + " isn't one of the board's accounts."));
    }

    /** Acts as the account the item belongs to: the one that found it, else GITHUB_ACCOUNT. */
    public void actFor(String itemKey) {
        String owner = itemKey == null ? null : owners().get(itemKey);
        actAs(owner != null && has(owner) ? owner : primary);
    }

    /** Item key to the account it belongs to, as the board last had it. */
    public synchronized Map<String, String> owners() {
        if (owners == null) owners = Json.read(ownersFile, new TypeReference<Map<String, String>>() {}, Map.of());
        return owners;
    }

    public synchronized void owners(Map<String, String> owners) {
        this.owners = Map.copyOf(owners);
        Json.write(ownersFile, this.owners);
    }
}
