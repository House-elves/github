package io.github.houseelves.github.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class GitHubWriterTest {

    final ObjectMapper mapper = new ObjectMapper();

    /** GitHub, faked: records what it was asked to send and answers with a canned status and body. */
    record Sent(String method, String path, JsonNode body) {
    }

    final List<Sent> sent = new ArrayList<>();
    final List<ObjectNode> queries = new ArrayList<>();

    GitHubWriter writer(int status, String body, RuntimeException failure, JsonNode... graphql) {
        List<JsonNode> answers = new ArrayList<>(List.of(graphql));
        return new GitHubWriter("phillip-kruger", new GitHubWriter.Transport() {
            @Override
            public GitHubWriter.Answer rest(String method, String path, String json) {
                try {
                    sent.add(new Sent(method, path, mapper.readTree(json)));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                if (failure != null) throw failure;
                return new GitHubWriter.Answer(status, body);
            }

            @Override
            public JsonNode graphql(ObjectNode request) {
                queries.add(request);
                return answers.remove(0);
            }
        });
    }

    @Test
    void sendsTheExactRequestAndReadsGitHubsAnswer() throws Exception {
        JsonNode r = writer(200, "{\"html_url\": \"https://github.com/a/b/issues/7\"}", null).close("a/b", 7);
        assertEquals("https://github.com/a/b/issues/7", r.path("html_url").asText());
        assertEquals("PATCH", sent.get(0).method());
        assertEquals("/repos/a/b/issues/7", sent.get(0).path());
        assertEquals("closed", sent.get(0).body().path("state").asText());

        writer(200, "{}", null).createReview("a/b", 7, Map.of("event", "COMMENT", "body", "x"));
        assertEquals("POST", sent.get(1).method());
        assertEquals("/repos/a/b/pulls/7/reviews", sent.get(1).path());
    }

    @Test
    void refusalsAndAnUnreachableGitHubBecomeWriteFailed() throws Exception {
        var e = assertThrows(GitHubWriter.WriteFailed.class,
                () -> writer(422, "{\"message\": \"Validation Failed\"}", null).createComment("a/b", 1, Map.of("body", "x")));
        assertEquals(422, e.status);
        assertTrue(e.body.contains("Validation Failed"));

        var offline = assertThrows(GitHubWriter.WriteFailed.class, () -> writer(0, null,
                new IllegalStateException("Couldn't reach GitHub: connection refused")).close("a/b", 1));
        assertEquals(0, offline.status);
        assertTrue(offline.getMessage().startsWith("Couldn't reach GitHub"));
    }

    @Test
    void onlyTheWritesTheRunnerAllowedAreSent() {
        GitHubWriter w = writer(200, "{}", null);
        // Someone else as assignee, a PR from someone else's fork, an unpinned merge, syncing someone else's fork.
        assertThrows(GitHubWriter.WriteFailed.class, () -> w.assign("a/b", 1, "someone-else"));
        assertThrows(GitHubWriter.WriteFailed.class, () -> w.createPull("a/b", "t", "someone-else:fix", "main", "b", true));
        assertThrows(GitHubWriter.WriteFailed.class, () -> w.merge("a/b", 1, "merge", "not-a-sha"));
        assertThrows(GitHubWriter.WriteFailed.class, () -> w.merge("a/b", 1, "octopus", "c".repeat(40)));
        assertThrows(GitHubWriter.WriteFailed.class, () -> w.syncFork("someone-else/b", "main"));
        assertThrows(GitHubWriter.WriteFailed.class, () -> w.createIssue("a/b", " ", "body"));
        assertThrows(GitHubWriter.WriteFailed.class, () -> w.approveDeployment("a/b", 1, List.of(1L, 2L, 3L, 4L)));
        assertTrue(sent.isEmpty());

        w.assign("a/b", 1, "phillip-kruger");
        w.createPull("a/b", "t", "phillip-kruger:fix-1-aaaaaaa", "main", "b", true);
        w.merge("a/b", 1, "squash", "c".repeat(40));
        w.syncFork("phillip-kruger/b", "main");
        w.rerunFailedJobs("a/b", 5);
        w.approveRun("a/b", 6);
        w.approveDeployment("a/b", 7, List.of(9L));
        w.reopen("a/b", 1);
        w.requestReviewers("a/b", 1, List.of("brunobat"));
        assertEquals(9, sent.size());

        // Reviewers: people, not yourself, at most 10.
        assertFalse(GitHubWriter.allowedWrite("POST", "/repos/a/b/pulls/1/requested_reviewers",
                mapper.createObjectNode().set("reviewers", mapper.valueToTree(List.of("phillip-kruger"))), "phillip-kruger"));
        assertFalse(GitHubWriter.allowedWrite("POST", "/repos/a/b/pulls/1/requested_reviewers",
                mapper.createObjectNode().put("team_reviewers", "x"), "phillip-kruger"));

        assertFalse(GitHubWriter.allowedWrite("DELETE", "/repos/a/b", mapper.createObjectNode(), "phillip-kruger"));
        assertFalse(GitHubWriter.allowedWrite("POST", "/repos/a/b/git/refs", mapper.createObjectNode(), "phillip-kruger"));
    }

    @Test
    void readyForReviewIsOnlyForYourOwnDraftPrs() throws Exception {
        JsonNode mine = mapper.readTree("{\"data\": {\"repository\": {\"pullRequest\": {\"id\": \"PR_1\", \"isDraft\": true, \"author\": {\"login\": \"phillip-kruger\"}}}}}");
        JsonNode done = mapper.readTree("{\"data\": {\"markPullRequestReadyForReview\": {\"pullRequest\": {\"isDraft\": false}}}}");
        writer(200, "{}", null, mine, done).readyForReview("a/b", 1);
        assertTrue(queries.get(1).path("query").asText().contains("markPullRequestReadyForReview"));
        assertEquals("PR_1", queries.get(1).path("variables").path("id").asText());

        JsonNode theirs = mapper.readTree("{\"data\": {\"repository\": {\"pullRequest\": {\"id\": \"PR_2\", \"isDraft\": true, \"author\": {\"login\": \"x\"}}}}}");
        var e = assertThrows(GitHubWriter.WriteFailed.class, () -> writer(200, "{}", null, theirs).readyForReview("a/b", 2));
        assertTrue(e.getMessage().contains("isn't your PR"));
    }

    @Test
    void updateBranchIsARebasePinnedToTheHead() throws Exception {
        JsonNode found = mapper.readTree("{\"data\": {\"repository\": {\"pullRequest\": {\"id\": \"PR_3\"}}}}");
        JsonNode refused = mapper.readTree("{\"errors\": [{\"message\": \"Head sha didn't match expected head sha\"}]}");
        var e = assertThrows(GitHubWriter.WriteFailed.class,
                () -> writer(200, "{}", null, found, refused).updateBranch("a/b", 3, "a".repeat(40), "REBASE"));
        assertEquals(422, e.status);
        assertTrue(e.body.contains("Head sha didn't match"));
        assertEquals("a".repeat(40), queries.get(1).path("variables").path("head").asText());
        assertThrows(GitHubWriter.WriteFailed.class, () -> writer(200, "{}", null).updateBranch("a/b", 3, "a".repeat(40), "MERGE"));
    }
}
