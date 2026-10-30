package io.github.houseelves.github.board;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import java.nio.file.Path;

import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.elf.Live;
import io.github.houseelves.github.elf.Log;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.ignore.IgnoreService;
import io.github.houseelves.github.scope.Scope;
import io.github.houseelves.github.scope.ScopeService;
import io.github.houseelves.github.settings.Settings;

/**
 * Builds the board. What GitHub said is kept in board/cache-{login}.json ({@link Cache}), per account: the elf's refresh fetches it
 * (on its schedule, or when you click Refresh), and everything else builds the board from it without asking
 * GitHub, so a change you make shows at once. Only the first load and a refresh wait for GitHub. The searches
 * run in parallel. Classification always reruns, so a scope change shows at once.
 *
 * <p>Everything you're involved in is fetched unscoped (involves:@me, plus
 * review-requested:@me and reviewed-by:@me for the review lane) and the scope
 * is applied here. That keeps the hidden count exact and avoids GitHub's 256-character
 * query limit on long scope lists.
 */
public class BoardService {

    private static final Log LOG = Log.of(BoardService.class);

    /** How long a load waits for GitHub before giving up. */
    static final Duration FIRST_LOAD_WAIT = Duration.ofSeconds(90);
    /**
     * How long a refresh waits: it's a run of its own, which ends when it gives up (nothing keeps fetching after it), and
     * a big board takes about a minute when GitHub is well, longer when it answers 502/504 and is asked again.
     */
    static final Duration REFRESH_WAIT = Duration.ofMinutes(4);
    static final int MAX_INVOLVED = GitHub.SEARCH_CAP;
    static final int MAX_REVIEW = 300;

    private final GitHub github;
    private final ScopeService scopes;
    private final Settings settings;
    private final Live live;
    private final IgnoreService ignores;
    private final Clock clock;
    private final Map<String, BoardClassifier.Fetched> cache = new ConcurrentHashMap<>();
    /** Per login: PRs that moved on since your review, from the last fetch. */
    private final Map<String, Map<String, String>> sinceReview = new ConcurrentHashMap<>();
    /** Per login: others' PRs you reviewed, and when you last did, from the last fetch. */
    private final Map<String, Map<String, Instant>> reviewedAt = new ConcurrentHashMap<>();
    private final Map<String, Map<String, GitHub.Feedback>> feedback = new ConcurrentHashMap<>();
    /** Per login: Dependabot PRs you asked to rebase, at which head. Kept until a fetch shows the head moved. */
    private final Map<String, Map<String, Board.Rebase>> rebasing = new ConcurrentHashMap<>();

    /** login -> issue key -> open PRs that fix or mention it, from the last fetch. */
    private final Map<String, Map<String, List<GitHub.LinkedPr>>> linkedPrs = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Path boardDir;
    /** board/cache.json: the one account's cache, before there could be more. */
    private final Path cacheFile;
    private boolean loaded;

    /**
     * board/cache-{login}.json: what this service kept in memory in the desk, for one account: the last fetch, and
     * what came with it.
     */
    public record Cache(BoardClassifier.Fetched fetched, Map<String, String> sinceReview, Map<String, Instant> reviewedAt,
            Map<String, GitHub.Feedback> feedback, Map<String, Board.Rebase> rebasing,
            Map<String, List<GitHub.LinkedPr>> linkedPrs) {
    }

    public BoardService(GitHub github, ScopeService scopes, Settings settings, Live live,
            IgnoreService ignores, Path boardDir) {
        this(github, scopes, settings, live, ignores, boardDir, Clock.systemUTC());
    }

    BoardService(GitHub github, ScopeService scopes, Settings settings, Live live,
            IgnoreService ignores, Path boardDir, Clock clock) {
        this.github = github;
        this.scopes = scopes;
        this.settings = settings;
        this.live = live;
        this.ignores = ignores;
        this.clock = clock;
        this.boardDir = boardDir;
        this.cacheFile = boardDir.resolve("cache.json");
    }

