package io.github.houseelves.github.board;

import static io.github.houseelves.github.Items.issue;
import static io.github.houseelves.github.Items.pr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.scope.Scope;

class BoardClassifierTest {

    static final String ME = "phillip-kruger";
    static final Scope QUARKUS_AND_SMALLRYE = new Scope(Set.of("quarkusio", "smallrye"), Set.of("mvnpm/mvnpm"),
            Set.of("quarkusio/quarkus-quickstarts"));

    static Board classify(List<Item> involved, List<Item> review, Scope scope) {
        return classify(involved, review, List.of(), scope);
    }

    static Board classify(List<Item> involved, List<Item> review, List<Item> reviewed, Scope scope) {
        var f = new BoardClassifier.Fetched(ME, Instant.parse("2026-10-06T07:00:00Z"), involved, review, reviewed, false);
        return BoardClassifier.classify(f, scope, 5);
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
                List.of(requested, mineAndRequested), QUARKUS_AND_SMALLRYE);

        assertEquals(List.of("quarkusio/quarkus#1"), lane(b, BoardClassifier.REVIEW));
        assertEquals(List.of("smallrye/smallrye-graphql#3", "quarkusio/quarkus#2"), lane(b, BoardClassifier.MINE));
        assertEquals(List.of("quarkusio/quarkus#4"), lane(b, BoardClassifier.ASSIGNED));
        // An assigned PR you didn't write is involvement, not an assigned issue.
        assertEquals(List.of("quarkusio/quarkus#5", "quarkusio/quarkus#6"), lane(b, BoardClassifier.INVOLVED)); // no updatedAt sorts last
        assertTrue(b.hidden().isEmpty());
    }

    @Test
    void anIssueInARepoYouMaintainIsYours() throws Exception {
        Item open = issue("quarkusio/quarkus", 31, "someone");
        Item theirs = issue("quarkusio/quarkus", 32, "someone", "someone-else");
        Item elsewhere = issue("smallrye/smallrye-graphql", 33, "someone");
        BoardClassifier.Fetched f = new BoardClassifier.Fetched(ME, Instant.now(), List.of(open, theirs, elsewhere),
                List.of(), List.of(), false);

        Board b = BoardClassifier.classify(f, QUARKUS_AND_SMALLRYE, 5, Set.of(), Set.of("quarkusio/quarkus"));

        assertEquals(List.of("quarkusio/quarkus#31"), lane(b, BoardClassifier.ASSIGNED));
        // Someone else has it, or it's not your repo: just involved.
        assertEquals(Set.of("quarkusio/quarkus#32", "smallrye/smallrye-graphql#33"), Set.copyOf(lane(b, BoardClassifier.INVOLVED)));
    }

    @Test
    void aPrYouReviewedStaysInNeedsMyReviewAfterTheRequestIsGone() throws Exception {
        Item approvedByMe = pr("quarkusio/quarkus", 11, "someone");
        // GitHub drops the review request when you review: it only shows up in involves and reviewed-by.
        Board b = classify(List.of(approvedByMe), List.of(), List.of(approvedByMe), QUARKUS_AND_SMALLRYE);
        assertEquals(List.of("quarkusio/quarkus#11"), lane(b, BoardClassifier.REVIEW));
        assertEquals(List.of(), lane(b, BoardClassifier.INVOLVED));
    }

    @Test
    void ignoredItemsLeaveTheirLane() throws Exception {
        Item review = pr("quarkusio/quarkus", 21, "someone");
        Item mentioned = issue("quarkusio/quarkus", 22, "someone");
        var f = new BoardClassifier.Fetched(ME, Instant.parse("2026-10-06T07:00:00Z"), List.of(review, mentioned), List.of(review),
                List.of(), false);
        Board b = BoardClassifier.classify(f, QUARKUS_AND_SMALLRYE, 5, Set.of("quarkusio/quarkus#21", "quarkusio/quarkus#22"));
        assertEquals(List.of(), lane(b, BoardClassifier.REVIEW));
        assertEquals(List.of(), lane(b, BoardClassifier.INVOLVED));
        assertEquals(Set.of("quarkusio/quarkus#21", "quarkusio/quarkus#22"),
                Set.copyOf(b.ignored().stream().map(Item::key).toList()));
    }

    @Test
    void outsideScopeIsHiddenIncludingReviewRequests() throws Exception {
        Item excludedRepo = issue("quarkusio/quarkus-quickstarts", 7, "someone");
        Item unwatched = pr("vert-x3/vertx-web", 8, "someone");
        Item watchedRepo = issue("mvnpm/mvnpm", 9, "someone");
        Item otherMvnpmRepo = issue("mvnpm/locker", 10, "someone");

        Board b = classify(List.of(excludedRepo, watchedRepo, otherMvnpmRepo), List.of(unwatched),
                QUARKUS_AND_SMALLRYE);

        assertEquals(List.of(), lane(b, BoardClassifier.REVIEW));
        assertEquals(List.of("mvnpm/mvnpm#9"), lane(b, BoardClassifier.INVOLVED));
        assertEquals(Set.of("quarkusio/quarkus-quickstarts#7", "vert-x3/vertx-web#8", "mvnpm/locker#10"),
                Set.copyOf(b.hidden().stream().map(Item::key).toList()));
    }

    @Test
    void emptyScopeHidesEverything() throws Exception {
        Board b = classify(List.of(issue("quarkusio/quarkus", 1, "x")), List.of(),
                new Scope(Set.of(), Set.of(), Set.of()));
        assertTrue(b.scopeEmpty());
        assertEquals(1, b.hidden().size());
    }

    @Test
    void aDependabotMvnpmBumpIsLeftToTheMvnpmTab() throws Exception {
        Item bump = pr("quarkusio/quarkus", 50124, "dependabot", "Bump org.mvnpm:lit from 3.3.1 to 3.3.3",
                "dependabot/maven/org.mvnpm-lit-3.3.3", "xyz");
        Item other = pr("quarkusio/quarkus", 50125, "dependabot", "Bump io.smallrye:jandex from 3.2.0 to 3.2.1",
                "dependabot/maven/io.smallrye-jandex-3.2.1", "abc");
        Board b = classify(List.of(bump, other), List.of(bump, other), QUARKUS_AND_SMALLRYE);
        assertEquals(List.of("quarkusio/quarkus#50125"), lane(b, BoardClassifier.REVIEW));
        assertTrue(b.hidden().isEmpty());
        assertTrue(b.ignored().isEmpty());
    }
}
