package io.github.houseelves.github.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.github.TestElf;
import io.github.houseelves.github.elf.Elf;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.GitHubWriter;
import io.github.houseelves.github.thread.GitHubAction;

class MergeServiceTest {

    static final String KEY = "quarkusio/quarkus#50031";
    static final String SHA = "c".repeat(40);

    @TempDir
    Path dir;

    GitHub github = mock(GitHub.class);
    GitHubWriter writer = mock(GitHubWriter.class);
    Elf elf;
    MergeService merges;

    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        elf = TestElf.create(dir, github, writer);
        merges = elf.merges;
    }

    /** The next run of the elf: watches are in state/merges.json. */
    MergeService again() {
        return TestElf.create(dir, github, writer).merges;
    }

    void prIs(String state, String sha, String ci) {
        prIs(state, sha, ci, true);
    }

    void prIs(String state, String sha, String ci, boolean approved) {
        prIs(state, sha, ci, approved, "MERGEABLE");
    }

    /** mergeable UNKNOWN: GitHub is still working it out, so a merge request waits for the next round. */
    void prIs(String state, String sha, String ci, boolean approved, String mergeable) {
        when(github.pullState(anyString(), eq("quarkusio/quarkus"), eq(50031))).thenReturn(new GitHub.PullState("t", "u",
                state, false, sha, ci, mergeable, "CLEAN", approved ? "APPROVED" : "REVIEW_REQUIRED", List.of("merge"),
                "merge", approved));
    }

    @Test
    void mergesAnApprovedGreenPrAtOncePinnedToTheHead() throws Exception {
        prIs("OPEN", SHA, "SUCCESS");
        when(writer.merge("quarkusio/quarkus", 50031, "merge", SHA)).thenReturn(mapper.readTree("{\"sha\": \"1234567890\"}"));
        MergeService.View v = merges.request(KEY, "merge", SHA, "phillip-kruger", "t");
        assertEquals("merged", v.state());
        assertEquals("Merged (1234567).", v.outcome());
        GitHubAction audit = elf.store.actions().get(0);
        assertEquals("merge", audit.kind);
        assertTrue(audit.request.contains(SHA));
    }

    @Test
    void mergeAnywayMergesAGreenPrWithoutAnApprovalAndSaysSo() throws Exception {
        prIs("OPEN", SHA, "SUCCESS", false);
        assertThrows(IllegalStateException.class, () -> merges.request(KEY, "merge", SHA, "me", "t"));
        when(writer.merge("quarkusio/quarkus", 50031, "merge", SHA)).thenReturn(mapper.readTree("{\"sha\": \"abcdef12\"}"));
        MergeService.View v = merges.request(KEY, "merge", SHA, "phillip-kruger", "t", true);
        assertEquals("merged", v.state());
        MergeWatch w = again().latestFor(KEY);
        assertTrue(!w.requireApproval);

        // Still not without green CI.
        prIs("OPEN", SHA, "PENDING", false);
        assertThrows(IllegalStateException.class, () -> merges.request(KEY, "merge", SHA, "me", "t", true));
    }

    @Test
    void ciWaitingForApprovalIsRefusedEvenForMergeAnyway() {
        prIs("OPEN", SHA, GitHub.AWAITING_APPROVAL, true);
        assertThrows(IllegalStateException.class, () -> merges.request(KEY, "merge", SHA, "me", "t"));
        assertThrows(IllegalStateException.class, () -> merges.request(KEY, "merge", SHA, "me", "t", true));
    }

    @Test
    void requiredChecksMustHavePassed() {
        when(github.pullState(anyString(), eq("quarkusio/quarkus"), eq(50031))).thenReturn(new GitHub.PullState("t", "u",
                "OPEN", false, SHA, "SUCCESS", "MERGEABLE", "BLOCKED", "APPROVED", List.of("merge"), "merge", true));
        var e = assertThrows(IllegalStateException.class, () -> merges.request(KEY, "merge", SHA, "me", "t"));
        assertTrue(e.getMessage().contains("required checks haven't all passed"));
    }

    static final String DIFF = "diff --git a/pom.xml b/pom.xml\nindex 1..2\n--- a/pom.xml\n+++ b/pom.xml\n@@ -10,3 +10,3 @@\n"
            + " <a>1</a>\n-<lit>3.1</lit>\n+<lit>3.2</lit>\n";
    static final String REBASED = "diff --git a/pom.xml b/pom.xml\nindex 3..4\n--- a/pom.xml\n+++ b/pom.xml\n@@ -12,3 +12,3 @@\n"
            + " <a>2</a>\n-<lit>3.1</lit>\n+<lit>3.2</lit>\n";
    /** The same hunk with the same context, moved down by the base's change elsewhere. */
    static final String MOVED = "diff --git a/pom.xml b/pom.xml\nindex 5..6\n--- a/pom.xml\n+++ b/pom.xml\n@@ -14,3 +14,3 @@\n"
            + " <a>1</a>\n-<lit>3.1</lit>\n+<lit>3.2</lit>\n";
    static final String NEW = "d".repeat(40);

    void stateIs(String sha, String ci, String mergeState) {
        when(github.pullState(anyString(), eq("quarkusio/quarkus"), eq(50031))).thenReturn(new GitHub.PullState("t", "u",
                "OPEN", false, sha, ci, "MERGEABLE", mergeState, "APPROVED", List.of("merge"), "merge", true));
    }

    @Test
    void mergeOnAPrBehindItsBaseBringsItUpToDateAndMergesTheRebaseOnceGreen() throws Exception {
        List<String> updates = new java.util.ArrayList<>();
        merges.updater((k, login, token) -> { updates.add(k); return "rebased"; });
        stateIs(SHA, "SUCCESS", "BEHIND");
        when(github.prDiff(anyString(), eq("quarkusio/quarkus"), eq(50031), anyInt())).thenReturn(new GitHub.Diff(DIFF, false));
        MergeService.View v = merges.request(KEY, "merge", SHA, "me", "t");
        assertEquals("waiting", v.state());
        assertEquals(List.of(KEY), updates);
        verify(writer, never()).merge(anyString(), anyInt(), anyString(), anyString());

        // Not rebased yet: it waits, and isn't updated again.
        merges.poll("t");
        assertEquals(1, updates.size());
        assertEquals("waiting", merges.view(KEY).state());

        // Rebased, the same change; CI running: waits. Then green: merges the rebased head.
        stateIs(NEW, "PENDING", "BLOCKED");
        when(github.prDiff(anyString(), eq("quarkusio/quarkus"), eq(50031), anyInt())).thenReturn(new GitHub.Diff(MOVED, false));
        merges = again();
        merges.updater((k, login, token) -> { updates.add(k); return "rebased"; });
        merges.poll("t");
        assertEquals("waiting", merges.view(KEY).state());
        stateIs(NEW, "SUCCESS", "CLEAN");
        when(writer.merge("quarkusio/quarkus", 50031, "merge", NEW)).thenReturn(mapper.readTree("{\"sha\": \"abcdef12\"}"));
        merges.poll("t");
        assertEquals("merged", merges.view(KEY).state());
        verify(writer).merge("quarkusio/quarkus", 50031, "merge", NEW);
        assertEquals(1, updates.size());
    }

    @Test
    void aRebaseThatChangesThePrStopsTheMerge() {
        merges.updater((k, login, token) -> "rebased");
        stateIs(SHA, "SUCCESS", "BEHIND");
        when(github.prDiff(anyString(), eq("quarkusio/quarkus"), eq(50031), anyInt())).thenReturn(new GitHub.Diff(DIFF, false));
        merges.request(KEY, "merge", SHA, "me", "t");
        stateIs(NEW, "SUCCESS", "CLEAN");
        when(github.prDiff(anyString(), eq("quarkusio/quarkus"), eq(50031), anyInt()))
                .thenReturn(new GitHub.Diff(DIFF + "+<more/>\n", false));
        merges.poll("t");
        assertEquals("failed", merges.view(KEY).state());
        verify(writer, never()).merge(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void fallingBehindAgainAfterTheRebaseUpdatesItAgain() {
        List<String> updates = new java.util.ArrayList<>();
        merges.updater((k, login, token) -> { updates.add(k); return "rebase-requested"; });
        stateIs(SHA, "SUCCESS", "BEHIND");
        when(github.prDiff(anyString(), eq("quarkusio/quarkus"), eq(50031), anyInt())).thenReturn(new GitHub.Diff(DIFF, false));
        merges.request(KEY, "merge", SHA, "me", "t");
        stateIs(NEW, "SUCCESS", "BEHIND");
        when(github.prDiff(anyString(), eq("quarkusio/quarkus"), eq(50031), anyInt())).thenReturn(new GitHub.Diff(REBASED, false));
        merges.poll("t"); // follows the rebase; updated a moment ago, so not again yet
        assertEquals(1, updates.size());
        assertEquals("waiting", merges.view(KEY).state());
    }

    @Test
    void behindWithoutAReadableChangeIsStillRefused() {
        merges.updater((k, login, token) -> "rebased");
        stateIs(SHA, "SUCCESS", "BEHIND");
        assertTrue(assertThrows(IllegalStateException.class, () -> merges.request(KEY, "merge", SHA, "me", "t"))
                .getMessage().contains("behind its base branch"));
    }

    @Test
    void theChangeLeavesOutLineNumbersOnlyAndContextOnlyForDependabot() {
        assertEquals(MergeService.changeOf(DIFF, false), MergeService.changeOf(MOVED, false));
        assertNotEquals(MergeService.changeOf(DIFF, false), MergeService.changeOf(REBASED, false));
        assertEquals(MergeService.changeOf(DIFF, true), MergeService.changeOf(REBASED, true));
        assertNotEquals(MergeService.changeOf(DIFF, true), MergeService.changeOf(DIFF + "+x\n", true));
        // The same added line somewhere else in the file isn't the same change.
        String elsewhere = "diff --git a/pom.xml b/pom.xml\n--- a/pom.xml\n+++ b/pom.xml\n@@ -90,3 +90,3 @@\n"
                + " <b>9</b>\n-<lit>3.1</lit>\n+<lit>3.2</lit>\n";
        assertNotEquals(MergeService.changeOf(DIFF, false), MergeService.changeOf(elsewhere, false));
        assertNotEquals(MergeService.changeOf(DIFF, false), MergeService.changeOf(DIFF + "old mode 100644\nnew mode 100755\n", false));
        assertNull(MergeService.changeOf("diff --git a/x.png b/x.png\nBinary files a/x.png and b/x.png differ\n", false));
    }

    @Test
    void aNonDependabotRebaseWithOtherContextStopsTheMerge() {
        merges.updater((k, login, token) -> "rebased");
        stateIs(SHA, "SUCCESS", "BEHIND");
        when(github.prDiff(anyString(), eq("quarkusio/quarkus"), eq(50031), anyInt())).thenReturn(new GitHub.Diff(DIFF, false));
        merges.request(KEY, "merge", SHA, "me", "t");
        stateIs(NEW, "SUCCESS", "CLEAN");
        when(github.prDiff(anyString(), eq("quarkusio/quarkus"), eq(50031), anyInt())).thenReturn(new GitHub.Diff(REBASED, false));
        merges.poll("t");
        assertEquals("failed", merges.view(KEY).state());
        verify(writer, never()).merge(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void ciThatIsntGreenYetIsRefused() {
        prIs("OPEN", SHA, "PENDING");
        var e = assertThrows(IllegalStateException.class, () -> merges.request(KEY, "merge", SHA, "me", "t"));
        assertEquals("CI isn't green on " + KEY + " yet.", e.getMessage());
        verify(writer, never()).merge(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void refusesStaleHeadsAndMethodsTheRepoDoesntAllow() {
        prIs("OPEN", SHA, "SUCCESS");
        assertThrows(IllegalStateException.class, () -> merges.request(KEY, "merge", "d".repeat(40), "me", "t"));
        assertThrows(IllegalArgumentException.class, () -> merges.request(KEY, "squash", SHA, "me", "t"));
    }

    @Test
    void unapprovedPrsAreRefusedAndALostApprovalStopsTheWait() {
        prIs("OPEN", SHA, "SUCCESS", false);
        var e = assertThrows(IllegalStateException.class, () -> merges.request(KEY, "merge", SHA, "me", "t"));
        assertTrue(e.getMessage().startsWith("Merging is only for approved PRs"));

        prIs("OPEN", SHA, "SUCCESS", true, "UNKNOWN");
        merges.request(KEY, "merge", SHA, "me", "t");
        assertEquals("waiting", merges.view(KEY).state());
        prIs("OPEN", SHA, "SUCCESS", false);
        again().poll("t"); // the next refresh
        assertEquals("failed", again().view(KEY).state());
        verify(writer, never()).merge(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void redCiIsRefused() {
        prIs("OPEN", SHA, "FAILURE", true);
        var e = assertThrows(IllegalStateException.class, () -> merges.request(KEY, "merge", SHA, "me", "t"));
        assertTrue(e.getMessage().contains("it's failing; re-run or fix it first"));
    }

    @Test
    void newCommitsStopItAndGitHubRefusalsAreReported() {
        prIs("OPEN", SHA, "SUCCESS", true, "UNKNOWN");
        merges.request(KEY, "merge", SHA, "me", "t");
        prIs("OPEN", "e".repeat(40), "SUCCESS");
        merges = again();
        merges.poll("t");
        assertEquals("failed", merges.view(KEY).state());
        verify(writer, never()).merge(anyString(), anyInt(), anyString(), anyString());

        prIs("OPEN", SHA, "SUCCESS");
        when(writer.merge(any(), anyInt(), any(), any())).thenThrow(new GitHubWriter.WriteFailed(405,
                "{\"message\": \"Required status check is expected.\"}", "HTTP 405"));
        merges.request(KEY, "merge", SHA, "me", "t");
        MergeService.View v = merges.view(KEY);
        assertEquals("failed", v.state());
        assertEquals("GitHub refused the merge: Required status check is expected.", v.outcome());
    }

    @Test
    void unreachableGitHubKeepsItWaitingAndCancelStopsIt() {
        prIs("OPEN", SHA, "SUCCESS");
        when(writer.merge(any(), anyInt(), any(), any())).thenThrow(new GitHubWriter.WriteFailed(0, null, "offline"));
        merges.request(KEY, "merge", SHA, "me", "t");
        assertEquals("waiting", merges.view(KEY).state());
        assertEquals("cancelled", merges.cancel(KEY).state());
    }
}