    /**
     * The cache files, read once per run: board/cache-{login}.json for each account. A cache.json from before the
     * board had more than one account is moved to its account's name.
     */
    private synchronized void load() {
        if (loaded) return;
        loaded = true;
        Cache legacy = Json.read(cacheFile, Cache.class, null);
        if (legacy != null && legacy.fetched() != null) {
            String login = legacy.fetched().login();
            if (!java.nio.file.Files.exists(cacheFile(login))) take(legacy);
            save(login);
        }
        try {
            java.nio.file.Files.deleteIfExists(cacheFile);
        } catch (java.io.IOException e) {
            LOG.warnf("Couldn't remove the old %s: %s", cacheFile, e.getMessage());
        }
        if (!java.nio.file.Files.isDirectory(boardDir)) return;
        try (var files = java.nio.file.Files.list(boardDir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("cache-[a-z0-9-]+\\.json")).toList()) {
                Cache c = Json.read(f, Cache.class, null);
                if (c != null && c.fetched() != null) take(c);
            }
        } catch (java.io.IOException e) {
            LOG.warnf("Couldn't read the board's cache: %s", e.getMessage());
        }
    }

    private void take(Cache c) {
        String login = c.fetched().login();
        cache.put(login, c.fetched());
        if (c.sinceReview() != null) sinceReview.put(login, c.sinceReview());
        if (c.reviewedAt() != null) reviewedAt.put(login, c.reviewedAt());
        if (c.feedback() != null) feedback.put(login, c.feedback());
        if (c.rebasing() != null) rebasing.put(login, new ConcurrentHashMap<>(c.rebasing()));
        if (c.linkedPrs() != null) linkedPrs.put(login, c.linkedPrs());
    }

    private Path cacheFile(String login) {
        return boardDir.resolve("cache-" + login.toLowerCase(Locale.ROOT) + ".json");
    }

    /** Writes the cache file for {@code login}. */
    private synchronized void save(String login) {
        BoardClassifier.Fetched f = cache.get(login);
        if (f == null) return;
        Json.write(cacheFile(login), new Cache(f, sinceReview.getOrDefault(login, Map.of()), reviewedAt.getOrDefault(login, Map.of()),
                feedback.getOrDefault(login, Map.of()), Map.copyOf(rebasing.getOrDefault(login, Map.of())),
                linkedPrs.getOrDefault(login, Map.of())));
    }

    /** An account taken off the board: what was fetched for it goes, so its items leave the board. */
    public synchronized void drop(String login) {
        load();
        for (Map<String, ?> m : List.of(cache, sinceReview, reviewedAt, feedback, rebasing, linkedPrs)) m.remove(login);
        try {
            java.nio.file.Files.deleteIfExists(cacheFile(login));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Couldn't remove " + cacheFile(login), e);
        }
        live.boardUpdated();
    }

    /** Something is cached for {@code login}: the board can be built without asking GitHub. */
    public boolean cached(String login) {
        load();
        return cache.containsKey(login);
    }

    /**
     * The board. With {@code refresh}, fetches GitHub now and keeps what it said. Without it, answers from the
     * cache, however old (the elf's refresh keeps it current); it only fetches when there is nothing cached yet.
     */
    public Board board(String login, String token, boolean refresh) {
        load();
        BoardClassifier.Fetched cached = cache.get(login);
        BoardClassifier.Fetched fetched;
        boolean refreshing = false;
        if (refresh || cached == null) {
            fetched = awaitAtMost(refetch(login, token), refresh ? REFRESH_WAIT : FIRST_LOAD_WAIT);
        } else {
            fetched = cached;
        }
        return classify(fetched, refreshing);
    }

    /** The repos you maintain, lower case. */
    private Set<String> maintained() {
        Set<String> repos = new HashSet<>();
        settings.alwaysInvolved().forEach(r -> repos.add(r.toLowerCase(Locale.ROOT)));
        return repos;
    }

    /** What a fetch shows, without keeping it or bringing back ignored items: the elf's dry run. */
    public Board preview(String login, String token) {
        // A dry run: what it found is shown, not kept.
        return classify(read(login, token).fetched(), false);
    }

    private Board classify(BoardClassifier.Fetched fetched, boolean refreshing) {
        String login = fetched.login();
        return BoardClassifier.classify(fetched, scopes.scope(), settings.refreshMinutes(), ignores.keys(),
                maintained()).refreshing(refreshing).returned(ignores.recentlyReturned())
                .sinceReview(sinceReview.getOrDefault(login, Map.of()))
                .reviewedAt(reviewedAt.getOrDefault(login, Map.of()))
                .feedback(feedback.getOrDefault(login, Map.of()))
                .rebasing(Map.copyOf(rebasing.getOrDefault(login, Map.of())))
                .linkedPrs(linkedPrs.getOrDefault(login, Map.of()));
    }

    /**
     * An owner (org or user) you could watch.
     *
     * @param items     open items involving you in that owner's repos
     * @param member    you belong to this org
     * @param preselect ticked on the first-run screen: an org you belong to that has items
     */
    public record Suggestion(String owner, int items, boolean member, boolean preselect) {
    }

    /** Owners not yet watched, busiest first, followed by your other orgs. */
    public List<Suggestion> suggestions(String login, String token) {
        load();
        BoardClassifier.Fetched cached = cache.get(login);
        BoardClassifier.Fetched f = cached != null ? cached : await(refetch(login, token));
        Scope scope = scopes.scope();
        List<String> orgs = github.viewerOrgs(token);
        Set<String> members = new LinkedHashSet<>();
        orgs.forEach(o -> members.add(o.toLowerCase(Locale.ROOT)));

        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> display = new HashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Item> items = new ArrayList<>(f.reviewRequested());
        items.addAll(f.reviewedByMe());
        items.addAll(f.involved());
        for (Item i : items) {
            if (!seen.add(i.key()) || scope.covers(i.repo())) continue;
            String owner = i.owner().toLowerCase(Locale.ROOT);
            counts.merge(owner, 1, Integer::sum);
            display.putIfAbsent(owner, i.owner());
        }
        for (String org : orgs) display.putIfAbsent(org.toLowerCase(Locale.ROOT), org);

        boolean firstRun = scope.isEmpty();
        return display.entrySet().stream()
                .filter(e -> !scope.owners().contains(e.getKey()))
                .map(e -> {
                    int n = counts.getOrDefault(e.getKey(), 0);
                    boolean member = members.contains(e.getKey());
                    return new Suggestion(e.getValue(), n, member, firstRun && member && n > 0);
                })
                .sorted(Comparator.comparingInt(Suggestion::items).reversed()
                        .thenComparing(Suggestion::owner, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Drops an item from the cached board right away, e.g. after you close it; the next fetch confirms it. */
    public void forget(String login, String itemKey) {
        load();
        cache.computeIfPresent(login, (l, f) -> new BoardClassifier.Fetched(f.login(), f.at(),
                without(f.involved(), itemKey), without(f.reviewRequested(), itemKey),
                without(f.reviewedByMe(), itemKey), f.truncated()));
        save(login);
        live.boardUpdated();
    }

    /**
     * Sets one item's CI state in the cached board, e.g. PENDING right after a re-run, so the board doesn't keep
     * showing the old failure until the next fetch. Open boards are told to reload.
     */
    /** You asked Dependabot to rebase: the row waits for its push instead of offering to ask again. */
    public void markRebasing(String login, String itemKey) {
        load();
        BoardClassifier.Fetched f = cache.get(login);
        String sha = f == null ? null : java.util.stream.Stream.of(f.involved(), f.reviewRequested(), f.reviewedByMe())
                .flatMap(List::stream).filter(i -> i.key().equals(itemKey)).map(Item::headSha).filter(Objects::nonNull)
                .findFirst().orElse(null);
        if (sha == null) return;
        rebasing.computeIfAbsent(login, l -> new ConcurrentHashMap<>()).put(itemKey, new Board.Rebase(sha, clock.instant()));
        save(login);
        live.boardUpdated();
    }

    public void markCi(String login, String itemKey, String ci) {
        load();
        cache.computeIfPresent(login, (l, f) -> new BoardClassifier.Fetched(f.login(), f.at(),
                withCi(f.involved(), itemKey, ci), withCi(f.reviewRequested(), itemKey, ci),
                withCi(f.reviewedByMe(), itemKey, ci), f.truncated()));
        save(login);
        live.boardUpdated();
    }

    /** Your push went out: the PR's head is the pushed commit, and CI runs again. */
    public void markPushed(String login, String itemKey, String headSha) {
        load();
        cache.computeIfPresent(login, (l, f) -> new BoardClassifier.Fetched(f.login(), f.at(),
                withHead(f.involved(), itemKey, headSha), withHead(f.reviewRequested(), itemKey, headSha),
                withHead(f.reviewedByMe(), itemKey, headSha), f.truncated()));
        markCi(login, itemKey, "PENDING");
    }

    private static List<Item> withHead(List<Item> items, String key, String sha) {
        return items.stream().map(i -> i.key().equals(key) ? i.withHead(sha) : i).toList();
    }

    /**
     * Reads one item from GitHub now and puts it in the cached board in place of the old copy (or drops it once
     * it's closed or merged), so a change you just made shows without waiting for the next fetch. Open boards are
     * told to reload. Returns the fresh item, or null when it's no longer open.
     */
    public Item refreshItem(String login, String token, String itemKey) {
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        Item fresh = github.item(token, repo, number);
        if (fresh != null && fresh.isPr()) {
            GitHub.PrDetails d = github.prDetails(token, List.of(itemKey)).get(itemKey);
            if (d != null) fresh = fresh.withDetails(d.mergeState(), d.awaitingApproval(), d.passedOnRerun());
        }
        Item item = fresh;
        load();
        BoardClassifier.Fetched before = cache.get(login);
        cache.computeIfPresent(login, (l, f) -> item == null
                ? new BoardClassifier.Fetched(f.login(), f.at(), without(f.involved(), itemKey),
                        without(f.reviewRequested(), itemKey), without(f.reviewedByMe(), itemKey), f.truncated())
                : new BoardClassifier.Fetched(f.login(), f.at(), replaced(f.involved(), item),
                        replaced(f.reviewRequested(), item), replaced(f.reviewedByMe(), item), f.truncated()));
        if (cache.get(login) != before) {
            save(login);
            live.boardUpdated();
        }
        return item;
    }

    private static List<Item> replaced(List<Item> items, Item fresh) {
        return items.stream().map(i -> i.key().equals(fresh.key()) ? fresh : i).toList();
    }

    /** Adds you to one item's assignees in the cached board (an issue moves to Assigned issues). */
    /** You asked them to review again: the board shows it waiting on them, until the next refresh says. */
    public void markReviewRequested(String login, String itemKey, List<String> logins) {
        feedback.computeIfPresent(login, (l, said) -> {
            Map<String, GitHub.Feedback> next = new HashMap<>(said);
            next.computeIfPresent(itemKey, (k, f) -> f.asked(logins));
            return next;
        });
        save(login);
        live.boardUpdated();
    }

    public void markAssigned(String login, String itemKey) {
        load();
        cache.computeIfPresent(login, (l, f) -> new BoardClassifier.Fetched(f.login(), f.at(),
                withAssignee(f.involved(), itemKey, login), withAssignee(f.reviewRequested(), itemKey, login),
                withAssignee(f.reviewedByMe(), itemKey, login), f.truncated()));
        save(login);
        live.boardUpdated();
    }

    private static List<Item> withAssignee(List<Item> items, String key, String login) {
        return items.stream().map(i -> i.key().equals(key) ? i.withAssignee(login) : i).toList();
    }

    private static List<Item> withCi(List<Item> items, String key, String ci) {
        return items.stream().map(i -> i.key().equals(key) ? i.withCi(ci) : i).toList();
    }

    private static List<Item> without(List<Item> items, String key) {
        return items.stream().filter(i -> !i.key().equals(key)).toList();
    }

    /** Fetches now, and keeps what GitHub said in the cache file. */
    CompletableFuture<BoardClassifier.Fetched> refetch(String login, String token) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return keep(read(login, token));
            } catch (RuntimeException e) {
                LOG.warnf("Fetching the board for %s failed: %s", login, e.getMessage());
                throw e;
            }
        }, pool);
    }

    /**
     * What a refresh read from GitHub, none of it kept yet: the board's items, the ignored items with new activity
     * for you ({@code back}), what moved since your reviews, the PRs' heads, and the PRs linked to issues (null if
     * that failed). Read without the elf's lock, which a click waits for; {@link #keep} keeps it, under the lock.
     */
    public record Fetch(BoardClassifier.Fetched fetched, Map<String, String> back, Map<String, String> sinceReview,
            Map<String, Instant> reviewedAt, Map<String, GitHub.Feedback> feedback, Map<String, String> heads,
            Map<String, List<GitHub.LinkedPr>> linkedPrs) {
    }

    /** The refresh's read of GitHub, up to its wait: nothing changes here, so it runs without the elf's lock. */
    public Fetch fetch(String login, String token) {
        return awaitAtMost(async(() -> read(login, token)), REFRESH_WAIT);
    }

    /** Keeps what a refresh read: the board as the cache has it from now on, under the elf's lock. */
    public Board board(Fetch f) {
        load();
        return classify(keep(f), false);
    }

    private BoardClassifier.Fetched keep(Fetch f) {
        String login = f.fetched().login();
        f.back().forEach(ignores::bringBack);
        sinceReview.put(login, f.sinceReview());
        reviewedAt.put(login, f.reviewedAt());
        feedback.put(login, f.feedback());
        // Dependabot's rebase is done once the head moved.
        Map<String, Board.Rebase> asked = rebasing.get(login);
        if (asked != null) asked.entrySet().removeIf(e -> !e.getValue().sha().equals(f.heads().get(e.getKey())));
        if (f.linkedPrs() != null) linkedPrs.put(login, f.linkedPrs());
        cache.put(login, f.fetched());
        save(login);
        live.boardUpdated();
        return f.fetched();
    }

    /** The three searches, in parallel, and the PRs' details: what GitHub says now, nothing kept. */
    private Fetch read(String login, String token) {
        Instant now = clock.instant();
        var involved = async(() -> github.search(token, "is:open involves:@me archived:false sort:updated-desc",
                MAX_INVOLVED));
        var review = async(() -> github.search(token,
                "is:open is:pr review-requested:@me archived:false sort:updated-desc", MAX_REVIEW));
        // Reviewing a PR removes GitHub's review request, so PRs you've reviewed come from their own search.
        var reviewed = async(() -> github.search(token,
                "is:open is:pr reviewed-by:@me archived:false sort:updated-desc", MAX_REVIEW));
        // Repos you maintain: every open PR and issue, whether GitHub counts you as involved or not.
        List<String> maintainedRepos = settings.alwaysInvolved();
        var maintained = async(() -> maintained(token, maintainedRepos));
        // Ignored items come back when someone requests your review or mentions you after you ignored them.
        Map<String, String> back = Map.of();
        try {
            back = github.activityForMe(token, login, ignores.ignoredSince());
        } catch (Exception e) {
            LOG.warnf("Checking ignored items for new activity failed: %s", e.getMessage());
        }
        GitHub.SearchResult inv = await(involved);
        GitHub.SearchResult rev = await(review);
        GitHub.SearchResult done = await(reviewed);
        GitHub.SearchResult own = await(maintained);
        boolean truncated = inv.truncated() || rev.truncated() || done.truncated() || own.truncated();
        // Their PRs count as review requests, the rest as involved.
        inv = new GitHub.SearchResult(concat(inv.items(), own.items().stream().filter(i -> !i.isPr()).toList()), 0);
        rev = new GitHub.SearchResult(concat(rev.items(), own.items().stream().filter(Item::isPr).toList()), 0);

        // Merge state and runs waiting for approval: too heavy for the searches, so read for the PRs only, in
        // parallel batches. If that fails, the board still shows (without Merge, which needs the merge state).
        // Issues you're on: is there already a PR for them? In parallel with the PR details.
        List<String> issueKeys = inv.items().stream().filter(i -> !i.isPr()).map(Item::key).distinct().toList();
        var links = async(() -> github.linkedPrs(token, issueKeys));
        Map<String, GitHub.PrDetails> details = Map.of();
        try {
            details = prDetails(token, login, inv.items(), rev.items(), done.items());
        } catch (Exception e) {
            LOG.warnf("Reading PR merge states failed: %s", e.getMessage());
        }
        Map<String, Instant> reviewTimes = new HashMap<>();
        details.forEach((key, d) -> {
            if (d.myReviewAt() != null) reviewTimes.put(key, d.myReviewAt());
        });
        Map<String, GitHub.Feedback> said = new HashMap<>();
        details.forEach((key, d) -> {
            if (d.feedback() != null) said.put(key, d.feedback());
        });
        Map<String, String> heads = new HashMap<>();
        java.util.stream.Stream.of(inv.items(), rev.items(), done.items()).flatMap(List::stream)
                .filter(i -> i.headSha() != null).forEach(i -> heads.putIfAbsent(i.key(), i.headSha()));
        Map<String, List<GitHub.LinkedPr>> linked = null;
        try {
            linked = await(links);
        } catch (Exception e) {
            LOG.warnf("Looking for PRs linked to issues failed: %s", e.getMessage());
        }
        return new Fetch(new BoardClassifier.Fetched(login, now, enrich(inv.items(), details), enrich(rev.items(), details),
                enrich(done.items(), details), truncated), back, sinceYourReview(login, details, inv.items(), rev.items(),
                done.items()), reviewTimes, said, heads, linked);
    }

    /** GitHub's search query is at most 256 characters, so a long list of repos goes in several searches. */
    static final int MAX_QUERY = 250;

    /** Every open PR and issue in these repos. */
    private GitHub.SearchResult maintained(String token, List<String> repos) {
        List<Item> items = new ArrayList<>();
        boolean truncated = false;
        for (String q : maintainedQueries(repos)) {
            try {
                GitHub.SearchResult r = github.search(token, q, MAX_REVIEW);
                items.addAll(r.items());
                truncated |= r.truncated();
            } catch (Exception e) {
                // A repo renamed or deleted since you added it: the rest of the board still shows.
                LOG.warnf("Searching the repos you maintain failed (%s): %s", q, e.getMessage());
            }
        }
        return new GitHub.SearchResult(List.copyOf(items), truncated ? Integer.MAX_VALUE : items.size());
    }

    static List<String> maintainedQueries(List<String> repos) {
        String base = "is:open archived:false sort:updated-desc";
        List<String> queries = new ArrayList<>();
        StringBuilder q = new StringBuilder(base);
        for (String repo : repos) {
            if (q.length() + 6 + repo.length() > MAX_QUERY && q.length() > base.length()) {
                queries.add(q.toString());
                q = new StringBuilder(base);
            }
            q.append(" repo:").append(repo);
        }
        if (q.length() > base.length()) queries.add(q.toString());
        return queries;
    }

    private static List<Item> concat(List<Item> a, List<Item> b) {
        if (b.isEmpty()) return a;
        List<Item> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    /**
     * Others' PRs you reviewed that moved on since: new commits (the head isn't the commit you reviewed), or other
     * activity (updated more than a couple of minutes after your review).
     */
    @SafeVarargs
    static Map<String, String> sinceYourReview(String login, Map<String, GitHub.PrDetails> details, List<Item>... lists) {
        Map<String, String> out = new HashMap<>();
        for (List<Item> list : lists) {
            for (Item i : list) {
                GitHub.PrDetails d = details.get(i.key());
                if (d == null || d.myReviewAt() == null || i.author().equalsIgnoreCase(login)) continue;
                if (d.myReviewedSha() != null && i.headSha() != null && !d.myReviewedSha().equals(i.headSha())) {
                    out.put(i.key(), "New commits since your review");
                } else if (i.updatedAt() != null && i.updatedAt().isAfter(d.myReviewAt().plus(Duration.ofMinutes(2)))) {
                    out.put(i.key(), "Updated since your review");
                }
            }
        }
        return out;
    }

    @SafeVarargs
    private Map<String, GitHub.PrDetails> prDetails(String token, String login, List<Item>... lists) {
        List<String> keys = new ArrayList<>(new LinkedHashSet<>(Arrays.stream(lists).flatMap(List::stream)
                .filter(Item::isPr).map(Item::key).toList()));
        List<CompletableFuture<Map<String, GitHub.PrDetails>>> parts = new ArrayList<>();
        for (int from = 0; from < keys.size(); from += GitHub.DETAILS_PER_QUERY) {
            List<String> chunk = keys.subList(from, Math.min(keys.size(), from + GitHub.DETAILS_PER_QUERY));
            parts.add(async(() -> github.prDetails(token, chunk, login)));
        }
        Map<String, GitHub.PrDetails> all = new HashMap<>();
        parts.forEach(p -> all.putAll(await(p)));
        return all;
    }

    private static List<Item> enrich(List<Item> items, Map<String, GitHub.PrDetails> details) {
        return items.stream().map(i -> {
            GitHub.PrDetails d = details.get(i.key());
            return d == null ? i : i.withDetails(d.mergeState(), d.awaitingApproval(), d.passedOnRerun());
        }).toList();
    }

    private <T> CompletableFuture<T> async(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, pool);
    }

    /** Waits up to {@code max}; past that the fetch keeps going in the background (open pages reload when it lands). */
    private static <T> T awaitAtMost(CompletableFuture<T> f, Duration max) {
        try {
            return f.get(max.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("GitHub is slow to answer right now. The board will appear as soon as it does.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** join(), rethrowing the real failure rather than a CompletionException. */
    private static <T> T await(CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }
}
