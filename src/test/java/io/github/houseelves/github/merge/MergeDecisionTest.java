package io.github.houseelves.github.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.merge.MergeDecision.Step;

class MergeDecisionTest {

    static final String SHA = "a".repeat(40);
    static final Instant T0 = Instant.parse("2026-10-07T03:00:00Z");

    static GitHub.PullState pr(String state, String sha, String ci, String mergeable, String mergeState) {
        return new GitHub.PullState("t", "u", state, false, sha, ci, mergeable, mergeState, "APPROVED", List.of("merge"),
                "merge", true);
    }

    static MergeDecision decide(GitHub.PullState pr) {
        return MergeDecision.decide(SHA, T0, T0.plusSeconds(600), pr);
    }

    @Test
    void greenAndMergeableMerges() {
        assertEquals(Step.MERGE, decide(pr("OPEN", SHA, "SUCCESS", "MERGEABLE", "CLEAN")).step());
        assertEquals(Step.MERGE, decide(pr("OPEN", SHA, "SUCCESS", "MERGEABLE", "HAS_HOOKS")).step());
    }

    @Test
    void greenIsntEnoughUntilEveryRequiredCheckPassed() {
        // quarkusio/quarkus#56953 after the push: two early checks green, the required CI not reported yet.
        assertEquals(Step.WAIT, decide(pr("OPEN", SHA, "SUCCESS", "MERGEABLE", "BLOCKED")).step());
    }

    @Test
    void runningOrUnknownWaits() {
        assertEquals(Step.WAIT, decide(pr("OPEN", SHA, "PENDING", "MERGEABLE", "UNSTABLE")).step());
        assertEquals(Step.WAIT, decide(pr("OPEN", SHA, "SUCCESS", "UNKNOWN", "UNKNOWN")).step());
        assertEquals(Step.WAIT, decide(pr("OPEN", SHA, null, "MERGEABLE", "BLOCKED")).step());
    }

    @Test
    void noChecksAtAllIsGreenOnlyWhenGitHubSaysClean() {
        assertEquals(Step.MERGE, decide(pr("OPEN", SHA, null, "MERGEABLE", "CLEAN")).step());
    }

    @Test
    void newCommitsFailuresAndConflictsStop() {
        MergeDecision pushed = decide(pr("OPEN", "b".repeat(40), "SUCCESS", "MERGEABLE", "CLEAN"));
        assertEquals(Step.STOP, pushed.step());
        assertTrue(pushed.reason().startsWith("New commits were pushed"));
        assertEquals(Step.STOP, decide(pr("OPEN", SHA, "FAILURE", "MERGEABLE", "UNSTABLE")).step());
        assertEquals(Step.STOP, decide(pr("OPEN", SHA, "SUCCESS", "CONFLICTING", "DIRTY")).step());
    }

    @Test
    void aWithdrawnApprovalStops() {
        var unapproved = new GitHub.PullState("t", "u", "OPEN", false, SHA, "SUCCESS", "MERGEABLE", "BLOCKED",
                "CHANGES_REQUESTED", List.of("merge"), "merge", false);
        MergeDecision d = decide(unapproved);
        assertEquals(Step.STOP, d.step());
        assertTrue(d.reason().startsWith("It's no longer approved"));
    }

    @Test
    void mergeAnywayDoesntNeedTheApproval() {
        var unapproved = new GitHub.PullState("t", "u", "OPEN", false, SHA, "SUCCESS", "MERGEABLE", "CLEAN",
                null, List.of("merge"), "merge", false);
        assertEquals(Step.MERGE, MergeDecision.decide(SHA, T0, T0.plusSeconds(60), unapproved, false).step());
    }

    @Test
    void mergedOrClosedElsewhereIsDone() {
        assertEquals(Step.DONE, decide(pr("MERGED", SHA, "SUCCESS", "UNKNOWN", "UNKNOWN")).step());
        assertEquals(Step.DONE, decide(pr("CLOSED", SHA, "SUCCESS", "UNKNOWN", "UNKNOWN")).step());
    }

    @Test
    void givesUpAfterADay() {
        MergeDecision d = MergeDecision.decide(SHA, T0, T0.plus(Duration.ofHours(25)),
                pr("OPEN", SHA, "PENDING", "MERGEABLE", "UNSTABLE"));
        assertEquals(Step.STOP, d.step());
    }
}
