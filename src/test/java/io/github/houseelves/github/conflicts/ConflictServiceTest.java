package io.github.houseelves.github.conflicts;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.github.TestElf;
import io.github.houseelves.github.agent.Prompts;
import io.github.houseelves.github.elf.Elf;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.GitHubWriter;
import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.github.ItemContext;
import io.github.houseelves.github.thread.GitHubAction;
import io.github.houseelves.github.thread.Job;
import io.github.houseelves.github.thread.Poster;
import io.github.houseelves.github.thread.ThreadService;

class ConflictServiceTest {

    static final String BUMP = "quarkusio/quarkus#57300";
    static final String PR = "quarkusio/quarkus#57301";

    @TempDir
    Path dir;

    GitHub github = mock(GitHub.class);
    GitHubWriter writer = mock(GitHubWriter.class);
    Elf elf;
    ConflictService conflicts;
    ThreadService threads;
    Poster poster;

    final ObjectMapper mapper = new ObjectMapper();

    static ItemContext ctx(int number, String author) {
        return new ItemContext("quarkusio/quarkus", number, Item.Kind.PR, "Bump org.apache.maven.plugins:maven-compiler-plugin",
                "https://github.com/quarkusio/quarkus/pull/" + number, author, "NONE", "Bumps it.", "main",
                "dependabot/maven/x", List.of());
    }

    @BeforeEach
    void setUp() {
        elf = TestElf.create(dir, github, writer);
        conflicts = elf.conflicts;
        threads = elf.threads;
        poster = elf.poster;
        when(github.context(anyString(), eq("quarkusio/quarkus"), eq(57300))).thenReturn(ctx(57300, "dependabot"));
        when(github.context(anyString(), eq("quarkusio/quarkus"), eq(57301))).thenReturn(ctx(57301, "someone"));
        when(github.ci(anyString(), eq("quarkusio/quarkus"), anyInt())).thenReturn(new GitHub.CiReport("a".repeat(40), "SUCCESS",
                List.of(), List.of(), "someone/quarkus", "feature", true));
    }

    @Test
    void dependabotPrsGetARebaseRequest() throws Exception {
        ConflictService.Options o = conflicts.options(BUMP, "t");
        assertTrue(o.dependabot());
        assertEquals("main", o.base());

        when(writer.createComment(eq("quarkusio/quarkus"), eq(57300), eq(Map.of("body", "@dependabot rebase"))))
                .thenReturn(mapper.readTree("{\"html_url\": \"https://github.com/quarkusio/quarkus/pull/57300#issuecomment-1\"}"));
        assertEquals("https://github.com/quarkusio/quarkus/pull/57300#issuecomment-1", conflicts.askDependabotToRebase(BUMP, "phillip-kruger", "t"));
        GitHubAction a = elf.store.actions().get(0);
        assertTrue(a.request.contains("@dependabot rebase"));

        assertThrows(IllegalStateException.class, () -> conflicts.askDependabotToRebase(PR, "me", "t"));
    }

    @Test
    void otherPrsGetTheAgentWithThePushTarget() {
        conflicts.resolve(PR, "phillip-kruger", "t");
        Job j = TestElf.jobs(elf).get(0);
        assertEquals(Prompts.CONFLICTS, j.action);
        assertTrue(j.prompt.contains("`git merge origin/main`"));
        assertTrue(j.prompt.contains("Don't rebase"));
        assertTrue(j.checkout.contains("\"headRepo\":\"someone/quarkus\""));
    }

    @Test
    void behindPrsAreUpdatedByGitHubPinnedToTheHead() throws Exception {
        when(writer.updateBranch("quarkusio/quarkus", 57301, "a".repeat(40), "REBASE")).thenReturn(mapper.createObjectNode());
        assertEquals("rebased", conflicts.updateBranch(PR, "REBASE", "phillip-kruger", "t"));
        verify(writer).updateBranch("quarkusio/quarkus", 57301, "a".repeat(40), "REBASE");
        GitHubAction a = elf.store.actions().get(0);
        assertEquals("update-branch", a.kind);

        assertThrows(IllegalArgumentException.class, () -> conflicts.updateBranch(PR, "SQUASH", "me", "t"));
        assertThrows(IllegalArgumentException.class, () -> conflicts.updateBranch(PR, "MERGE", "me", "t"));

        // Dependabot: ask it to rebase instead of updating its branch ourselves.
        when(writer.createComment(eq("quarkusio/quarkus"), eq(57300), any())).thenReturn(mapper.readTree("{\"html_url\": \"u\"}"));
        assertEquals("rebase-requested", conflicts.updateBranch(BUMP, "REBASE", "me", "t"));
        verify(writer, never()).updateBranch(eq("quarkusio/quarkus"), eq(57300), any(), any());
    }

