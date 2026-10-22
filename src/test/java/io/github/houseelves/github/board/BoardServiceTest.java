package io.github.houseelves.github.board;

import static io.github.houseelves.github.Items.pr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.ignore.IgnoreService;
import io.github.houseelves.github.Items;
import io.github.houseelves.github.elf.Live;
import io.github.houseelves.github.scope.Scope;
import io.github.houseelves.github.scope.ScopeService;
import io.github.houseelves.github.settings.Settings;

class BoardServiceTest {

    @TempDir
    Path dir;

    GitHub github = mock(GitHub.class);
    ScopeService scopes = mock(ScopeService.class);
    Settings settings = mock(Settings.class);
    Live live = mock(Live.class);
    IgnoreService ignores = mock(IgnoreService.class);
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-07T07:00:00Z"));
    Clock clock = new Clock() {
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now.get(); }
    };
    BoardService service;

    @BeforeEach
    void setUp() {
        when(scopes.scope()).thenReturn(new Scope(Set.of("quarkusio"), Set.of(), Set.of()));
        when(settings.refreshMinutes()).thenReturn(5);
        when(github.search(anyString(), contains("review-requested"), anyInt()))
                .thenReturn(new GitHub.SearchResult(List.of(), 0));
        when(github.search(anyString(), contains("reviewed-by"), anyInt()))
                .thenReturn(new GitHub.SearchResult(List.of(), 0));
        when(ignores.keys()).thenReturn(Set.of());
        service = new BoardService(github, scopes, settings, live, ignores, dir, clock);
    }

    /** The next run of the elf: it reads board/cache.json. */
    BoardService again() {
        return new BoardService(github, scopes, settings, live, ignores, dir, clock);
    }

    void involvedReturns(Item... items) {
        when(github.search(anyString(), contains("involves:@me"), anyInt()))
                .thenReturn(new GitHub.SearchResult(List.of(items), items.length));
    }

    List<String> keys(Board b) {
        return b.lanes().stream().flatMap(l -> l.items().stream()).map(Item::key).toList();
    }

    @Test
    void firstLoadWaitsThenFreshCacheAnswersWithoutGitHub() {
        involvedReturns(pr("quarkusio/quarkus", 1, "x"));
        Board first = service.board("me", "t", false);
        assertEquals(List.of("quarkusio/quarkus#1"), keys(first));
        assertFalse(first.refreshing());

        now.set(now.get().plusSeconds(60));
        Board again = service.board("me", "t", false);
        assertFalse(again.refreshing());
        verify(github, times(1)).search(eq("t"), contains("involves:@me"), anyInt());
    }

    @Test
    void theCacheFileAnswersTheNextRunHoweverOldAndARefreshFetchesAgain() {
        involvedReturns(pr("quarkusio/quarkus", 1, "x"));
        service.board("me", "t", false);
        assertTrue(Files.isRegularFile(dir.resolve("cache.json")));

        involvedReturns(pr("quarkusio/quarkus", 2, "x"));
        now.set(now.get().plusSeconds(3600)); // the elf's schedule refreshes it, not a look at the board
        Board cached = again().board("me", "t", false);
        assertFalse(cached.refreshing());
        assertEquals(List.of("quarkusio/quarkus#1"), keys(cached));
        verify(github, times(1)).search(eq("t"), contains("involves:@me"), anyInt());

        assertEquals(List.of("quarkusio/quarkus#2"), keys(again().board("me", "t", true)));
        assertEquals(List.of("quarkusio/quarkus#2"), keys(again().board("me", "t", false)));
        verify(github, times(2)).search(eq("t"), contains("involves:@me"), anyInt());
    }

    @Test
    void aDryRunShowsWhatGitHubSaysAndKeepsNothing() {
        involvedReturns(pr("quarkusio/quarkus", 1, "x"));
        when(ignores.ignoredSince()).thenReturn(Map.of("quarkusio/quarkus#9", Instant.EPOCH));
        when(github.activityForMe(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(Map.of("quarkusio/quarkus#9", "You were mentioned"));
        assertEquals(List.of("quarkusio/quarkus#1"), keys(service.preview("me", "t")));
        assertFalse(Files.exists(dir.resolve("cache.json")));
        assertFalse(service.cached("me"));
        verify(ignores, never()).bringBack(anyString(), anyString());
    }

    @Test
    void aDependabotMvnpmBumpIsAPrLikeAnyOtherWithNoSearchOfItsOwn() {
        involvedReturns(Items.pr("quarkusio/quarkus", 9, "dependabot", "Bump org.mvnpm:lit from 3.3.1 to 3.3.3",
                "dependabot/maven/org.mvnpm-lit-3.3.3", "abc"));
        Board b = service.board("me", "t", false);
        assertEquals(List.of("quarkusio/quarkus#9"), keys(b));
        verify(github, never()).search(anyString(), contains("author:app/dependabot"), anyInt());
    }

    @Test
    void reposYouMaintainBringEveryPrToReviewAndEveryIssueToYou() {
        when(scopes.scope()).thenReturn(new Scope(Set.of("quarkusio", "quarkiverse"), Set.of(), Set.of()));
        when(settings.alwaysInvolved()).thenReturn(List.of("quarkiverse/quarkus-agent-mcp"));
        involvedReturns();
        when(github.search(anyString(), contains("repo:quarkiverse/quarkus-agent-mcp"), anyInt()))
                .thenReturn(new GitHub.SearchResult(List.of(pr("quarkiverse/quarkus-agent-mcp", 5, "someone"),
                        io.github.houseelves.github.Items.issue("quarkiverse/quarkus-agent-mcp", 6, "someone")), 2));
        Board b = service.board("me", "t", false);
        Map<String, List<String>> lanes = new java.util.HashMap<>();
        b.lanes().forEach(l -> lanes.put(l.id(), l.items().stream().map(Item::key).toList()));
        assertEquals(List.of("quarkiverse/quarkus-agent-mcp#5"), lanes.get(BoardClassifier.REVIEW));
        // Nobody has the issue: it's yours to look at, like one assigned to you.
        assertEquals(List.of("quarkiverse/quarkus-agent-mcp#6"), lanes.get(BoardClassifier.ASSIGNED));
    }

    @Test
    void manyMaintainedReposGoInSeveralSearchesUnderGitHubsQueryLimit() {
        List<String> repos = java.util.stream.IntStream.range(0, 20).mapToObj(i -> "quarkiverse/quarkus-extension-" + i).toList();
        List<String> queries = BoardService.maintainedQueries(repos);
        assertTrue(queries.size() > 1);
        assertTrue(queries.stream().allMatch(q -> q.length() <= BoardService.MAX_QUERY));
        assertEquals(20, queries.stream().mapToInt(q -> q.split(" repo:").length - 1).sum());
        assertTrue(BoardService.maintainedQueries(List.of()).isEmpty());
    }

    @Test
    void markCiShowsARerunAsInProgressWithoutWaitingForGitHub() {
        involvedReturns(pr("quarkusio/quarkus", 1, "x").withCi("FAILURE"));
        service.board("me", "t", false);
        service.markCi("me", "quarkusio/quarkus#1", "PENDING");
        Item item = service.board("me", "t", false).lanes().stream().flatMap(l -> l.items().stream()).findFirst().orElseThrow();
        assertEquals("PENDING", item.ci());
        verify(live, atLeast(2)).boardUpdated();
        assertEquals("PENDING", again().board("me", "t", false).lanes().stream().flatMap(l -> l.items().stream())
                .findFirst().orElseThrow().ci()); // kept in the cache file
    }

    @Test
    void aPushYouMadeMovesTheHeadAndCiRunsAgain() {
        involvedReturns(pr("quarkusio/quarkus", 1, "x"));
        service.board("me", "t", false);
        service.markPushed("me", "quarkusio/quarkus#1", "f".repeat(40));
        Item item = again().board("me", "t", false).lanes().stream().flatMap(l -> l.items().stream()).findFirst().orElseThrow();
        assertEquals("f".repeat(40), item.headSha());
        assertEquals("PENDING", item.ci());
        assertEquals("UNKNOWN", item.mergeState());
    }

    @Test
    void prsThatMovedOnSinceYourReviewAreFlagged() {
        Item moved = pr("quarkusio/quarkus", 1, "someone"); // head "sha1"
        Item talkedAbout = pr("quarkusio/quarkus", 2, "someone"); // head "sha2", updated 2026-10-05
        Item quiet = pr("quarkusio/quarkus", 3, "someone");
        Item mine = pr("quarkusio/quarkus", 4, "me");
        Instant before = Instant.parse("2026-10-04T00:00:00Z");
        Map<String, GitHub.PrDetails> d = Map.of(
                moved.key(), new GitHub.PrDetails("CLEAN", false, before, "old-sha"),
                talkedAbout.key(), new GitHub.PrDetails("CLEAN", false, before, "sha2"),
                quiet.key(), new GitHub.PrDetails("CLEAN", false, Instant.parse("2026-10-06T00:00:00Z"), "sha3"),
                mine.key(), new GitHub.PrDetails("CLEAN", false, before, "old"));
        Map<String, String> since = BoardService.sinceYourReview("me", d, List.of(moved, talkedAbout, quiet, mine));
        assertEquals(Map.of("quarkusio/quarkus#1", "New commits since your review",
                "quarkusio/quarkus#2", "Updated since your review"), since);
    }

    @Test
    void forgetDropsAnItemFromTheCache() {
        involvedReturns(pr("quarkusio/quarkus", 1, "x"), pr("quarkusio/quarkus", 2, "x"));
        service.board("me", "t", false);
        service.forget("me", "quarkusio/quarkus#1");
        assertEquals(List.of("quarkusio/quarkus#2"), keys(service.board("me", "t", false)));
    }
}
