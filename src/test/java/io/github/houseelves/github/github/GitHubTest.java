package io.github.houseelves.github.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class GitHubTest {

    final ObjectMapper mapper = new ObjectMapper();

    JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    static final String PR_NODE = """
            {"__typename": "PullRequest", "number": 50031, "title": "Dev UI: streaming", "url": "https://github.com/quarkusio/quarkus/pull/50031",
             "createdAt": "2026-10-01T10:00:00Z", "updatedAt": "2026-10-05T10:00:00Z", "isDraft": false,
             "headRefName": "devui-stream", "headRefOid": "9a1b", "reviewDecision": "CHANGES_REQUESTED", "mergeable": "MERGEABLE",
             "repository": {"nameWithOwner": "quarkusio/quarkus"}, "author": {"login": "phillip-kruger"},
             "assignees": {"nodes": [{"login": "phillip-kruger"}]}, "comments": {"totalCount": 3},
             "commits": {"nodes": [{"commit": {"statusCheckRollup": {"state": "FAILURE"}}}]},
             "latestOpinionatedReviews": {"nodes": [{"state": "CHANGES_REQUESTED"}]}}
            """;

    @Test
    void parsesAPullRequest() throws Exception {
        Item i = GitHub.toItem(json(PR_NODE));
        assertEquals("quarkusio/quarkus#50031", i.key());
        assertEquals(Item.Kind.PR, i.kind());
        assertEquals("quarkusio", i.owner());
        assertEquals("FAILURE", i.ci());
        assertEquals("CHANGES_REQUESTED", i.reviewDecision());
        assertEquals(List.of("phillip-kruger"), i.assignees());
        assertEquals(3, i.comments());
        assertEquals(false, i.approved());
    }

    @Test
    void approvalFollowsGitHubsDecisionOrTheWritersReviews() {
        // Repos that require reviews: GitHub's decision is the answer.
        assertTrue(Item.isApproved("APPROVED", List.of()));
        assertEquals(false, Item.isApproved("REVIEW_REQUIRED", List.of("APPROVED")));
        assertEquals(false, Item.isApproved("CHANGES_REQUESTED", List.of("APPROVED")));
        // Repos that don't: a writer approved, and no writer's latest review asks for changes.
        assertTrue(Item.isApproved(null, List.of("APPROVED")));
        assertEquals(false, Item.isApproved(null, List.of("APPROVED", "CHANGES_REQUESTED")));
        assertEquals(false, Item.isApproved(null, List.of()));
    }

    @Test
    void parsesAnIssueWithoutPrFields() throws Exception {
        Item i = GitHub.toItem(json("""
                {"__typename": "Issue", "number": 7, "title": "t", "url": "u", "createdAt": "2026-10-01T10:00:00Z",
                 "updatedAt": "2026-10-02T10:00:00Z", "repository": {"nameWithOwner": "smallrye/smallrye-open-api"},
                 "author": null, "assignees": {"nodes": []}, "comments": {"totalCount": 0}}
                """));
        assertEquals(Item.Kind.ISSUE, i.kind());
        assertEquals("", i.author()); // deleted users ("ghost") come back as null
        assertNull(i.draft());
        assertNull(i.ci());
        assertNull(i.approved());
    }

    @Test
    void anyRunningCheckMeansInProgressEvenWhenGitHubSaysFailure() throws Exception {
        // quarkusio/quarkus#56953 during a re-run: the gate status still fails, one job is running again.
        assertEquals("PENDING", GitHub.ciState(json("""
                {"state": "FAILURE", "contexts": {
                  "checkRunCountsByState": [{"state": "IN_PROGRESS", "count": 1}, {"state": "SUCCESS", "count": 56}],
                  "statusContextCountsByState": [{"state": "FAILURE", "count": 1}]}}""")));
        assertEquals("PENDING", GitHub.ciState(json("""
                {"state": "SUCCESS", "contexts": {"checkRunCountsByState": [],
                  "statusContextCountsByState": [{"state": "PENDING", "count": 2}]}}""")));
        assertEquals("FAILURE", GitHub.ciState(json("""
                {"state": "FAILURE", "contexts": {"checkRunCountsByState": [{"state": "IN_PROGRESS", "count": 0},
                  {"state": "FAILURE", "count": 1}], "statusContextCountsByState": []}}""")));
        assertNull(GitHub.ciState(json("null")));
    }

    @Test
    void newReviewRequestsAndOthersMentionsBringItemsBack() throws Exception {
        assertEquals("Review requested", GitHub.reasonFor(json("""
                [{"__typename": "ReviewRequestedEvent", "requestedReviewer": {"login": "Phillip-Kruger"}}]"""), "phillip-kruger"));
        assertEquals("You were mentioned", GitHub.reasonFor(json("""
                [{"__typename": "IssueComment", "author": {"login": "gsmet"}, "body": "cc @phillip-kruger, can you look?"}]"""),
                "phillip-kruger"));
        assertEquals("You were mentioned", GitHub.reasonFor(json("""
                [{"__typename": "PullRequestReview", "author": {"login": "gsmet"}, "body": "",
                  "comments": {"nodes": [{"body": "@phillip-kruger is this right?"}]}}]"""), "phillip-kruger"));
        // Your own comments, other people's handles, and someone else's review request don't count.
        assertNull(GitHub.reasonFor(json("""
                [{"__typename": "IssueComment", "author": {"login": "phillip-kruger"}, "body": "@phillip-kruger note to self"},
                 {"__typename": "IssueComment", "author": {"login": "x"}, "body": "@phillip-kruger-bot and @phillipkruger"},
                 {"__typename": "ReviewRequestedEvent", "requestedReviewer": {"login": "gsmet"}}]"""), "phillip-kruger"));
    }

    @Test
    void ciWaitingForApprovalIsNeverGreen() throws Exception {
        // quarkusio/quarkus#56264: two bot checks passed, Quarkus CI waits for a maintainer.
        assertEquals(List.of(new GitHub.Approval(37703109423L)), GitHub.awaitingApproval(json("""
                {"checkSuites": {"nodes": [{"conclusion": "SUCCESS", "workflowRun": null},
                  {"conclusion": "ACTION_REQUIRED", "workflowRun": {"databaseId": 37703109423}}]}}""")));
        // quarkusio/quarkus#57185: a Dependabot PR's Quarkus CI held at the "dependabot" environment you review;
        // one you can't approve doesn't count.
        assertEquals(List.of(new GitHub.Approval(38490864478L, List.of(12056103218L))), GitHub.awaitingApproval(json("""
                {"checkSuites": {"nodes": [{"conclusion": "SUCCESS", "workflowRun": {"databaseId": 1,
                    "pendingDeploymentRequests": {"nodes": []}}},
                  {"conclusion": null, "workflowRun": {"databaseId": 38490864478, "pendingDeploymentRequests": {"nodes": [
                    {"currentUserCanApprove": true, "environment": {"databaseId": 12056103218}}]}}},
                  {"conclusion": null, "workflowRun": {"databaseId": 2, "pendingDeploymentRequests": {"nodes": [
                    {"currentUserCanApprove": false, "environment": {"databaseId": 3}}]}}}]}}""")));
        Item green = GitHub.toItem(json(PR_NODE.replace("{\"state\": \"FAILURE\"}", "{\"state\": \"SUCCESS\"}")));
        assertEquals("SUCCESS", green.ci());
        Item waiting = green.withDetails("BLOCKED", true);
        assertEquals(GitHub.AWAITING_APPROVAL, waiting.ci());
        assertEquals("BLOCKED", waiting.mergeState());
        assertEquals("SUCCESS", green.withDetails("CLEAN", false).ci());
    }

    @Test
    void prWithoutChecksHasNoCi() throws Exception {
        Item i = GitHub.toItem(json(PR_NODE.replace("{\"state\": \"FAILURE\"}", "null")));
        assertNull(i.ci());
    }

    @Test
    void followsPagesAndReportsTruncation() throws Exception {
        GitHubApi api = mock(GitHubApi.class);
        when(api.graphql(eq("Bearer t"), any())).thenReturn(
                json("""
                        {"data": {"search": {"issueCount": 1500, "pageInfo": {"hasNextPage": true, "endCursor": "c1"},
                                             "nodes": [%s]}}}""".formatted(PR_NODE)),
                json("""
                        {"data": {"search": {"issueCount": 1500, "pageInfo": {"hasNextPage": false, "endCursor": null},
                                             "nodes": [%s]}}}""".formatted(PR_NODE.replace("50031", "50032"))));

        GitHub.SearchResult r = new GitHub(api).search("t", "is:open involves:@me", 1000);
        assertEquals(2, r.items().size());
        assertTrue(r.truncated());
        verify(api, times(2)).graphql(eq("Bearer t"), any(Map.class));
    }

    @Test
    void anEmptyPageEndsTheSearchEvenIfGitHubClaimsMore() throws Exception {
        GitHubApi api = mock(GitHubApi.class);
        when(api.graphql(eq("Bearer t"), any())).thenReturn(
                json("""
                        {"data": {"search": {"issueCount": 500, "pageInfo": {"hasNextPage": true, "endCursor": "c1"},
                                             "nodes": [%s]}}}""".formatted(PR_NODE)),
                json("""
                        {"data": {"search": {"issueCount": 500, "pageInfo": {"hasNextPage": true, "endCursor": "c2"},
                                             "nodes": []}}}"""));
        GitHub.SearchResult r = new GitHub(api).search("t", "is:open involves:@me", 1000);
        assertEquals(1, r.items().size());
        verify(api, times(2)).graphql(eq("Bearer t"), any(Map.class));
    }

    @Test
    void activityIsWhatOthersDidAfterYourLastReviewOrComment() throws Exception {
        GitHub.Activity a = GitHub.activityFrom("quarkusio/quarkus#56953", json("""
                {"headRefOid": "6c82981", "reviewDecision": "REVIEW_REQUIRED", "timelineItems": {"nodes": [
                  {"__typename": "IssueComment", "createdAt": "2026-09-30T10:00:00Z", "body": "first", "author": {"login": "arnabnandy7"}, "url": "u0"},
                  {"__typename": "PullRequestReview", "submittedAt": "2026-10-01T07:15:04Z", "state": "CHANGES_REQUESTED", "body": "fix the brace",
                   "author": {"login": "phillip-kruger"}, "comments": {"totalCount": 2}, "url": "u1"},
                  {"__typename": "PullRequestCommit", "url": "u2", "commit": {"oid": "6c82981ec2f", "messageHeadline": "Add missing closing brace",
                   "committedDate": "2026-10-02T09:00:00Z", "author": {"name": "Arnab", "user": {"login": "arnabnandy7"}}}},
                  {"__typename": "IssueComment", "createdAt": "2026-10-02T09:05:00Z", "body": "Done, <!-- bot -->please re-review", "author": {"login": "arnabnandy7"}, "url": "u3"},
                  {"__typename": "IssueComment", "createdAt": "2026-10-02T10:00:00Z", "body": "thanks", "author": {"login": "phillip-kruger"}, "url": "u4"},
                  {"__typename": "HeadRefForcePushedEvent", "createdAt": "2026-10-02T09:01:00Z", "actor": {"login": "arnabnandy7"}}
                ]}}"""), "phillip-kruger");
        // Your last comment (10:00) is the latest thing you did; nothing from others came after it.
        assertEquals("your comment", a.sinceWhat());
        assertTrue(a.events().isEmpty());

        GitHub.Activity sinceReview = GitHub.activityFrom("k", json("""
                {"headRefOid": "x", "timelineItems": {"nodes": [
                  {"__typename": "PullRequestReview", "submittedAt": "2026-10-01T07:15:04Z", "state": "APPROVED", "body": "",
                   "author": {"login": "phillip-kruger"}, "comments": {"totalCount": 0}},
                  {"__typename": "PullRequestCommit", "commit": {"oid": "abcdef1234", "messageHeadline": "Address review",
                   "committedDate": "2026-10-02T09:00:00Z", "author": {"name": "A", "user": {"login": "arnabnandy7"}}}},
                  {"__typename": "IssueComment", "createdAt": "2026-10-02T09:05:00Z", "body": "Done <!-- hidden -->  please   look", "author": {"login": "arnabnandy7"}}
                ]}}"""), "phillip-kruger");
        assertEquals("your review", sinceReview.sinceWhat());
        assertEquals(List.of("commit", "comment"), sinceReview.events().stream().map(GitHub.Event::kind).toList());
        assertEquals("abcdef1 Address review", sinceReview.events().get(0).text());
        assertEquals("Done please look", sinceReview.events().get(1).text());
    }

    @Test
    void errorsWithoutDataFail() throws Exception {
        GitHubApi api = mock(GitHubApi.class);
        when(api.graphql(any(), any())).thenReturn(json("{\"errors\": [{\"message\": \"Bad credentials\"}]}"));
        var e = assertThrows(GitHub.GitHubException.class, () -> new GitHub(api).viewerOrgs("t"));
        assertEquals("Bad credentials", e.getMessage());
    }

    @Test
    void linkedPrsKeepOpenPrsAndFixingWins() throws Exception {
        GitHubApi api = mock(GitHubApi.class);
        when(api.graphql(eq("Bearer t"), any())).thenReturn(json("""
                {"data": {"i0": {"issue": {
                  "closedByPullRequestsReferences": {"nodes": [
                    {"number": 57310, "title": "Fix it", "url": "u1", "state": "OPEN", "isDraft": false,
                     "author": {"login": "jnbdz"}, "repository": {"nameWithOwner": "quarkusio/quarkus"}}]},
                  "timelineItems": {"nodes": [
                    {"willCloseTarget": false, "source": {"number": 57310, "title": "Fix it", "url": "u1", "state": "OPEN",
                     "isDraft": false, "author": {"login": "jnbdz"}, "repository": {"nameWithOwner": "quarkusio/quarkus"}}},
                    {"willCloseTarget": false, "source": {"number": 9, "title": "Related", "url": "u2", "state": "OPEN",
                     "isDraft": true, "author": {"login": "x"}, "repository": {"nameWithOwner": "other/repo"}}},
                    {"willCloseTarget": true, "source": {"number": 8, "title": "Old", "url": "u3", "state": "CLOSED",
                     "isDraft": false, "author": {"login": "x"}, "repository": {"nameWithOwner": "quarkusio/quarkus"}}},
                    {"willCloseTarget": false, "source": {}}]}}},
                 "i1": {"issue": {"closedByPullRequestsReferences": {"nodes": []}, "timelineItems": {"nodes": []}}}}}"""));
        var links = new GitHub(api).linkedPrs("t", java.util.List.of("quarkusio/quarkus#57298", "quarkusio/quarkus#1"));
        var prs = links.get("quarkusio/quarkus#57298");
        assertEquals(2, prs.size());
        assertEquals("quarkusio/quarkus#57310", prs.get(0).key());
        assertTrue(prs.get(0).fixes());
        assertEquals("other/repo#9", prs.get(1).key());
        assertTrue(!prs.get(1).fixes() && prs.get(1).draft());
        assertTrue(!links.containsKey("quarkusio/quarkus#1"));
    }

    @Test
    void feedbackIsTheLastPersonToSpeakAndWhetherThatWasAfterYou() throws Exception {
        JsonNode pr = json("""
                {"lastCommit": {"nodes": [{"commit": {"committedDate": "2026-10-11T23:21:35Z"}}]},
                 "allReviews": {"nodes": [
                   {"state": "COMMENTED", "submittedAt": "2026-10-13T14:07:40Z", "author": {"__typename": "User", "login": "brunobat"}},
                   {"state": "PENDING", "submittedAt": null, "author": {"__typename": "User", "login": "brunobat"}},
                   {"state": "COMMENTED", "submittedAt": "2026-10-13T15:38:39Z", "author": {"__typename": "User", "login": "brunobat"}}]},
                 "comments": {"nodes": [
                   {"createdAt": "2026-10-13T01:29:09Z", "author": {"__typename": "User", "login": "phillip-kruger"}},
                   {"createdAt": "2026-10-13T20:00:00Z", "author": {"__typename": "Bot", "login": "quarkus-bot"}}]},
                 "reviewThreads": {"nodes": [{"isResolved": false}, {"isResolved": true}, {"isResolved": false}]}}
                """);
        GitHub.Feedback f = GitHub.feedback(pr, "phillip-kruger");
        assertEquals("brunobat", f.who());
        assertEquals("COMMENTED", f.state());
        assertEquals("2026-10-13T15:38:39Z", f.at().toString());
        assertTrue(f.afterYou());
        assertEquals(2, f.openThreads());

        // You replied after: not your move any more.
        ((com.fasterxml.jackson.databind.node.ArrayNode) pr.path("comments").path("nodes")).addObject()
                .put("createdAt", "2026-10-13T16:00:00Z").putObject("author").put("__typename", "User").put("login", "phillip-kruger");
        assertTrue(!GitHub.feedback(pr, "phillip-kruger").afterYou());
    }

    @Test
    void whoToAskAgainIsWhoReviewedBeforeYourLatestCommitAndIsntAskedYet() throws Exception {
        JsonNode pr = json("""
                {"lastCommit": {"nodes": [{"commit": {"committedDate": "2026-10-14T10:00:00Z"}}]},
                 "allReviews": {"nodes": [
                   {"state": "CHANGES_REQUESTED", "submittedAt": "2026-10-13T14:00:00Z", "author": {"__typename": "User", "login": "brunobat"}},
                   {"state": "COMMENTED", "submittedAt": "2026-10-13T15:00:00Z", "author": {"__typename": "User", "login": "geoand"}},
                   {"state": "APPROVED", "submittedAt": "2026-10-13T16:00:00Z", "author": {"__typename": "User", "login": "gsmet"}},
                   {"state": "COMMENTED", "submittedAt": "2026-10-14T11:00:00Z", "author": {"__typename": "User", "login": "mkouba"}},
                   {"state": "COMMENTED", "submittedAt": "2026-10-13T12:00:00Z", "author": {"__typename": "Bot", "login": "quarkus-bot"}}]},
                 "comments": {"nodes": []},
                 "reviewThreads": {"nodes": []},
                 "reviewRequests": {"nodes": [{"requestedReviewer": {"__typename": "User", "login": "geoand"}}]}}
                """);
        GitHub.Feedback f = GitHub.feedback(pr, "phillip-kruger");
        // gsmet approved, mkouba spoke after your commit, geoand is asked already, the bot doesn't count.
        assertEquals(List.of("brunobat"), f.reReview());
        assertEquals(List.of("geoand"), f.requested());
        assertEquals(List.of(), f.asked(List.of("brunobat")).reReview());
    }

    @Test
    void reviewsPutOpenThreadsWithNewRepliesFirstAndSkipBotsAndEmptyWrappers() throws Exception {
        JsonNode pr = json("""
                {"url": "https://github.com/quarkusio/quarkus/pull/56951",
                 "lastCommit": {"nodes": [{"commit": {"committedDate": "2026-10-11T23:21:35Z"}}]},
                 "allReviews": {"nodes": [
                   {"state": "COMMENTED", "body": "", "submittedAt": "2026-10-13T15:38:39Z", "author": {"__typename": "User", "login": "brunobat"}},
                   {"state": "COMMENTED", "body": "Found a bug", "submittedAt": "2026-10-13T14:07:40Z", "author": {"__typename": "User", "login": "brunobat"}}]},
                 "comments": {"nodes": [
                   {"body": "CI report", "createdAt": "2026-10-13T20:00:00Z", "author": {"__typename": "Bot", "login": "quarkus-bot"}},
                   {"body": "Updated", "createdAt": "2026-10-13T01:29:09Z", "author": {"__typename": "User", "login": "phillip-kruger"}}]},
                 "reviewThreads": {"nodes": [
                   {"isResolved": true, "isOutdated": false, "path": "a/A.java", "line": 3, "originalLine": 3,
                    "comments": {"nodes": [{"body": "old", "createdAt": "2026-10-01T00:00:00Z", "diffHunk": "@@ x", "author": {"login": "brunobat"}}]}},
                   {"isResolved": false, "isOutdated": true, "path": "b/B.java", "line": null, "originalLine": 40,
                    "comments": {"nodes": [{"body": "Why?", "createdAt": "2026-10-13T14:07:40Z", "diffHunk": "@@ y", "author": {"login": "brunobat"}}]}},
                   {"isResolved": false, "isOutdated": false, "path": "a/A.java", "line": 9, "originalLine": 9,
                    "comments": {"nodes": [{"body": "Hmm", "createdAt": "2026-10-10T00:00:00Z", "diffHunk": "@@ z", "author": {"login": "brunobat"}},
                                           {"body": "Done", "createdAt": "2026-10-11T00:00:00Z", "diffHunk": "@@ z", "author": {"login": "phillip-kruger"}}]}}]}}
                """);
        GitHub.Reviews r = GitHub.reviewsFrom("quarkusio/quarkus#56951", pr, "phillip-kruger");
        assertEquals("2026-10-13T01:29:09Z", r.yourLast().toString());
        assertEquals(List.of("Updated", "Found a bug"), r.said().stream().map(GitHub.Said::body).toList()); // oldest first
        assertTrue(!r.said().get(0).isNew() && r.said().get(1).isNew());
        assertEquals(List.of("b/B.java", "a/A.java", "a/A.java"), r.threads().stream().map(GitHub.ReviewThread::path).toList());
        assertEquals(40, r.threads().get(0).line()); // outdated: the original line
        assertTrue(r.threads().get(0).isNew() && !r.threads().get(1).isNew() && r.threads().get(2).resolved());
    }

    @Test
    void aFailedRunThatANewerRunOfTheSameWorkflowReplacedDoesntCount() throws Exception {
        // quarkusio/search.quarkus.io#636: Build run 1554 failed, a close and reopen started 1555, which passed.
        String suites = """
                "checkSuites": {"nodes": [
                  {"conclusion": "FAILURE", "workflowRun": {"databaseId": 39177557036, "workflow": {"databaseId": 7}}},
                  {"conclusion": "SUCCESS", "workflowRun": {"databaseId": 39181508545, "workflow": {"databaseId": 7}}},
                  {"conclusion": "NEUTRAL", "workflowRun": null},
                  {"conclusion": null, "workflowRun": null}]}""";
        String rollup = """
                "statusCheckRollup": {"state": "FAILURE", "contexts": {"checkRunCountsByState":
                  [{"state": "FAILURE", "count": 1}, {"state": "SUCCESS", "count": 1}], "statusContextCountsByState": []}}""";
        JsonNode commit = json("{" + rollup + ", \"status\": null, " + suites + "}");
        assertEquals(java.util.Set.of(39177557036L), GitHub.replacedRuns(commit));
        assertTrue(GitHub.passedOnRerun(commit));
        assertEquals("SUCCESS", GitHub.commitCi(commit));

        // The newer run failed too: still failing.
        JsonNode bothFailed = json("{" + rollup + ", " + suites.replace("\"SUCCESS\"", "\"FAILURE\"") + "}");
        assertFalse(GitHub.passedOnRerun(bothFailed));
        assertEquals("FAILURE", GitHub.commitCi(bothFailed));

        // Another workflow failed: still failing.
        JsonNode other = json("{" + rollup + ", " + suites.replace("{\"conclusion\": null, \"workflowRun\": null}",
                "{\"conclusion\": \"FAILURE\", \"workflowRun\": {\"databaseId\": 1, \"workflow\": {\"databaseId\": 8}}}") + "}");
        assertFalse(GitHub.passedOnRerun(other));

        // A failed commit status: still failing.
        assertFalse(GitHub.passedOnRerun(json("{" + rollup + ", \"status\": {\"state\": \"FAILURE\"}, " + suites + "}")));

        // Only one run: GitHub's rollup stands.
        assertFalse(GitHub.passedOnRerun(json("""
                {"checkSuites": {"nodes": [{"conclusion": "FAILURE", "workflowRun": {"databaseId": 1, "workflow": {"databaseId": 7}}}]}}""")));
    }

    @Test
    void theRecapSummarySkipsWhatTheTemplateLeftAndKeepsTheFirstRealParagraph() {
        String body = """
                <!-- Thanks for the PR! Please describe it below. -->
                ## Description

                Fixes #1234

                The Dev UI log stream now **reconnects** after the app restarts, so the [log viewer](https://x.y/z)
                keeps showing new lines.

                - [x] Tests added
                """;
        assertEquals("The Dev UI log stream now reconnects after the app restarts, so the log viewer keeps showing new lines.",
                GitHub.summary(body));
    }

    @Test
    void anIssueFormKeepsTheAnswerAndDropsTheQuestionsAndTheCode() {
        String body = """
                ### Describe the bug

                ```
                java.lang.NullPointerException
                ```

                _No response_

                **Expected behavior**

                Starting dev mode with a custom profile fails when the config has no default.
                """;
        assertEquals("Starting dev mode with a custom profile fails when the config has no default.", GitHub.summary(body));
    }

    @Test
    void aListBecomesOneLineAndALongOneStopsAtASentence() {
        assertEquals("Adds the agent MCP tool; Updates the docs", GitHub.summary("- Adds the agent MCP tool\n- Updates the docs"));
        String longer = "This is a sentence that goes on for a while. ".repeat(10);
        String s = GitHub.summary(longer);
        assertTrue(s.length() <= 300 && s.endsWith("."), s);
        assertEquals("#52528 moved the console out, so the classes move to io.quarkus.devui.deployment.",
                GitHub.summary("#52528 moved the console out, so the classes move to `io.quarkus.devui.deployment`.\n\n- Relates to: #51873"));
        assertEquals("Publishes them at https://smallrye.io/docs/<version>/ for each release.",
                GitHub.summary("Publishes them at https://smallrye.io/docs/<version>/ for each <b>release</b>."));
        assertEquals("", GitHub.summary(""));
        assertEquals("", GitHub.summary("<!-- nothing -->\n## Checklist\n- [ ] done"));
    }

    @Test
    void aCommentsGistIsPlainText() {
        assertEquals("Status for workflow Quarkus CI: Failing Jobs", GitHub.snippet("""
                # Status for workflow `Quarkus CI`

                This is the status report for running `Quarkus CI` on commit [a7a5c81](https://x/y).

                ## Failing Jobs

                | Status | Name |
                |---|---|
                | ✖ | JVM Tests |
                """));
        assertEquals("Looks good, see the docs", GitHub.snippet("<!-- hi -->Looks **good**,\nsee [the docs](https://x)"));
    }
}
