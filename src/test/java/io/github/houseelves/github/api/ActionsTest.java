package io.github.houseelves.github.api;

import static io.github.houseelves.github.Items.pr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.houseelves.github.Contexts;
import io.github.houseelves.github.TestElf;
import io.github.houseelves.github.elf.Elf;
import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.elf.Views;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.GitHubWriter;

/**
 * The actions as the runner runs them: values in, the answer out (one JSON line), and the files the tab reads
 * written after. Each call is a fresh run of the elf on the same files.
 */
class ActionsTest {

    static final String KEY = "quarkusio/quarkus#50087";

    @TempDir
    Path dir;

    GitHub github = mock(GitHub.class);
    GitHubWriter writer = mock(GitHubWriter.class);

    @BeforeEach
    void setUp() {
        when(github.context(any(), eq("quarkusio/quarkus"), eq(50087))).thenReturn(Contexts.pr("quarkusio/quarkus", 50087, "MEMBER"));
        when(github.search(anyString(), contains("involves:@me"), anyInt())).thenReturn(new GitHub.SearchResult(
                List.of(pr("quarkusio/quarkus", 50087, "someone")), 1));
        when(github.search(anyString(), contains("review-requested:@me"), anyInt())).thenReturn(new GitHub.SearchResult(List.of(), 0));
        when(github.search(anyString(), contains("reviewed-by:@me"), anyInt())).thenReturn(new GitHub.SearchResult(List.of(), 0));
        when(github.search(anyString(), contains("author:app/dependabot"), anyInt())).thenReturn(new GitHub.SearchResult(List.of(), 0));
    }

    /** One run of the elf: the action, then the views, as GitHubElf does. */
    JsonNode act(String action, String params) {
        Elf elf = TestElf.create(dir, github, writer);
        JsonNode answer = new Actions(elf, () -> "gho_secret").run(action, TestElf.json(params));
        Views.write(elf);
        return answer;
    }

    JsonNode file(String path) throws Exception {
        return Json.MAPPER.readTree(dir.resolve(path).toFile());
    }

    @Test
    void anAgentStartAsksTheRunnerAndWritesTheThread() throws Exception {
        TestElf.create(dir, github, writer).api.watch("quarkusio");
        TestElf.create(dir, github, writer).api.board(true); // a refresh: board/cache.json
        JsonNode a = act("start", "{\"itemKey\": \"" + KEY + "\", \"action\": \"review\", \"question\": null}");
        JsonNode agent = a.path("agent");
        assertTrue(agent.path("id").asText().startsWith("quarkusio-quarkus-50087-"));
        assertEquals("PR", agent.path("checkout").path("kind").asText());
        assertTrue(agent.path("schema").asText().contains("\"reply\""));
        assertEquals("queued", a.path("thread").path("job").path("state").asText());

        // threads/<key>.json is DeskApi.thread; board.json shows it working.
        JsonNode thread = file("threads/quarkusio_quarkus_50087.json");
        assertEquals(KEY, thread.path("itemKey").asText());
        assertEquals(agent.path("id").asText(), thread.path("job").path("agent").asText());
        assertTrue(thread.path("runnerConnected").asBoolean());
        JsonNode board = file("board/board.json");
        assertEquals("working", board.path("threads").path(KEY).path("state").asText());
        assertTrue(board.path("flows").has(KEY));
        assertTrue(board.path("fetchedAt").isTextual()); // times as text, as the desk sent them
        assertTrue(Files.isRegularFile(dir.resolve("state/settings-view.json")));

        // The runner's agent-done takes the report in.
        String id = agent.path("id").asText();
        TestElf.report(TestElf.create(dir, github, writer), id, """
                {"id": "%s", "status": "done", "output": {"reply": "Fine.", "draft": null}, "session": "s1"}""".formatted(id));
        JsonNode done = act("agent-done", "{\"id\": \"" + id + "\"}");
        assertEquals("Fine.", done.path("turns").path(1).path("text").asText());
        assertEquals("answered", file("board/board.json").path("threads").path(KEY).path("state").asText());

        // Stop: the tab is told which agent to cancel.
        act("reply", "{\"itemKey\": \"" + KEY + "\", \"text\": \"More?\"}");
        JsonNode cancel = act("cancel", "{\"itemKey\": \"" + KEY + "\"}");
        assertTrue(cancel.path("cancelAgent").asText().startsWith("quarkusio-quarkus-50087-"));
    }

