package io.github.houseelves.github.board;

import static io.github.houseelves.github.Items.issue;
import static io.github.houseelves.github.Items.pr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.merge.MergeService;
import io.github.houseelves.github.thread.ThreadView;

class FlowClassifierTest {

    static final String ME = "phillip-kruger";
    static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    /** A PR with the given GitHub state; everything else as {@code Items.pr}. */
    static Item pr2(String author, String ci, String mergeState, String mergeable, boolean approved, boolean draft,
            String reviewDecision) {
        Item p = pr("quarkusio/quarkus", 7, author);
        return new Item(p.key(), p.repo(), p.number(), p.kind(), p.title(), p.url(), p.author(), p.assignees(),
                p.createdAt(), p.updatedAt(), 0, draft, p.headRef(), p.headSha(), ci, reviewDecision, mergeable, mergeState,
                approved);
    }

    static FlowClassifier.Ctx ctx() {
        return ctx(null, null, null, null, List.of());
    }

    static FlowClassifier.Ctx ctx(ThreadView.Summary t, MergeService.View m, String sinceReview, Instant reviewedAt,
            List<GitHub.LinkedPr> links) {
        return new FlowClassifier.Ctx(ME, t, m, sinceReview, reviewedAt, links, NOW);
    }

    static ThreadView.Summary thread(String state, String action, String draftKind) {
        return new ThreadView.Summary("k", state, action, draftKind, NOW, false, null, null);
    }

    static void is(Flow f, String waiting, int step, String next) {
        assertEquals(waiting, f.waiting(), f.say());
        assertEquals(step, f.step(), f.say());
        if (next == null) assertNull(f.next(), f.say());
        else assertEquals(next, f.next().id(), f.say());
    }

    static Item withoutWrite(Item p) {
        return new Item(p.key(), p.repo(), p.number(), p.kind(), p.title(), p.url(), p.author(), p.assignees(),
                p.createdAt(), p.updatedAt(), p.comments(), p.draft(), p.headRef(), p.headSha(), p.ci(), p.reviewDecision(),
                p.mergeable(), p.mergeState(), p.approved(), false, null);
    }

    static List<String> ids(List<Flow.Next> next) {
        return next.stream().map(Flow.Next::id).toList();
    }

    @Test
    void withoutWriteAccessFailedJobsCantBeReRun() {
        // Your own PR: closing and reopening it runs CI again.
        Flow mine = FlowClassifier.flow("mine", withoutWrite(pr2(ME, "FAILURE", "UNSTABLE", "MERGEABLE", false, false, null)), ctx());
        assertTrue(ids(mine.more()).contains("ci-reopen"));
        assertFalse(ids(mine.more()).contains("ci-rerun"));
        // Someone else's: nothing to re-run with.
        Flow theirs = FlowClassifier.flow("review", withoutWrite(pr2("someone", "FAILURE", "UNSTABLE", "MERGEABLE", true, false,
                "APPROVED")), ctx());
        assertFalse(ids(theirs.more()).contains("ci-rerun") || ids(theirs.more()).contains("ci-reopen"));
        // Write access, or not known: re-run as before.
        Flow known = FlowClassifier.flow("mine", pr2(ME, "FAILURE", "UNSTABLE", "MERGEABLE", false, false, null), ctx());
        assertTrue(ids(known.more()).contains("ci-rerun"));
    }

    // ---- my PRs ----

    @Test
    void myPrMovesThroughCiReviewsAndMerge() {
        is(FlowClassifier.flow("mine", pr2(ME, "PENDING", "BLOCKED", "MERGEABLE", false, false, null), ctx()), Flow.CI, 1, null);
        is(FlowClassifier.flow("mine", pr2(ME, "FAILURE", "UNSTABLE", "MERGEABLE", false, false, null), ctx()), Flow.YOU, 1, "ci-why");
        is(FlowClassifier.flow("mine", pr2(ME, "SUCCESS", "CLEAN", "MERGEABLE", false, false, null), ctx()), Flow.OTHERS, 2, null);
        is(FlowClassifier.flow("mine", pr2(ME, "SUCCESS", "CLEAN", "MERGEABLE", false, false, "CHANGES_REQUESTED"), ctx()),
                Flow.YOU, 2, "read-review");
        is(FlowClassifier.flow("mine", pr2(ME, "SUCCESS", "CLEAN", "MERGEABLE", true, false, "APPROVED"), ctx()), Flow.YOU, 3, "merge");
        is(FlowClassifier.flow("mine", pr2(ME, "SUCCESS", "DRAFT", "MERGEABLE", false, true, null), ctx()), Flow.PARKED, 0, "ready");
        is(FlowClassifier.flow("mine", pr2(ME, "SUCCESS", "DIRTY", "CONFLICTING", false, false, null), ctx()), Flow.YOU, 1, "conflicts");
        is(FlowClassifier.flow("mine", pr2(ME, "SUCCESS", "BEHIND", "MERGEABLE", true, false, null), ctx()), Flow.YOU, 1, "update-branch");
    }

