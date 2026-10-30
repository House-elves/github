package io.github.houseelves.github.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.elf.Log;

/**
 * Writes to GitHub, as the elf's account with its token. The desk's writes went through the runner, which
 * accepted only the requests in its allowlist; the elf keeps that list as a guard on what it writes itself
 * ({@link #allowedWrite}). Only {@link io.github.houseelves.github.thread.Poster} and the services the board's
 * GitHub writes go through call this, after you confirm in the desk's bar.
 *
 * <p>
 * Pushing commits isn't here: the agent's commits are in the runner's worktree, so the elf answers with
 * {@code {"push": ...}} and the runner pushes (see {@link io.github.houseelves.github.thread.Poster#post}).
 */
public class GitHubWriter {

    private static final Log LOG = Log.of(GitHubWriter.class);

    static final String REPO = "/repos/[A-Za-z0-9-]+/[A-Za-z0-9._-]+";
    static final Pattern REVIEW = Pattern.compile(REPO + "/pulls/\\d+/reviews");
    static final Pattern COMMENT = Pattern.compile(REPO + "/issues/\\d+/comments");
    static final Pattern ISSUE = Pattern.compile(REPO + "/issues/\\d+");
    static final Pattern MERGE = Pattern.compile(REPO + "/pulls/\\d+/merge");
    static final Pattern RERUN = Pattern.compile(REPO + "/actions/runs/\\d+/rerun-failed-jobs");
    static final Pattern APPROVE = Pattern.compile(REPO + "/actions/runs/\\d+/approve");
    static final Pattern PENDING_DEPLOYMENTS = Pattern.compile(REPO + "/actions/runs/\\d+/pending_deployments");
    static final Pattern ASSIGNEES = Pattern.compile(REPO + "/issues/\\d+/assignees");
    static final Pattern REVIEWERS = Pattern.compile(REPO + "/pulls/\\d+/requested_reviewers");
    static final Pattern ISSUES = Pattern.compile(REPO + "/issues");
    static final Pattern PULLS = Pattern.compile(REPO + "/pulls");
    static final Pattern MERGE_UPSTREAM = Pattern.compile(REPO + "/merge-upstream");
    public static final Pattern REPO_NAME = Pattern.compile("[A-Za-z0-9-]+/[A-Za-z0-9._-]+");
    public static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]{1,250}");
    public static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    /** How the writes reach GitHub: REST and GraphQL with the token. Tests fake it. */
    public interface Transport {
        /** {status, body} for a REST call. */
        Answer rest(String method, String path, String body);

        /** GitHub's GraphQL answer. */
        JsonNode graphql(ObjectNode request);
    }

    public record Answer(int status, String body) {
    }

    /** GitHub said no (or couldn't be reached). {@code body} is GitHub's JSON response when there is one. */
    public static class WriteFailed extends RuntimeException {
        public final int status;
        public final String body;

        public WriteFailed(int status, String body, String message) {
            super(message);
            this.status = status;
            this.body = body;
        }
    }

    final ObjectMapper mapper = Json.MAPPER;
    /** Who it writes as: the account acting now (the item's, when the board has several). */
    final Supplier<String> login;
    final Transport transport;

    /** Writes as {@code login}, with the token {@code token} gives (asked for only when something is written). */
    public GitHubWriter(Supplier<String> login, Supplier<String> token) {
        this(login, http(token));
    }

    public GitHubWriter(String login, Transport transport) {
        this(() -> login, transport);
    }

    public GitHubWriter(Supplier<String> login, Transport transport) {
        this.login = login;
        this.transport = transport;
    }

    /** body: {event, body, comments: [{path, line, side, body}]} */
    public JsonNode createReview(String repo, int number, Map<String, Object> body) {
        return write("POST", "/repos/" + repo + "/pulls/" + number + "/reviews", body);
    }

    /** Works for issues and PRs. body: {body} */
    public JsonNode createComment(String repo, int number, Map<String, Object> body) {
        return write("POST", "/repos/" + repo + "/issues/" + number + "/comments", body);
    }

    /** Opens a new issue. body: {title, body} */
    public JsonNode createIssue(String repo, String title, String body) {
        return write("POST", "/repos/" + repo + "/issues", Map.of("title", title, "body", body));
    }

    /**
     * Opens a PR from {@code head} ("you:branch", nobody else's fork) onto {@code base}, as a draft
     * ({@link #readyForReview} later) or ready for review.
     */
    public JsonNode createPull(String repo, String title, String head, String base, String body, boolean draft) {
        return write("POST", "/repos/" + repo + "/pulls",
                Map.of("title", title, "head", head, "base", base, "body", body, "draft", draft));
    }

    /** Marks your own draft PR ready for review (GitHub only does this through GraphQL). Nobody else's PR. */
    public JsonNode readyForReview(String repo, int number) {
        if (!REPO_NAME.matcher(repo).matches() || number <= 0) {
            throw new WriteFailed(0, null, "Refused ready for review with unexpected parameters");
        }
        String[] r = repo.split("/", 2);
        ObjectNode find = mapper.createObjectNode().put("query", "query($o: String!, $n: String!, $p: Int!) { "
                + "repository(owner: $o, name: $n) { pullRequest(number: $p) { id isDraft author { login } } } }");
        find.putObject("variables").put("o", r[0]).put("n", r[1]).put("p", number);
        JsonNode pr = graphql(find).path("data").path("repository").path("pullRequest");
        if (pr.path("id").asText(null) == null) throw new WriteFailed(0, null, "No pull request " + repo + "#" + number);
        if (!login.get().equalsIgnoreCase(pr.path("author").path("login").asText())) {
            throw new WriteFailed(0, null, "Refused: " + repo + "#" + number + " isn't your PR");
        }
        if (!pr.path("isDraft").asBoolean()) return mapper.createObjectNode();
        ObjectNode ready = mapper.createObjectNode().put("query", "mutation($id: ID!) { "
                + "markPullRequestReadyForReview(input: { pullRequestId: $id }) { pullRequest { isDraft } } }");
        ready.putObject("variables").put("id", pr.path("id").asText());
        refused(graphql(ready));
        LOG.infof("Marked %s#%d ready for review as %s", repo, number, login.get());
        return mapper.createObjectNode();
    }

    /** Brings a branch of your fork up to date with its upstream, as GitHub's "Sync fork" does. */
    public JsonNode syncFork(String fork, String branch) {
        return write("POST", "/repos/" + fork + "/merge-upstream", Map.of("branch", branch));
    }

    /** Closes an issue or PR. */
    public JsonNode close(String repo, int number) {
        return write("PATCH", "/repos/" + repo + "/issues/" + number, Map.of("state", "closed"));
    }

    /** Adds {@code login} (you: nobody else) to an issue's or PR's assignees. */
    public JsonNode assign(String repo, int number, String login) {
        return write("POST", "/repos/" + repo + "/issues/" + number + "/assignees", Map.of("assignees", List.of(login)));
    }

    /** Asks these people to review a PR (again): only people, no teams. */
    public JsonNode requestReviewers(String repo, int number, List<String> logins) {
        return write("POST", "/repos/" + repo + "/pulls/" + number + "/requested_reviewers", Map.of("reviewers", logins));
    }

    /** Reopens an issue or PR (with close, this re-runs a PR's CI when you can't re-run its workflows). */
    public JsonNode reopen(String repo, int number) {
        return write("PATCH", "/repos/" + repo + "/issues/" + number, Map.of("state", "open"));
    }

    /** Merges a PR, but only if its head is still {@code sha}. method: merge, squash or rebase. */
    public JsonNode merge(String repo, int number, String method, String sha) {
        return write("PUT", "/repos/" + repo + "/pulls/" + number + "/merge", Map.of("merge_method", method, "sha", sha));
    }

    /** Re-runs the failed jobs of one GitHub Actions workflow run. */
    public JsonNode rerunFailedJobs(String repo, long runId) {
        return write("POST", "/repos/" + repo + "/actions/runs/" + runId + "/rerun-failed-jobs", Map.of());
    }

    /**
     * GitHub's "Update branch" for a PR behind its base, done by GitHub, and only if the PR's head is still
     * {@code expectedHead}. REBASE only: no merge commits.
     */
    public JsonNode updateBranch(String repo, int number, String expectedHead, String method) {
        if (!REPO_NAME.matcher(repo).matches() || number <= 0 || !SHA.matcher(expectedHead).matches()
                || !method.equals("REBASE")) {
            throw new WriteFailed(0, null, "Refused a branch update with unexpected parameters");
        }
        String[] r = repo.split("/", 2);
        ObjectNode find = mapper.createObjectNode().put("query",
                "query($o: String!, $n: String!, $p: Int!) { repository(owner: $o, name: $n) { pullRequest(number: $p) { id } } }");
        find.putObject("variables").put("o", r[0]).put("n", r[1]).put("p", number);
        String id = graphql(find).path("data").path("repository").path("pullRequest").path("id").asText(null);
        if (id == null) throw new WriteFailed(0, null, "No pull request " + repo + "#" + number);
        ObjectNode update = mapper.createObjectNode().put("query",
                "mutation($id: ID!, $head: GitObjectID!, $how: PullRequestBranchUpdateMethod!) { "
                        + "updatePullRequestBranch(input: { pullRequestId: $id, expectedHeadOid: $head, updateMethod: $how }) "
                        + "{ pullRequest { headRefOid } } }");
        update.putObject("variables").put("id", id).put("head", expectedHead).put("how", method);
        JsonNode done = graphql(update);
        refused(done);
        LOG.infof("Updated %s#%d by %s as %s", repo, number, method.toLowerCase(), login.get());
        return done.path("data").path("updatePullRequestBranch");
    }

    /** Approves a workflow run waiting for a maintainer (a fork PR's CI): GitHub's "Approve and run". */
    public JsonNode approveRun(String repo, long runId) {
        return write("POST", "/repos/" + repo + "/actions/runs/" + runId + "/approve", Map.of());
    }

    /** Approves a run held at deployment environments you review: GitHub's "Review deployments", approved. */
    public JsonNode approveDeployment(String repo, long runId, List<Long> environments) {
        return write("POST", "/repos/" + repo + "/actions/runs/" + runId + "/pending_deployments",
                Map.of("environment_ids", environments, "state", "approved", "comment", ""));
    }

    JsonNode write(String method, String path, Map<String, Object> body) {
        JsonNode json = mapper.valueToTree(body);
        if (!allowedWrite(method, path, json, login.get())) {
            throw new WriteFailed(0, null, "The GitHub elf only posts reviews, comments, new issues and PRs, closes items "
                    + "and merges PRs; refused " + method + " " + path);
        }
        Answer r;
        try {
            r = transport.rest(method, path, json.toString());
        } catch (WriteFailed e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WriteFailed(0, null, e.getMessage());
        }
        LOG.infof("GitHub %s %s as %s: %d", method, path, login.get(), r.status());
        String responseBody = r.body() == null ? "" : r.body();
        if (r.status() < 200 || r.status() >= 300) {
            throw new WriteFailed(r.status(), responseBody, "HTTP " + r.status());
        }
        try {
            return responseBody.isBlank() ? mapper.createObjectNode() : mapper.readTree(responseBody);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private JsonNode graphql(ObjectNode request) {
        try {
            return transport.graphql(request);
        } catch (WriteFailed e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WriteFailed(0, null, e.getMessage());
        }
    }

    /** GraphQL errors as a refusal, with GitHub's message as the body ({"message": ...}). */
    private void refused(JsonNode done) {
        if (done.path("errors").isArray() && !done.path("errors").isEmpty()) {
            String why = done.path("errors").path(0).path("message").asText("GitHub refused it");
            throw new WriteFailed(422, mapper.createObjectNode().put("message", why).toString(), why);
        }
    }

    /**
     * The only writes the elf makes, as the runner allowed them for the desk: reviews, comments, new issues,
     * close/reopen, assign yourself, merges pinned to a head, CI re-runs and approvals (fork runs and deployment
     * environments), PRs from your fork and syncing that fork.
     */
    static boolean allowedWrite(String method, String path, JsonNode body, String self) {
        return switch (method) {
            case "POST" -> REVIEW.matcher(path).matches() || COMMENT.matcher(path).matches()
                    // A new issue: a title and a body, nothing else (no labels, assignees or milestones).
                    || (ISSUES.matcher(path).matches() && body.size() == 2 && body.path("title").isTextual()
                            && !body.path("title").asText().isBlank() && body.path("body").isTextual())
                    || ((RERUN.matcher(path).matches() || APPROVE.matcher(path).matches()) && body.isEmpty())
                    // CI held at deployment environments you review: approve only (never reject), no comment.
                    || (PENDING_DEPLOYMENTS.matcher(path).matches() && body.size() == 3
                            && body.path("state").asText().equals("approved") && body.path("comment").asText("-").isEmpty()
                            && body.path("environment_ids").isArray() && body.path("environment_ids").size() >= 1
                            && body.path("environment_ids").size() <= 3
                            && java.util.stream.StreamSupport.stream(body.path("environment_ids").spliterator(), false)
                                    .allMatch(JsonNode::isIntegralNumber))
                    // A PR from your own fork's branch: title, body, head, base and draft (true, or false to open it
                    // ready for review), nothing else. You read the change in the desk before you click.
                    || (PULLS.matcher(path).matches() && body.size() == 5 && body.path("draft").isBoolean()
                            && body.path("title").isTextual()
                            && !body.path("title").asText().isBlank() && body.path("body").isTextual()
                            && BRANCH.matcher(body.path("base").asText()).matches()
                            && self != null && body.path("head").asText().startsWith(self + ":")
                            && BRANCH.matcher(body.path("head").asText().substring(self.length() + 1)).matches())
                    // Bring your own fork's branch up to date with its upstream (so a push sends only the new commit).
                    || (MERGE_UPSTREAM.matcher(path).matches() && self != null
                            && path.split("/")[2].equalsIgnoreCase(self) && body.size() == 1
                            && BRANCH.matcher(body.path("branch").asText()).matches())
                    // Ask people (not teams, not yourself) to review a PR again.
                    || (REVIEWERS.matcher(path).matches() && body.size() == 1 && body.path("reviewers").isArray()
                            && body.path("reviewers").size() >= 1 && body.path("reviewers").size() <= 10
                            && java.util.stream.StreamSupport.stream(body.path("reviewers").spliterator(), false)
                                    .allMatch(r -> r.asText().matches("[A-Za-z0-9-]{1,39}") && !r.asText().equalsIgnoreCase(self)))
                    // Assign yourself, and only yourself.
                    || (ASSIGNEES.matcher(path).matches() && body.size() == 1 && body.path("assignees").size() == 1
                            && self != null && self.equalsIgnoreCase(body.path("assignees").path(0).asText()));
            case "PATCH" -> ISSUE.matcher(path).matches() && body.size() == 1
                    && List.of("closed", "open").contains(body.path("state").asText());
            // A merge must be pinned to a head commit, so new pushes can't slip in.
            case "PUT" -> MERGE.matcher(path).matches() && body.size() == 2
                    && List.of("merge", "squash", "rebase").contains(body.path("merge_method").asText())
                    && SHA.matcher(body.path("sha").asText()).matches();
            default -> false;
        };
    }

    /** The real GitHub: api.github.com with the token, never redirected, never logged. */
    static Transport http(Supplier<String> token) {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
        return new Transport() {
            @Override
            public Answer rest(String method, String path, String body) {
                try {
                    HttpResponse<String> r = OneAtATime.send(http, HttpRequest.newBuilder(URI.create("https://api.github.com" + path))
                            .timeout(Duration.ofSeconds(45))
                            .header("Authorization", "Bearer " + token.get())
                            .header("Accept", "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2022-11-28")
                            .header("User-Agent", "github-elf")
                            .header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString(body))
                            .build(), HttpResponse.BodyHandlers.ofString());
                    return new Answer(r.statusCode(), r.body());
                } catch (IOException e) {
                    throw new WriteFailed(0, null, "Couldn't reach GitHub: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new WriteFailed(0, null, "Interrupted");
                }
            }

            @Override
            public JsonNode graphql(ObjectNode request) {
                try {
                    HttpResponse<String> r = OneAtATime.send(http, HttpRequest.newBuilder(URI.create("https://api.github.com/graphql"))
                            .timeout(Duration.ofSeconds(60))
                            .header("Authorization", "Bearer " + token.get()).header("User-Agent", "github-elf")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() != 200) throw new WriteFailed(r.statusCode(), r.body(), "GitHub GraphQL HTTP " + r.statusCode());
                    return Json.MAPPER.readTree(r.body());
                } catch (IOException e) {
                    throw new WriteFailed(0, null, "Couldn't reach GitHub: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new WriteFailed(0, null, "Interrupted");
                }
            }
        };
    }
}
