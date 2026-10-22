package io.github.houseelves.github.board;

import static io.github.houseelves.github.Items.issue;
import static io.github.houseelves.github.Items.mvnpmBump;
import static io.github.houseelves.github.Items.pr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.scope.Scope;

class BoardClassifierTest {

    static final String ME = "phillip-kruger";
    static final List<String> ELF_REPOS = List.of("quarkusio/quarkus", "smallrye/smallrye-open-api");
    static final Scope QUARKUS_AND_SMALLRYE = new Scope(Set.of("quarkusio", "smallrye"), Set.of("mvnpm/mvnpm"),
            Set.of("quarkusio/quarkus-quickstarts"));

    static JsonNode state(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    static Board classify(List<Item> involved, List<Item> review, List<Item> mvnpm, Scope scope, JsonNode state) {
        return classify(involved, review, List.of(), mvnpm, scope, state);
    }

    static Board classify(List<Item> involved, List<Item> review, List<Item> reviewed, List<Item> mvnpm, Scope scope,
            JsonNode state) {
        var f = new BoardClassifier.Fetched(ME, Instant.parse("2026-10-06T07:00:00Z"), involved, review, reviewed, mvnpm,
                false);
        return BoardClassifier.classify(f, scope, ELF_REPOS, true, state, 5);
    }

    static List<String> lane(Board b, String id) {
        return b.lanes().stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow()
                .items().stream().map(Item::key).toList();
    }

    @Test
    void sortsIntoLanesByPrecedence() throws Exception {
        Item requested = pr("quarkusio/quarkus", 1, "someone");
        Item mine = pr("quarkusio/quarkus", 2, ME);
        Item mineAndRequested = pr("smallrye/smallrye-graphql", 3, ME); // your own PR stays in My PRs
        Item assigned = issue("quarkusio/quarkus", 4, "someone", ME);
        Item mentioned = issue("quarkusio/quarkus", 5, "someone");
        Item assignedPr = new Item("quarkusio/quarkus#6", "quarkusio/quarkus", 6, Item.Kind.PR, "t", "u", "x",
                List.of(ME), null, null, 0, false, null, null, null, null, null, null, false);

        Board b = classify(List.of(mine, mineAndRequested, assigned, mentioned, assignedPr),
                List.of(requested, mineAndRequested), List.of(), QUARKUS_AND_SMALLRYE, state("{}"));

        assertEquals(List.of("quarkusio/quarkus#1"), lane(b, BoardClassifier.REVIEW));
        assertEquals(List.of("smallrye/smallrye-graphql#3", "quarkusio/quarkus#2"), lane(b, BoardClassifier.MINE));
        assertEquals(List.of("quarkusio/quarkus#4"), lane(b, BoardClassifier.ASSIGNED));
        // An assigned PR you didn't write is involvement, not an assigned issue.
        assertEquals(List.of("quarkusio/quarkus#5", "quarkusio/quarkus#6"), lane(b, BoardClassifier.INVOLVED)); // no updatedAt sorts last
        assertTrue(b.hidden().isEmpty());
    }

    @Test
    void aPrYouReviewedStaysInNeedsMyReviewAfterTheRequestIsGone() throws Exception {
        Item approvedByMe = pr("quarkusio/quarkus", 11, "someone");
        // GitHub drops the review request when you review: it only shows up in involves and reviewed-by.
        Board b = classify(List.of(approvedByMe), List.of(), List.of(approvedByMe), List.of(), QUARKUS_AND_SMALLRYE,
                state("{}"));
        assertEquals(List.of("quarkusio/quarkus#11"), lane(b, BoardClassifier.REVIEW));
        assertEquals(List.of(), lane(b, BoardClassifier.INVOLVED));
    }

    @Test
    void ignoredItemsLeaveTheirLaneIncludingMvnpm() throws Exception {
        Item review = pr("quarkusio/quarkus", 21, "someone");
        Item bump = mvnpmBump("quarkusio/quarkus", 22, "s");
        var f = new BoardClassifier.Fetched(ME, Instant.parse("2026-10-06T07:00:00Z"), List.of(review), List.of(review),
                List.of(), List.of(bump), false);
        Board b = BoardClassifier.classify(f, QUARKUS_AND_SMALLRYE, ELF_REPOS, true, state("{}"), 5,
                Set.of("quarkusio/quarkus#21", "quarkusio/quarkus#22"));
        assertEquals(List.of(), lane(b, BoardClassifier.REVIEW));
        assertTrue(b.mvnpm().rows().isEmpty());
        assertEquals(Set.of("quarkusio/quarkus#21", "quarkusio/quarkus#22"),
                Set.copyOf(b.ignored().stream().map(Item::key).toList()));
    }

    @Test
    void outsideScopeIsHiddenIncludingReviewRequests() throws Exception {
        Item excludedRepo = issue("quarkusio/quarkus-quickstarts", 7, "someone");
        Item unwatched = pr("vert-x3/vertx-web", 8, "someone");
        Item watchedRepo = issue("mvnpm/mvnpm", 9, "someone");
        Item otherMvnpmRepo = issue("mvnpm/locker", 10, "someone");

        Board b = classify(List.of(excludedRepo, watchedRepo, otherMvnpmRepo), List.of(unwatched), List.of(),
                QUARKUS_AND_SMALLRYE, state("{}"));

        assertEquals(List.of(), lane(b, BoardClassifier.REVIEW));
        assertEquals(List.of("mvnpm/mvnpm#9"), lane(b, BoardClassifier.INVOLVED));
        assertEquals(Set.of("quarkusio/quarkus-quickstarts#7", "vert-x3/vertx-web#8", "mvnpm/locker#10"),
                Set.copyOf(b.hidden().stream().map(Item::key).toList()));
    }

    @Test
    void emptyScopeHidesEverything() throws Exception {
        Board b = classify(List.of(issue("quarkusio/quarkus", 1, "x")), List.of(), List.of(),
                new Scope(Set.of(), Set.of(), Set.of()), state("{}"));
        assertTrue(b.scopeEmpty());
        assertEquals(1, b.hidden().size());
    }

    @Test
    void mvnpmBumpsGoToTheirLaneOnlyAndCarryTheElfsVerdict() throws Exception {
        Item checkedNow = mvnpmBump("smallrye/smallrye-open-api", 2696, "abc");
        Item checkedEarlier = mvnpmBump("quarkusio/quarkus", 50120, "newer-sha");
        Item neverChecked = mvnpmBump("quarkusio/quarkus", 50124, "xyz");
        Item npmBump = pr("quarkusio/quarkus", 50125, "dependabot", "Bump io.vertx:vertx-core", "dependabot/maven/io.vertx", "s");
        JsonNode state = state("""
                {"smallrye/smallrye-open-api#2696": {"sha": "abc", "verdict": "SAFE", "approved": true},
                 "50120": {"sha": "old-sha", "verdict": "NOT_SAFE", "approved": false}}
                """);

        // A bump also requesting my review must not show up in "Needs my review".
        Board b = classify(List.of(), List.of(checkedNow, npmBump), List.of(checkedNow, checkedEarlier, neverChecked, npmBump),
                QUARKUS_AND_SMALLRYE, state);

        var rows = b.mvnpm().rows();
        assertEquals(List.of("quarkusio/quarkus#50124", "quarkusio/quarkus#50120", "smallrye/smallrye-open-api#2696"),
                rows.stream().map(r -> r.item().key()).toList());
        assertNull(rows.get(0).check());
        assertEquals("NOT_SAFE", rows.get(1).check().verdict()); // read through the legacy bare-number key
        assertFalse(rows.get(1).current());
        assertTrue(rows.get(2).current());
        assertEquals(List.of("quarkusio/quarkus#50125"), lane(b, BoardClassifier.REVIEW));
    }

    @Test
    void mvnpmBumpInARepoTheElfDoesntWatchIsAnOrdinaryItem() throws Exception {
        Item bump = mvnpmBump("quarkiverse/quarkus-web-bundler", 5, "s");
        Board b = classify(List.of(bump), List.of(), List.of(),
                new Scope(Set.of("quarkiverse"), Set.of(), Set.of()), state("{}"));
        assertEquals(List.of("quarkiverse/quarkus-web-bundler#5"), lane(b, BoardClassifier.INVOLVED));
        assertTrue(b.mvnpm().rows().isEmpty());
    }
}