    @Test
    void noPushNoResolve() {
        when(github.ci(anyString(), eq("quarkusio/quarkus"), anyInt())).thenReturn(new GitHub.CiReport("a".repeat(40), "SUCCESS",
                List.of(), List.of(), "someone/quarkus", "feature", false));
        assertThrows(IllegalStateException.class, () -> conflicts.resolve(PR, "me", "t"));
        assertThrows(IllegalStateException.class, () -> conflicts.updateBranch(PR, "REBASE", "me", "t"));
        assertThrows(IllegalStateException.class, () -> conflicts.squash(PR, "t"));
        verify(writer, never()).updateBranch(any(), anyInt(), any(), any());
    }

    static final String OLD = "a".repeat(40), SQUASHED = "c".repeat(40);

    static String squashedGit() {
        return """
                {"from": "%s", "to": "%s", "base": "main", "squashed": true, "replaces": 3,
                 "commits": [{"sha": "%s", "subject": "Bump org.apache.maven.plugins:maven-compiler-plugin"}],
                 "diffstat": " pom.xml | 2 +-"}""".formatted(OLD, SQUASHED, SQUASHED);
    }

    @Test
    void resolvedConflictsArePushedAsOneSquashedCommit() throws Exception {
        conflicts.resolve(PR, "phillip-kruger", "t");
        long job = threads.view(PR).job().id();
        threads.jobDone(job, "s", mapper.readTree("{\"reply\": \"Merged main in.\", \"draft\": null}"), null,
                mapper.readTree(squashedGit()));

        var draft = threads.view(PR).draft();
        assertTrue(draft.push().squashed());
        Poster.Preview p = poster.preview(draft.id());
        assertTrue(p.target().startsWith("Replace someone/quarkus:feature (3 commits) with 1 squashed commit on main"), p.target());
        // A force push with a lease on the old head, which the runner makes and checks.
        assertEquals(new Poster.Push("quarkusio/quarkus", 57301, "someone/quarkus", "feature", OLD, SQUASHED, true, "main"),
                poster.post(draft.id(), p.confirm(), "phillip-kruger").push());
        poster.postDone(draft.id(), 200, null, "phillip-kruger");
        assertTrue(threads.view(PR).turns().stream().anyMatch(t -> t.text().startsWith("You replaced someone/quarkus:feature")));
    }

    @Test
    void squashWithoutAnAgentIsOfferedAsAPush() throws Exception {
        // The runner squashes in its worktree: the action answers with what to squash.
        var checkout = conflicts.squash(PR, "t");
        assertEquals("quarkusio/quarkus", checkout.path("repo").asText());
        assertEquals(57301, checkout.path("number").asInt());
        assertEquals("PR", checkout.path("kind").asText());
        assertEquals("main", checkout.path("base").asText());

        // Its answer comes back through squash-done, in a later run of the elf.
        var view = TestElf.create(dir, github, writer).threads.squashDone(PR, 200, squashedGit(), null);
        assertTrue(view.draft().push().squashed());
        assertEquals("feature", view.draft().push().headRef());
        assertEquals(3, view.draft().push().replaces());
        // Only one push waiting at a time, and only an answer to a squash that was asked for.
        assertThrows(IllegalStateException.class, () -> TestElf.create(dir, github, writer).conflicts.squash(PR, "t"));
        assertThrows(IllegalStateException.class, () -> TestElf.create(dir, github, writer).threads.squashDone(PR, 200, squashedGit(), null));
    }

    @Test
    void aSquashTheRunnerCouldntDoSaysWhy() {
        conflicts.squash(PR, "t");
        var e = assertThrows(IllegalStateException.class, () -> threads.squashDone(PR, 409, null,
                "Couldn't squash the PR into one commit: boom"));
        assertTrue(e.getMessage().contains("boom"));
        assertEquals(null, threads.view(PR).draft());
    }

    @Test
    void aSquashThatFailsOffersNothing() throws Exception {
        conflicts.resolve(PR, "phillip-kruger", "t");
        long job = threads.view(PR).job().id();
        threads.jobDone(job, "s", mapper.readTree("{\"reply\": \"Done.\", \"draft\": null}"), null,
                mapper.readTree("{\"error\": \"Couldn't squash the PR into one commit: boom\"}"));
        assertEquals(null, threads.view(PR).draft());
        assertTrue(threads.view(PR).turns().stream().anyMatch(t -> t.text().contains("boom")));
    }
}