    @Test
    void youCanSelfReviewYourPrAtAnyStageEvenWhileCiRuns() {
        for (Item p : List.of(pr2(ME, "PENDING", "BLOCKED", "MERGEABLE", false, false, null),
                pr2(ME, "FAILURE", "UNSTABLE", "MERGEABLE", false, false, null),
                pr2(ME, "SUCCESS", "CLEAN", "MERGEABLE", false, false, null),
                pr2(ME, "SUCCESS", "DRAFT", "MERGEABLE", false, true, null))) {
            Flow f = FlowClassifier.flow("mine", p, ctx());
            assertEquals(1, f.more().stream().filter(n -> n.id().equals("review")).count(), f.say());
        }
        // While the agent works, nothing else is offered.
        Flow working = FlowClassifier.flow("mine", pr2(ME, "PENDING", "BLOCKED", "MERGEABLE", false, false, null),
                ctx(thread("working", "review", null), null, null, null, List.of()));
        is(working, Flow.AGENT, 0, "open");
        assertEquals(List.of(), working.more());
    }

    @Test
    void aSelfReviewWithFixesOffersToMakeThem() {
        Item running = pr2(ME, "PENDING", "BLOCKED", "MERGEABLE", false, false, null);
        ThreadView.Summary answered = new ThreadView.Summary("k", "answered", "review", null, NOW, false, null, null, 3);
        Flow f = FlowClassifier.flow("mine", running, ctx(answered, null, null, null, List.of()));
        is(f, Flow.YOU, 0, "open");
        assertEquals("The agent reviewed it: 3 fixes worth making.", f.say());
        assertEquals("self-fix", f.more().get(0).id());
        // Read: back to CI, the fixes still offered.
        ThreadView.Summary read = new ThreadView.Summary("k", "idle", "review", null, NOW, false, null, null, 3);
        f = FlowClassifier.flow("mine", running, ctx(read, null, null, null, List.of()));
        is(f, Flow.CI, 1, null);
        assertEquals("self-fix", f.more().get(0).id());
        // Nothing to fix: no offer.
        ThreadView.Summary clean = new ThreadView.Summary("k", "answered", "review", null, NOW, false, null, null, 0);
        f = FlowClassifier.flow("mine", running, ctx(clean, null, null, null, List.of()));
        assertEquals("The agent reviewed it.", f.say());
        assertEquals(0, f.more().stream().filter(n -> n.id().equals("self-fix")).count());
    }

    @Test
    void rightAfterAPushNoChecksYetMeansCiIsStarting() {
        Item noChecks = pr2(ME, null, "BLOCKED", "MERGEABLE", false, false, "REVIEW_REQUIRED");
        ThreadView.Summary pushed = new ThreadView.Summary("k", "idle", "ci-fix", null, NOW, false, "push", NOW.minusSeconds(240));
        is(FlowClassifier.flow("mine", noChecks, ctx(pushed, null, null, null, List.of())), Flow.CI, 1, null);
        // An hour later still nothing: not CI starting, the repo just has no checks.
        ThreadView.Summary old = new ThreadView.Summary("k", "idle", "ci-fix", null, NOW, false, "push", NOW.minusSeconds(3600));
        is(FlowClassifier.flow("mine", noChecks, ctx(old, null, null, null, List.of())), Flow.OTHERS, 2, null);
    }

    @Test
    void aStoppedCiJobGivesWayToCiRunningAgain() {
        ThreadView.Summary stopped = new ThreadView.Summary("k", "idle", "ci-why", null, NOW, true, null, null);
        var c = ctx(stopped, null, null, null, List.of());
        // You re-ran the failed jobs: CI is what you wait on now, not the agent that stopped.
        is(FlowClassifier.flow("mine", pr2(ME, "PENDING", "BLOCKED", "MERGEABLE", false, false, null), c), Flow.CI, 1, null);
        // Still failing: the stop is your move, and asking again is right there.
        Flow failing = FlowClassifier.flow("mine", pr2(ME, "FAILURE", "BLOCKED", "MERGEABLE", false, false, null), c);
        is(failing, Flow.YOU, 1, "open");
        assertTrue(failing.more().stream().anyMatch(n -> n.id().startsWith("ci-")), failing.more().toString());
    }