    @Test
    void aPushIsAnsweredForTheRunnerWithTheTokenAndFinishedByPostDone() throws Exception {
        Elf elf = TestElf.create(dir, github, writer);
        elf.threads.start(KEY, "review", null, "phillip-kruger", "t");
        String head = "a".repeat(40), fixed = "b".repeat(40);
        elf.threads.jobDone(elf.threads.view(KEY).job().id(), "s", TestElf.json("{\"reply\": \"Done.\", \"draft\": null}"), null,
                TestElf.json("{\"from\": \"%s\", \"to\": \"%s\", \"commits\": [{\"sha\": \"%s\", \"subject\": \"x\"}]}"
                        .formatted(head, fixed, fixed)));
        when(github.ci(anyString(), eq("quarkusio/quarkus"), eq(50087))).thenReturn(new GitHub.CiReport(head, "SUCCESS",
                List.of(), List.of(), "someone/quarkus", "feature", true));
        long draftId = elf.threads.view(KEY).draft().id();

        JsonNode preview = act("preview-post", "{\"draftId\": " + draftId + "}");
        JsonNode post = act("post", "{\"draftId\": " + draftId + ", \"confirm\": \"" + preview.path("confirm").asText() + "\"}");
        JsonNode push = post.path("push");
        assertEquals("quarkusio/quarkus", push.path("repo").asText());
        assertEquals(50087, push.path("number").asInt());
        assertEquals("someone/quarkus", push.path("headRepo").asText());
        assertEquals("feature", push.path("headRef").asText());
        assertEquals(head, push.path("from").asText());
        assertEquals(fixed, push.path("to").asText());
        assertFalse(push.path("squash").asBoolean());
        assertEquals("gho_secret", push.path("token").asText());
        // The token is in the answer only: never in a file.
        try (var files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(f).contains("gho_secret"), f.toString());
            }
        }

        JsonNode posted = act("post-done", "{\"draftId\": " + draftId + ", \"status\": 200}");
        assertEquals(KEY, posted.path("pushedTo").asText());
        assertEquals("https://github.com/quarkusio/quarkus/pull/50087/commits/" + fixed, posted.path("url").asText());
        assertThrows(IllegalStateException.class, () -> act("post-done", "{\"draftId\": " + draftId + ", \"status\": 200}"));
    }

    @Test
    void aSquashIsAnsweredForTheRunner() throws Exception {
        when(github.ci(anyString(), eq("quarkusio/quarkus"), eq(50087))).thenReturn(new GitHub.CiReport("a".repeat(40), "SUCCESS",
                List.of(), List.of(), "someone/quarkus", "feature", true));
        JsonNode a = act("squash", "{\"itemKey\": \"" + KEY + "\"}");
        assertEquals("quarkusio/quarkus", a.path("squash").path("repo").asText());
        // The runner keeps its squashed commit in agents/ and names the file; the tab only passes the name on.
        java.nio.file.Files.createDirectories(dir.resolve("agents"));
        java.nio.file.Files.writeString(dir.resolve("agents/squash-17.json"), """
                {"from": "%s", "to": "%s", "base": "main", "squashed": true, "replaces": 2, "commits": [{"sha": "%s", "subject": "x"}]}"""
                .formatted("a".repeat(40), "c".repeat(40), "c".repeat(40)));
        JsonNode done = act("squash-done", "{\"itemKey\": \"" + KEY + "\", \"status\": 200, \"file\": \"squash-17.json\"}");
        assertEquals("push", done.path("draft").path("kind").asText());
        assertTrue(done.path("draft").path("push").path("squashed").asBoolean());
        assertFalse(java.nio.file.Files.exists(dir.resolve("agents/squash-17.json")));
        assertThrows(IllegalArgumentException.class, () -> act("squash-done", "{\"itemKey\": \"" + KEY + "\", \"status\": 200, \"file\": \"../board/board.json\"}"));
    }

    @Test
    void bigReadsAnswerWithACacheFile() throws Exception {
        when(github.prDiff("gho_test", "quarkusio/quarkus", 50087, 2_000_000)).thenReturn(new GitHub.Diff("diff --git a/x b/x", false));
        JsonNode a = act("pr-diff", "{\"itemKey\": \"" + KEY + "\"}");
        assertEquals("pr-diff-quarkusio_quarkus_50087.json", a.path("file").asText());
        assertEquals("diff --git a/x b/x", file("cache/pr-diff-quarkusio_quarkus_50087.json").path("text").asText());
    }

    @Test
    void valuesAreCheckedAndUnknownActionsRefused() {
        assertThrows(IllegalArgumentException.class, () -> act("thread", "{\"itemKey\": \"not a key\"}"));
        assertThrows(IllegalArgumentException.class, () -> act("rm-rf", "{}"));
        assertEquals(List.of("quarkusio", "smallrye"), Actions.owners(" quarkusio, smallrye "));
        JsonNode s = act("watch-all", "{\"owners\": \"quarkusio smallrye\"}");
        assertEquals(2, s.path("scope").size());
        assertThrows(IllegalArgumentException.class, () -> act("update-draft", "{\"draftId\": 1, \"draft\": \"not json\"}"));
    }
}