    @Test
    void greenButBlockedIsCiStartingOnlyJustAfterAChange() {
        // GitHub blocks a green PR while required checks haven't started, and also when a ruleset wants an approval
        // without saying so in reviewDecision (search.quarkus.io#636): only the first right after a push.
        Item p = pr2(ME, "SUCCESS", "BLOCKED", "MERGEABLE", false, false, null);
        Item fresh = new Item(p.key(), p.repo(), p.number(), p.kind(), p.title(), p.url(), p.author(), p.assignees(),
                p.createdAt(), NOW.minusSeconds(300), 0, false, p.headRef(), p.headSha(), "SUCCESS", null, "MERGEABLE", "BLOCKED", false);
        is(FlowClassifier.flow("mine", fresh, ctx()), Flow.CI, 1, null);
        Flow old = FlowClassifier.flow("mine", p, ctx());
        is(old, Flow.OTHERS, 2, null);
        assertEquals("Waiting on reviewers for 8 days.", old.say());
    }

    @Test
    void aReviewerSpeakingAfterYouMakesItYourMove() {
        Item waiting = pr2(ME, "SUCCESS", "BLOCKED", "MERGEABLE", false, false, "REVIEW_REQUIRED");
        Instant at = NOW.minusSeconds(3600);
        Flow f = FlowClassifier.flow("mine", waiting, new FlowClassifier.Ctx(ME, null, null, null, null, List.of(), NOW,
                new GitHub.Feedback("brunobat", at, "COMMENTED", true, 2)));
        is(f, Flow.YOU, 2, "read-review");
        assertEquals("address", f.more().get(0).id());
        assertEquals("brunobat reviewed, 2 threads open.", f.say());
        assertEquals(at, f.since());
        // You answered since: back to waiting on them, with the threads still mentioned.
        f = FlowClassifier.flow("mine", waiting, new FlowClassifier.Ctx(ME, null, null, null, null, List.of(), NOW,
                new GitHub.Feedback("brunobat", at, "COMMENTED", false, 1)));
        is(f, Flow.OTHERS, 2, null);
        org.junit.jupiter.api.Assertions.assertTrue(f.say().endsWith(", 1 thread open."), f.say());
    }

    @Test
    void youPushedSinceTheReviewSoAskThemAgain() {
        Item pushed = pr2(ME, "SUCCESS", "BLOCKED", "MERGEABLE", false, false, "CHANGES_REQUESTED");
        Instant at = NOW.minusSeconds(7200);
        Flow f = FlowClassifier.flow("mine", pushed, new FlowClassifier.Ctx(ME, null, null, null, null, List.of(), NOW,
                new GitHub.Feedback("brunobat", at, "CHANGES_REQUESTED", false, 0, List.of("brunobat"), List.of())));
        is(f, Flow.YOU, 2, "re-request");
        assertEquals("Ask brunobat to review again", f.next().label());
        assertEquals("brunobat", f.next().target());
        assertEquals("You pushed since brunobat reviewed.", f.say());
        // Asked: waiting on them.
        f = FlowClassifier.flow("mine", pushed, new FlowClassifier.Ctx(ME, null, null, null, null, List.of(), NOW,
                new GitHub.Feedback("brunobat", at, "CHANGES_REQUESTED", false, 0, List.of(), List.of("brunobat"))));
        is(f, Flow.OTHERS, 2, null);
        org.junit.jupiter.api.Assertions.assertTrue(f.say().startsWith("Waiting for brunobat to review again"), f.say());
    }

    @Test
    void afterTheAgentExplainedFailingCiTheNextStepIsTheFix() {
        Item red = pr2(ME, "FAILURE", "UNSTABLE", "MERGEABLE", false, false, null);
        is(FlowClassifier.flow("mine", red, ctx(thread("working", "ci-why", null), null, null, null, List.of())), Flow.AGENT, 1, "open");
        Flow answered = FlowClassifier.flow("mine", red, ctx(thread("answered", "ci-why", null), null, null, null, List.of()));
        is(answered, Flow.YOU, 1, "open");
        assertEquals("ci-fix", answered.more().get(0).id());
        is(FlowClassifier.flow("mine", red, ctx(thread("idle", "ci-why", null), null, null, null, List.of())), Flow.YOU, 1, "ci-fix");
        is(FlowClassifier.flow("mine", red, ctx(thread("draft", "ci-fix", "push"), null, null, null, List.of())), Flow.YOU, 1, "open");
    }

    @Test
    void aMergeTheDeskRunsDecidesFirst() {
        Item green = pr2(ME, "PENDING", "BLOCKED", "MERGEABLE", true, false, "APPROVED");
        is(FlowClassifier.flow("mine", green, ctx(null, new MergeService.View("k", "squash", "waiting", null, NOW, null), null, null,
                List.of())), Flow.CI, 3, "merge");
        is(FlowClassifier.flow("mine", green, ctx(null, new MergeService.View("k", "squash", "merged", "Merged.", NOW, NOW), null, null,
                List.of())), Flow.DONE, 3, null);
    }

    @Test
    void aDependabotPrWithConflictsAsksDependabotToRebaseThenWaitsForIt() {
        Item bump = pr2("dependabot[bot]", "SUCCESS", "DIRTY", "CONFLICTING", true, false, "APPROVED");
        is(FlowClassifier.flow("review", bump, ctx()), Flow.YOU, 2, "dependabot-rebase");

        // Asked: wait for Dependabot's push, no button to ask again.
        Instant asked = NOW.minusSeconds(120);
        Flow waiting = FlowClassifier.flow("review", bump, new FlowClassifier.Ctx(ME, null, null, null, null, List.of(), NOW,
                null, FlowClassifier.rebaseAsked(java.util.Map.of(bump.key(), new Board.Rebase(bump.headSha(), asked)), bump, NOW)));
        is(waiting, Flow.OTHERS, 2, null);
        // Once the head moved (or an hour went by), the ask is done with.
        assertNull(FlowClassifier.rebaseAsked(java.util.Map.of(bump.key(), new Board.Rebase("older-sha", asked)), bump, NOW));
        assertNull(FlowClassifier.rebaseAsked(java.util.Map.of(bump.key(), new Board.Rebase(bump.headSha(), NOW.minusSeconds(7200))),
                bump, NOW));
    }

    // ---- PRs to review ----

    @Test
    void reviewThenWaitForTheAuthorThenMerge() {
        Item open = pr2("someone", "SUCCESS", "BLOCKED", "MERGEABLE", false, false, "REVIEW_REQUIRED");
        is(FlowClassifier.flow("review", open, ctx()), Flow.YOU, 0, "review");
        is(FlowClassifier.flow("review", open, ctx(thread("draft", "review", "review"), null, null, null, List.of())), Flow.YOU, 0, "open");
        Instant reviewed = NOW.minusSeconds(3 * 86_400);
        Flow waiting = FlowClassifier.flow("review", open, ctx(null, null, null, reviewed, List.of()));
        is(waiting, Flow.OTHERS, 1, null);
        assertEquals("Reviewed. Waiting on the author for 3 days.", waiting.say());
        is(FlowClassifier.flow("review", open, ctx(null, null, "New commits since your review", reviewed, List.of())), Flow.YOU, 0,
                "review");
        is(FlowClassifier.flow("review", pr2("someone", "SUCCESS", "CLEAN", "MERGEABLE", true, false, "APPROVED"),
                ctx(null, null, null, reviewed, List.of())), Flow.YOU, 3, "merge");
        is(FlowClassifier.flow("review", pr2("someone", "FAILURE", "UNSTABLE", "MERGEABLE", true, false, "APPROVED"),
                ctx(null, null, null, reviewed, List.of())), Flow.YOU, 2, "ci-why");
    }

    // ---- issues ----

    @Test
    void issueGoesInvestigateFixPr() {
        Item i = issue("quarkusio/quarkus", 32850, "someone", ME);
        is(FlowClassifier.flow("assigned", i, ctx()), Flow.YOU, 0, "investigate");
        is(FlowClassifier.flow("assigned", i, ctx(thread("working", "investigate", null), null, null, null, List.of())), Flow.AGENT, 0,
                "open");
        is(FlowClassifier.flow("assigned", i, ctx(thread("idle", "investigate", null), null, null, null, List.of())), Flow.YOU, 1, "fix");
        is(FlowClassifier.flow("assigned", i, ctx(thread("draft", "fix", "pr"), null, null, null, List.of())), Flow.YOU, 1, "open");
        Flow mine = FlowClassifier.flow("assigned", i, ctx(null, null, null, null, List.of(new GitHub.LinkedPr(
                "quarkusio/quarkus#57300", "Fix", "u", ME, false, true))));
        is(mine, Flow.OTHERS, 2, "go");
        assertEquals("quarkusio/quarkus#57300", mine.next().target());
        assertEquals("Open #57300", mine.next().label());
    }

    @Test
    void involvedItemsAreJustWatched() {
        is(FlowClassifier.flow("involved", issue("quarkusio/quarkus", 5, "someone"), ctx()), Flow.WATCHING, 0, null);
        is(FlowClassifier.flow("involved", issue("quarkusio/quarkus", 5, "someone"),
                ctx(thread("answered", "ask", null), null, null, null, List.of())), Flow.YOU, 0, "open");
    }
}
