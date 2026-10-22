package io.github.houseelves.github.github;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.houseelves.github.elf.Log;

/**
 * Read-only GitHub queries for the board, each made with the caller's token.
 * Search runs through GraphQL so one request returns everything a row needs
 * (CI rollup, review decision, mergeability) for up to 100 items.
 */
public class GitHub {

    private static final Log LOG = Log.of(GitHub.class);

    /** GitHub search never returns more than 1000 results for one query. */
    public static final int SEARCH_CAP = 1000;
    /** Never fetch more pages than this for one search (100 per page, matching GitHub's own cap). */
    static final int MAX_PAGES = 10;

    /** The fields {@link #toItem} reads, for search results and single items alike. */
    private static final String ITEM_FIELDS = """
            __typename
            ... on Issue {
              number title url createdAt updatedAt state
              repository { nameWithOwner }
              author { login }
              assignees(first: 10) { nodes { login } }
              comments { totalCount }
            }
            ... on PullRequest {
              number title url createdAt updatedAt state isDraft headRefName headRefOid reviewDecision mergeable
              repository { nameWithOwner viewerPermission }
              commitCount: commits { totalCount }
              author { login }
              assignees(first: 10) { nodes { login } }
              comments { totalCount }
              commits(last: 1) { nodes { commit { statusCheckRollup { state contexts(first: 1) { checkRunCountsByState { state count } statusContextCountsByState { state count } } } } } }
              latestOpinionatedReviews(first: 20, writersOnly: true) { nodes { state } }
            }
            """;

    private static final String SEARCH = """
            query($q: String!, $after: String) {
              search(query: $q, type: ISSUE, first: 100, after: $after) {
                issueCount
                pageInfo { hasNextPage endCursor }
                nodes {
            """ + ITEM_FIELDS + """
                }
              }
            }
            """;

    private static final String ITEM = """
            query($owner: String!, $name: String!, $number: Int!) {
              repository(owner: $owner, name: $name) {
                issueOrPullRequest(number: $number) {
            """ + ITEM_FIELDS + """
                }
              }
            }
            """;

    private static final String VIEWER_ORGS = """
            query($after: String) {
              viewer { organizations(first: 100, after: $after) { pageInfo { hasNextPage endCursor } nodes { login } } }
            }
            """;

    private static final String CONTEXT = """
            query($owner: String!, $name: String!, $number: Int!) {
              repository(owner: $owner, name: $name) {
                issueOrPullRequest(number: $number) {
                  __typename
                  ... on Issue {
                    title url body authorAssociation author { login }
                    comments(last: 30) { nodes { author { login } authorAssociation body createdAt } }
                  }
                  ... on PullRequest {
                    title url body authorAssociation author { login } baseRefName headRefName
                    comments(last: 30) { nodes { author { login } authorAssociation body createdAt } }
                    reviews(last: 15) { nodes { author { login } authorAssociation body state createdAt } }
                  }
                }
              }
            }
            """;

    private static final String PULL_STATE = """
            query($owner: String!, $name: String!, $number: Int!) {
              repository(owner: $owner, name: $name) {
                mergeCommitAllowed squashMergeAllowed rebaseMergeAllowed viewerDefaultMergeMethod
                pullRequest(number: $number) {
                  title url state isDraft headRefOid mergeable mergeStateStatus reviewDecision
                  commits(last: 1) { nodes { commit { statusCheckRollup { state contexts(first: 1) { checkRunCountsByState { state count } statusContextCountsByState { state count } } } status { state } checkSuites(first: 30) { nodes { conclusion workflowRun { databaseId workflow { databaseId } pendingDeploymentRequests(first: 3) { nodes { currentUserCanApprove environment { databaseId } } } } } } } } }
                  latestOpinionatedReviews(first: 20, writersOnly: true) { nodes { state } }
                }
              }
            }
            """;

    private static final String CI = """
            query($owner: String!, $name: String!, $number: Int!) {
              viewer { login }
              repository(owner: $owner, name: $name) {
                viewerPermission
                pullRequest(number: $number) {
                  headRefOid headRefName isCrossRepository maintainerCanModify author { login }
                  headRepository { nameWithOwner owner { login } }
                  commits(last: 1) { nodes { commit {
                    statusCheckRollup { state contexts(first: 1) { checkRunCountsByState { state count } statusContextCountsByState { state count } } }
                    status { state contexts { context state targetUrl } }
                    checkSuites(first: 50) { nodes {
                      app { slug }
                      conclusion
                      workflowRun { databaseId workflow { databaseId } pendingDeploymentRequests(first: 3) { nodes { currentUserCanApprove environment { databaseId } } } }
                      checkRuns(first: 50, filterBy: { checkType: LATEST,
                          conclusions: [FAILURE, TIMED_OUT, CANCELLED, STARTUP_FAILURE, ACTION_REQUIRED] }) {
                        nodes { name conclusion databaseId detailsUrl }
                      }
                    } }
                  } } }
                }
              }
            }
            """;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final GitHubApi api;

    public GitHub(GitHubApi api) {
        this.api = api;
    }

    /** Result of a search: the items and GitHub's total, which can exceed what was fetched. */
    public record SearchResult(List<Item> items, int total) {
        public boolean truncated() {
            return total > items.size();
        }
    }

    /** Runs an issue/PR search, following pages up to {@code max} items. */
    public SearchResult search(String token, String query, int max) {
        List<Item> items = new ArrayList<>();
        String after = null;
        int total = 0;
        int pages = 0;
        do {
            Map<String, Object> vars = new HashMap<>();
            vars.put("q", query);
            vars.put("after", after);
            JsonNode search = call(token, SEARCH, vars).path("search");
            total = search.path("issueCount").asInt();
            for (JsonNode n : search.path("nodes")) {
                Item item = toItem(n);
                if (item != null) items.add(item);
            }
            JsonNode page = search.path("pageInfo");
            String next = page.path("hasNextPage").asBoolean() ? page.path("endCursor").asText(null) : null;
            // Stop on an empty page or a cursor that doesn't move: GitHub search can claim more pages than it has,
            // and following that would loop forever.
            if (search.path("nodes").isEmpty() || (next != null && next.equals(after))) next = null;
            after = next;
        } while (after != null && ++pages < MAX_PAGES && items.size() < Math.min(max, SEARCH_CAP));
        return new SearchResult(items, total);
    }

    /** One issue or PR as the board shows it, read now; null when it's closed, merged or gone. */
    public Item item(String token, String repo, int number) {
        String[] parts = repo.split("/", 2);
        Map<String, Object> vars = new HashMap<>();
        vars.put("owner", parts[0]);
        vars.put("name", parts[1]);
        vars.put("number", number);
        JsonNode n = call(token, ITEM, vars).path("repository").path("issueOrPullRequest");
        return n.isObject() && n.path("state").asText().equals("OPEN") ? toItem(n) : null;
    }

    /** Logins of the orgs the user belongs to (needs read:org). */
    public List<String> viewerOrgs(String token) {
        List<String> orgs = new ArrayList<>();
        String after = null;
        do {
            Map<String, Object> vars = new HashMap<>();
            vars.put("after", after);
            JsonNode conn = call(token, VIEWER_ORGS, vars).path("viewer").path("organizations");
            conn.path("nodes").forEach(n -> orgs.add(n.path("login").asText()));
            JsonNode page = conn.path("pageInfo");
            after = page.path("hasNextPage").asBoolean() ? page.path("endCursor").asText(null) : null;
        } while (after != null);
        return orgs;
    }

    /** How many commits a PR has. */
    public int commitCount(String token, String repo, int number) {
        String[] parts = repo.split("/", 2);
        Map<String, Object> vars = new HashMap<>();
        vars.put("owner", parts[0]);
        vars.put("name", parts[1]);
        vars.put("number", number);
        return call(token, """
                query($owner: String!, $name: String!, $number: Int!) {
                  repository(owner: $owner, name: $name) { pullRequest(number: $number) { commits { totalCount } } }
                }""", vars).path("repository").path("pullRequest").path("commits").path("totalCount").asInt();
    }

    /** {@code fork} exists and is a fork of {@code parent}. */
    public boolean isForkOf(String token, String fork, String parent) {
        String[] parts = fork.split("/", 2);
        Map<String, Object> vars = new HashMap<>();
        vars.put("owner", parts[0]);
        vars.put("name", parts[1]);
        JsonNode r = call(token, """
                query($owner: String!, $name: String!) { repository(owner: $owner, name: $name) { isFork parent { nameWithOwner } } }""",
                vars).path("repository");
        return r.path("isFork").asBoolean() && parent.equalsIgnoreCase(r.path("parent").path("nameWithOwner").asText());
    }

    /** The repo exists and is public (the desk only writes to public repos). */
    public boolean isPublic(String token, String repo) {
        String[] parts = repo.split("/", 2);
        Map<String, Object> vars = new HashMap<>();
        vars.put("owner", parts[0]);
        vars.put("name", parts[1]);
        JsonNode r = call(token, """
                query($owner: String!, $name: String!) { repository(owner: $owner, name: $name) { isPrivate } }""", vars)
                .path("repository");
        return r.isObject() && !r.path("isPrivate").asBoolean(true);
    }

    /**
     * What an issue or PR is about, for the thread page's recap.
     *
     * @param summary the opening of its description in plain text (see {@link #summary}), or "" when it has none
     * @param closes  PRs only: the issues it fixes or closes, as #123 (same repo) or owner/repo#123
     * @param files   PRs only: how many files it changes, or null
     */
    public record About(String summary, List<String> closes, Integer files, Integer additions, Integer deletions) {
    }

    public About about(String token, String repo, int number) {
        String[] parts = repo.split("/", 2);
        Map<String, Object> vars = new HashMap<>();
        vars.put("owner", parts[0]);
        vars.put("name", parts[1]);
        vars.put("number", number);
        JsonNode n = call(token, """
                query($owner: String!, $name: String!, $number: Int!) {
                  repository(owner: $owner, name: $name) {
                    issueOrPullRequest(number: $number) {
                      ... on Issue { body }
                      ... on PullRequest {
                        body changedFiles additions deletions
                        closingIssuesReferences(first: 5) { nodes { number repository { nameWithOwner } } }
                      }
                    }
                  }
                }""", vars).path("repository").path("issueOrPullRequest");
        if (!n.isObject()) throw new GitHubException(repo + "#" + number + " was not found");
        List<String> closes = new ArrayList<>();
        for (JsonNode c : n.path("closingIssuesReferences").path("nodes")) {
            String in = c.path("repository").path("nameWithOwner").asText(repo);
            closes.add((in.equalsIgnoreCase(repo) ? "" : in) + "#" + c.path("number").asInt());
        }
        boolean pr = n.has("changedFiles");
        return new About(summary(n.path("body").asText("")), List.copyOf(closes),
                pr ? n.path("changedFiles").asInt() : null,
                pr ? n.path("additions").asInt() : null,
                pr ? n.path("deletions").asInt() : null);
    }

    private static final Pattern NOT_PROSE = Pattern.compile(
            "(?i)\\s*(#{1,6}(\\s.*)?|([-*]\\s*)?\\[[ x]\\].*|([-*]\\s*)?(fix(es|ed)?|close[sd]?|resolve[sd]?|relate[sd]?( to)?|see( also)?|part of):?\\s+\\S*#\\d+.*"
                    + "|!\\[.*|\\|.*|>.*"
                    + "|[-*_]{3,}|\\*\\*[^*]+\\*\\*:?|_no response_|\\s*)");

    /**
     * The first real paragraph of a description, as plain text of at most about 300 characters. Skips what PR and
     * issue templates leave behind: comments, headings, checklists, "Fixes #1" lines, images, tables, quotes and
     * code.
     */
    static String summary(String body) {
        String text = body.replace("\r", "")
                .replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("(?s)```.*?(```|$)", "\n\n")
                .replaceAll("(?is)<details>.*?</details>", "\n\n")
                .replaceAll("(?i)</?(a|b|i|p|br|hr|em|strong|code|kbd|sub|sup|div|span|img|summary|h[1-6]|ul|ol|li|table|tr|td|th|picture|source|video)\\b[^>]*>", "");
        for (String para : text.split("\\n\\s*\\n")) {
            List<String> lines = new ArrayList<>();
            for (String line : para.split("\\n")) {
                if (NOT_PROSE.matcher(line).matches()) continue;
                lines.add(line.strip().replaceFirst("^([-*+]|\\d+[.)])\\s+", ""));
            }
            String plain = String.join(lines.size() > 1 && para.strip().matches("(?s)([-*+]|\\d+[.)])\\s.*") ? "; " : " ", lines)
                    .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                    .replaceAll("[*_`]{1,3}([^*_`]+)[*_`]{1,3}", "$1")
                    .replaceAll("\\s+", " ").strip();
            if (plain.chars().filter(Character::isLetter).count() < 15) continue;
            return shorten(plain, 300);
        }
        return "";
    }

    private static String shorten(String s, int max) {
        if (s.length() <= max) return s;
        int end = -1;
        for (int i = max / 2; i < max; i++) {
            if (".!?".indexOf(s.charAt(i)) >= 0 && (i + 1 == s.length() || s.charAt(i + 1) == ' ')) end = i + 1;
        }
        return end > 0 ? s.substring(0, end) : s.substring(0, s.lastIndexOf(' ', max)) + "…";
    }

    /** Title, body, comments and (for PRs) reviews and branches of one issue or PR. */
    public ItemContext context(String token, String repo, int number) {
        String[] parts = repo.split("/", 2);
        Map<String, Object> vars = new HashMap<>();
        vars.put("owner", parts[0]);
        vars.put("name", parts[1]);
        vars.put("number", number);
        JsonNode n = call(token, CONTEXT, vars).path("repository").path("issueOrPullRequest");
        if (!n.isObject()) throw new GitHubException(repo + "#" + number + " was not found");
        boolean pr = n.path("__typename").asText().equals("PullRequest");
        List<ItemContext.Comment> comments = new ArrayList<>();
        for (JsonNode c : n.path("comments").path("nodes")) comments.add(comment(c, null));
        for (JsonNode r : n.path("reviews").path("nodes")) comments.add(comment(r, r.path("state").asText(null)));
        comments.sort(Comparator.comparing(ItemContext.Comment::createdAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return new ItemContext(repo, number, pr ? Item.Kind.PR : Item.Kind.ISSUE,
                n.path("title").asText(), n.path("url").asText(),
                n.path("author").path("login").asText(""), n.path("authorAssociation").asText("NONE"),
                n.path("body").asText(""),
                pr ? n.path("baseRefName").asText(null) : null,
                pr ? n.path("headRefName").asText(null) : null,
                List.copyOf(comments));
    }

    /**
     * What merge on green needs to know about a PR right now.
     *
     * @param state          OPEN, CLOSED or MERGED
     * @param ci             SUCCESS, FAILURE, ERROR, PENDING, EXPECTED, or null when no checks ran
     * @param mergeable      MERGEABLE, CONFLICTING or UNKNOWN (GitHub is still computing)
     * @param mergeState     GitHub's mergeStateStatus: CLEAN, BLOCKED, BEHIND, DIRTY, UNSTABLE, HAS_HOOKS, DRAFT, UNKNOWN
     * @param methods        merge methods the repo allows, in GitHub's order: merge, squash, rebase
     * @param defaultMethod  the one you used last in this repo (GitHub's viewerDefaultMergeMethod), lower case
     * @param approved       see {@link Item#isApproved}
     */
    public record PullState(String title, String url, String state, boolean draft, String headSha, String ci,
            String mergeable, String mergeState, String reviewDecision, List<String> methods, String defaultMethod,
            boolean approved) {
    }

    public PullState pullState(String token, String repo, int number) {
        String[] parts = repo.split("/", 2);
        Map<String, Object> vars = new HashMap<>();
        vars.put("owner", parts[0]);
        vars.put("name", parts[1]);
        vars.put("number", number);
        JsonNode r = call(token, PULL_STATE, vars).path("repository");
        JsonNode pr = r.path("pullRequest");
        if (!pr.isObject()) throw new GitHubException(repo + "#" + number + " is not a pull request");
        List<String> methods = new ArrayList<>();
        if (r.path("mergeCommitAllowed").asBoolean()) methods.add("merge");
        if (r.path("squashMergeAllowed").asBoolean()) methods.add("squash");
        if (r.path("rebaseMergeAllowed").asBoolean()) methods.add("rebase");
        String preferred = r.path("viewerDefaultMergeMethod").asText("MERGE").toLowerCase(java.util.Locale.ROOT);
        JsonNode prCommit = pr.path("commits").path("nodes").path(0).path("commit");
        return new PullState(pr.path("title").asText(), pr.path("url").asText(), pr.path("state").asText(),
                pr.path("isDraft").asBoolean(), pr.path("headRefOid").asText(),
                awaitingApproval(prCommit).isEmpty() ? commitCi(prCommit) : AWAITING_APPROVAL,
                pr.path("mergeable").asText("UNKNOWN"), pr.path("mergeStateStatus").asText("UNKNOWN"),
                textOrNull(pr.path("reviewDecision")), List.copyOf(methods),
                methods.contains(preferred) ? preferred : methods.isEmpty() ? "merge" : methods.get(0),
                Item.isApproved(textOrNull(pr.path("reviewDecision")), writerReviews(pr)));
    }

    /**
     * One failed check on a PR's head.
     *
     * @param jobId GitHub Actions job id (log available), or null for other CI
     * @param runId the Actions workflow run it belongs to (can be re-run), or null
     */
    public record FailedCheck(String name, String conclusion, String url, Long jobId, Long runId) {
    }

    /**
     * Where CI stands on a PR, and whether you can push a fix to it.
     *
     * @param headRepo  where the PR's branch lives (a fork, for most outside contributions)
     * @param canPush   you can push to that branch: same repo with write access, or a fork that allows maintainer edits
     */
    /**
     * @param canRerun you have write access to the base repo, which GitHub needs to re-run (or approve) workflows
     * @param own      it's your PR: you can close and reopen it, which runs PR workflows again
     */
    public record CiReport(String headSha, String ci, List<FailedCheck> failed, List<Long> rerunnableRuns,
            String headRepo, String headRef, boolean canPush, List<Approval> awaitingApproval, boolean canRerun,
            boolean own) {

        public CiReport(String headSha, String ci, List<FailedCheck> failed, List<Long> rerunnableRuns,
                String headRepo, String headRef, boolean canPush) {
            this(headSha, ci, failed, rerunnableRuns, headRepo, headRef, canPush, List.of(), true, false);
        }

        public CiReport(String headSha, String ci, List<FailedCheck> failed, List<Long> rerunnableRuns,
                String headRepo, String headRef, boolean canPush, List<Approval> awaitingApproval) {
            this(headSha, ci, failed, rerunnableRuns, headRepo, headRef, canPush, awaitingApproval, true, false);
        }
    }

    public CiReport ci(String token, String repo, int number) {
        String[] parts = repo.split("/", 2);
        Map<String, Object> vars = new HashMap<>();
        vars.put("owner", parts[0]);
        vars.put("name", parts[1]);
        vars.put("number", number);
        JsonNode data = call(token, CI, vars);
        JsonNode r = data.path("repository");
        JsonNode pr = r.path("pullRequest");
        if (!pr.isObject()) throw new GitHubException(repo + "#" + number + " is not a pull request");
        JsonNode commit = pr.path("commits").path("nodes").path(0).path("commit");
        List<FailedCheck> failed = new ArrayList<>();
        LinkedHashSet<Long> runs = new LinkedHashSet<>();
        Set<Long> replaced = replacedRuns(commit);
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            boolean actions = suite.path("app").path("slug").asText().equals("github-actions");
            Long runId = suite.path("workflowRun").path("databaseId").isNumber()
                    ? suite.path("workflowRun").path("databaseId").asLong() : null;
            if (runId != null && replaced.contains(runId)) continue;
            for (JsonNode run : suite.path("checkRuns").path("nodes")) {
                failed.add(new FailedCheck(run.path("name").asText(), run.path("conclusion").asText(),
                        run.path("detailsUrl").asText(null), actions ? run.path("databaseId").asLong() : null,
                        actions ? runId : null));
                if (actions && runId != null) runs.add(runId);
            }
        }
        for (JsonNode ctx : commit.path("status").path("contexts")) {
            String state = ctx.path("state").asText();
            if (state.equals("FAILURE") || state.equals("ERROR")) {
                failed.add(new FailedCheck(ctx.path("context").asText(), state, ctx.path("targetUrl").asText(null),
                        null, null));
            }
        }
        String perm = r.path("viewerPermission").asText("");
        boolean write = List.of("WRITE", "MAINTAIN", "ADMIN").contains(perm);
        // A fork's branch: yours, or one whose author allows edits from maintainers. Same repo: your write access.
        boolean ownFork = pr.path("headRepository").path("owner").path("login").asText()
                .equalsIgnoreCase(data.path("viewer").path("login").asText("-"));
        boolean canPush = pr.path("isCrossRepository").asBoolean()
                ? ownFork || pr.path("maintainerCanModify").asBoolean()
                : write;
        List<Approval> waiting = awaitingApproval(commit);
        return new CiReport(pr.path("headRefOid").asText(),
                waiting.isEmpty() ? commitCi(commit) : AWAITING_APPROVAL,
                List.copyOf(failed), List.copyOf(runs),
                pr.path("headRepository").path("nameWithOwner").asText(repo), pr.path("headRefName").asText(),
                canPush && pr.path("headRepository").isObject(), waiting, write,
                pr.path("author").path("login").asText("").equalsIgnoreCase(data.path("viewer").path("login").asText("-")));
    }

    /** A PR's change as a unified diff, as GitHub's "Files changed" shows it. {@code cut}: longer than asked for. */
    public record Diff(String text, boolean cut) {
    }

    /** A PR's diff, at most {@code maxChars}; null when GitHub won't give one (too large, or no such PR). */
    public Diff prDiff(String token, String repo, int number, int maxChars) {
        try {
            var r = OneAtATime.send(HTTP, HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo + "/pulls/" + number))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github.diff")
                    .timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                LOG.debugf("No diff for %s#%d: HTTP %d", repo, number, r.statusCode());
                return null;
            }
            String text = r.body();
            return text.length() <= maxChars ? new Diff(text, false) : new Diff(text.substring(0, maxChars), true);
        } catch (Exception e) {
            LOG.warnf("Couldn't read the diff of %s#%d: %s", repo, number, e.getMessage());
            return null;
        }
    }

    /**
     * The last {@code maxChars} of a GitHub Actions job's log. GitHub answers with a redirect to blob storage,
     * which must be fetched without the token, so this follows it by hand.
     */
    public String jobLogTail(String token, String repo, long jobId, int maxChars) {
        try {
            var first = OneAtATime.send(HTTP, HttpRequest.newBuilder(URI.create(
                    "https://api.github.com/repos/" + repo + "/actions/jobs/" + jobId + "/logs"))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            String location = first.headers().firstValue("location").orElse(null);
            if (location == null) return null;
            var log = OneAtATime.send(HTTP, HttpRequest.newBuilder(URI.create(location))
                    .timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (log.statusCode() != 200) return null;
            String text = log.body();
            return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
        } catch (Exception e) {
            LOG.warnf("Couldn't read the log of job %d in %s: %s", jobId, repo, e.getMessage());
            return null;
        }
    }

    private static ItemContext.Comment comment(JsonNode c, String state) {
        return new ItemContext.Comment(c.path("author").path("login").asText(""),
                c.path("authorAssociation").asText("NONE"), c.path("body").asText(""), instant(c.path("createdAt")),
                state);
    }

    private JsonNode call(String token, String query, Map<String, Object> variables) {
        JsonNode res = withRetries(() -> api.graphql("Bearer " + token, Map.of("query", query, "variables", variables)));
        JsonNode errors = res.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            // Partial data is normal for search (e.g. a repo the token can't see); keep it, log the rest.
            LOG.warnf("GitHub GraphQL returned errors: %s", errors);
            if (res.path("data").isMissingNode() || res.path("data").isNull()) {
                throw new GitHubException(errors.path(0).path("message").asText("GitHub GraphQL error"));
            }
        }
        return res.path("data");
    }

    /** GitHub's GraphQL answers 502/503/504 now and then on heavy queries; try twice more before giving up. */
    static JsonNode withRetries(Supplier<JsonNode> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (GitHubApi.HttpFailed e) {
                int status = e.status;
                if (attempt >= 3 || (status != 502 && status != 503 && status != 504)) throw e;
                LOG.infof("GitHub answered %d, retrying (%d of 2)", status, attempt);
                try {
                    Thread.sleep(attempt * 1500L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** WRITE, MAINTAIN or ADMIN; null when GitHub didn't say (issues don't ask). */
    static Boolean canWrite(JsonNode permission) {
        if (!permission.isTextual()) return null;
        return List.of("WRITE", "MAINTAIN", "ADMIN").contains(permission.asText());
    }

    static Item toItem(JsonNode n) {
        String type = n.path("__typename").asText();
        if (!type.equals("Issue") && !type.equals("PullRequest")) return null;
        boolean pr = type.equals("PullRequest");
        String repo = n.path("repository").path("nameWithOwner").asText();
        int number = n.path("number").asInt();
        List<String> assignees = new ArrayList<>();
        n.path("assignees").path("nodes").forEach(a -> assignees.add(a.path("login").asText()));
        String ci = null;
        if (pr) {
            ci = ciState(n.path("commits").path("nodes").path(0).path("commit").path("statusCheckRollup"));
        }
        return new Item(
                Item.key(repo, number), repo, number, pr ? Item.Kind.PR : Item.Kind.ISSUE,
                n.path("title").asText(), n.path("url").asText(),
                n.path("author").path("login").asText(""),
                List.copyOf(assignees),
                instant(n.path("createdAt")), instant(n.path("updatedAt")),
                n.path("comments").path("totalCount").asInt(),
                pr ? n.path("isDraft").asBoolean() : null,
                pr ? n.path("headRefName").asText(null) : null,
                pr ? n.path("headRefOid").asText(null) : null,
                ci,
                pr ? textOrNull(n.path("reviewDecision")) : null,
                pr ? textOrNull(n.path("mergeable")) : null,
                null, // mergeStateStatus: too heavy for search, filled in by prDetails()
                pr ? Item.isApproved(textOrNull(n.path("reviewDecision")), writerReviews(n)) : null,
                canWrite(n.path("repository").path("viewerPermission")),
                pr && n.path("commitCount").path("totalCount").isInt() ? n.path("commitCount").path("totalCount").asInt() : null);
    }

    /**
     * What a PR's merge state is and whether its CI waits for approval: fields too heavy for search pages, so they're
     * read here for open PRs only, a few per query.
     */
    /**
     * @param myReviewAt     when you last reviewed this PR, or null
     * @param myReviewedSha  the head commit you reviewed, or null
     * @param feedback       on your own PRs: what reviewers said last, or null
     */
    /**
     * @param passedOnRerun every check passes once failed workflow runs that a newer run of the same workflow
     *                      replaced are left out (see {@link #passedOnRerun}), which GitHub's rollup doesn't do
     */
    public record PrDetails(String mergeState, boolean awaitingApproval, Instant myReviewAt, String myReviewedSha,
            Feedback feedback, boolean passedOnRerun) {

        public PrDetails(String mergeState, boolean awaitingApproval) {
            this(mergeState, awaitingApproval, null, null, null, false);
        }

        public PrDetails(String mergeState, boolean awaitingApproval, Instant myReviewAt, String myReviewedSha) {
            this(mergeState, awaitingApproval, myReviewAt, myReviewedSha, null, false);
        }

        public PrDetails(String mergeState, boolean awaitingApproval, Instant myReviewAt, String myReviewedSha,
                Feedback feedback) {
            this(mergeState, awaitingApproval, myReviewAt, myReviewedSha, feedback, false);
        }
    }

    /**
     * The last word from a person (not a bot) on your PR. A "Commented" review leaves GitHub's review decision at
     * "review required", so this is how the desk knows a reviewer said something.
     *
     * @param who         their login, or null when nobody but you and bots said anything
     * @param at          when
     * @param state       a review's state (COMMENTED, CHANGES_REQUESTED, APPROVED), or COMMENT for a plain comment
     * @param afterYou    it came after your last commit, comment and review: your move
     * @param openThreads review threads not resolved yet
     */
    public record Feedback(String who, Instant at, String state, boolean afterYou, int openThreads) {
    }

    public static final int DETAILS_PER_QUERY = 25;

    /**
     * An open PR linked to an issue.
     *
     * @param fixes the PR closes the issue when merged ("Fixes #123", or linked in the sidebar); false: it only
     *              mentions it
     */
    public record LinkedPr(String key, String title, String url, String author, boolean draft, boolean fixes) {
    }

    /** Open PRs that fix or mention each issue, by issue key; issues without any are left out. */
    public Map<String, List<LinkedPr>> linkedPrs(String token, List<String> issueKeys) {
        Map<String, List<LinkedPr>> out = new HashMap<>();
        for (int from = 0; from < issueKeys.size(); from += DETAILS_PER_QUERY) {
            List<String> chunk = issueKeys.subList(from, Math.min(issueKeys.size(), from + DETAILS_PER_QUERY));
            StringBuilder q = new StringBuilder("query {\n");
            for (int i = 0; i < chunk.size(); i++) {
                String key = chunk.get(i);
                if (!key.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) continue;
                String[] repo = key.substring(0, key.indexOf('#')).split("/", 2);
                q.append("  i").append(i).append(": repository(owner: \"").append(repo[0]).append("\", name: \"")
                        .append(repo[1]).append("\") { issue(number: ").append(key.substring(key.indexOf('#') + 1))
                        .append(") { closedByPullRequestsReferences(first: 5, includeClosedPrs: false) { nodes { "
                                + LINKED_PR + " } } timelineItems(itemTypes: [CROSS_REFERENCED_EVENT], last: 20) { nodes { "
                                + "... on CrossReferencedEvent { willCloseTarget source { ... on PullRequest { " + LINKED_PR
                                + " } } } } } } }\n");
            }
            q.append("}");
            JsonNode data = call(token, q.toString(), Map.of());
            for (int i = 0; i < chunk.size(); i++) {
                JsonNode issue = data.path("i" + i).path("issue");
                if (!issue.isObject()) continue;
                Map<String, LinkedPr> prs = new LinkedHashMap<>();
                for (JsonNode pr : issue.path("closedByPullRequestsReferences").path("nodes")) linked(pr, true, prs);
                for (JsonNode ev : issue.path("timelineItems").path("nodes")) {
                    linked(ev.path("source"), ev.path("willCloseTarget").asBoolean(), prs);
                }
                if (!prs.isEmpty()) out.put(chunk.get(i), List.copyOf(prs.values()));
            }
        }
        return out;
    }

    private static final String LINKED_PR = "number title url state isDraft author { login } repository { nameWithOwner }";

    /** Adds an open PR (a cross-reference can also be an issue, or a closed PR: skipped). Fixing wins over mentioning. */
    private static void linked(JsonNode pr, boolean fixes, Map<String, LinkedPr> prs) {
        if (!pr.has("number") || !"OPEN".equals(pr.path("state").asText())) return;
        String key = Item.key(pr.path("repository").path("nameWithOwner").asText(), pr.path("number").asInt());
        LinkedPr before = prs.get(key);
        prs.put(key, new LinkedPr(key, pr.path("title").asText(), pr.path("url").asText(),
                pr.path("author").path("login").asText(""), pr.path("isDraft").asBoolean(),
                fixes || (before != null && before.fixes())));
    }

    public Map<String, PrDetails> prDetails(String token, List<String> keys) {
        return prDetails(token, keys, null);
    }

    /** @param login yours, to find your latest review on each PR; null skips that */
    public Map<String, PrDetails> prDetails(String token, List<String> keys, String login) {
        boolean mine = login != null && login.matches("[A-Za-z0-9-]+");
        Map<String, PrDetails> out = new HashMap<>();
        for (int from = 0; from < keys.size(); from += DETAILS_PER_QUERY) {
            List<String> chunk = keys.subList(from, Math.min(keys.size(), from + DETAILS_PER_QUERY));
            StringBuilder q = new StringBuilder("query {\n");
            for (int i = 0; i < chunk.size(); i++) {
                String key = chunk.get(i);
                if (!key.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) continue;
                String[] repo = key.substring(0, key.indexOf('#')).split("/", 2);
                q.append("  p").append(i).append(": repository(owner: \"").append(repo[0]).append("\", name: \"")
                        .append(repo[1]).append("\") { pullRequest(number: ").append(key.substring(key.indexOf('#') + 1))
                        .append(") { mergeStateStatus commits(last: 1) { nodes { commit { "
                                + "status { state } checkSuites(first: 30) { nodes { conclusion " + WORKFLOW_RUN + " } } } } } ")
                        .append(mine ? "reviews(author: \"" + login + "\", last: 1) { nodes { submittedAt commit { oid } } } "
                                + "author { login } " + FEEDBACK : "")
                        .append("} }\n");
            }
            q.append("}");
            JsonNode data = call(token, q.toString(), Map.of());
            for (int i = 0; i < chunk.size(); i++) {
                JsonNode pr = data.path("p" + i).path("pullRequest");
                if (!pr.isObject()) continue;
                JsonNode review = pr.path("reviews").path("nodes").path(0);
                boolean myPr = mine && login.equalsIgnoreCase(pr.path("author").path("login").asText());
                JsonNode commit = pr.path("commits").path("nodes").path(0).path("commit");
                out.put(chunk.get(i), new PrDetails(textOrNull(pr.path("mergeStateStatus")),
                        !awaitingApproval(commit).isEmpty(),
                        instant(review.path("submittedAt")), textOrNull(review.path("commit").path("oid")),
                        myPr ? feedback(pr, login) : null, passedOnRerun(commit)));
            }
        }
        return out;
    }

    /** Reviews, comments and threads on a PR, for {@link #feedback}. */
    private static final String FEEDBACK = "lastCommit: commits(last: 1) { nodes { commit { committedDate } } } "
            + "allReviews: reviews(last: 30) { nodes { state submittedAt author { __typename login } } } "
            + "comments(last: 30) { nodes { createdAt author { __typename login } } } "
            + "reviewThreads(last: 100) { nodes { isResolved } } ";

    /** Who spoke last on your PR, and whether that came after you did. Null when nobody did and nothing is open. */
    static Feedback feedback(JsonNode pr, String login) {
        Instant mine = instant(pr.path("lastCommit").path("nodes").path(0).path("commit").path("committedDate"));
        String who = null, state = null;
        Instant at = null;
        List<JsonNode> said = new ArrayList<>();
        pr.path("allReviews").path("nodes").forEach(said::add);
        pr.path("comments").path("nodes").forEach(said::add);
        for (JsonNode n : said) {
            boolean review = n.has("state");
            if (review && "PENDING".equals(n.path("state").asText())) continue;
            Instant when = instant(n.path(review ? "submittedAt" : "createdAt"));
            if (when == null) continue;
            String by = n.path("author").path("login").asText("");
            if (by.equalsIgnoreCase(login)) {
                if (mine == null || when.isAfter(mine)) mine = when;
            } else if (!isBot(n.path("author")) && (at == null || when.isAfter(at))) {
                who = by;
                at = when;
                state = review ? n.path("state").asText() : "COMMENT";
            }
        }
        int open = 0;
        for (JsonNode t : pr.path("reviewThreads").path("nodes")) if (!t.path("isResolved").asBoolean()) open++;
        if (who == null && open == 0) return null;
        return new Feedback(who, at, state, at != null && (mine == null || at.isAfter(mine)), open);
    }

    static boolean isBot(JsonNode author) {
        String login = author.path("login").asText("");
        return "Bot".equals(author.path("__typename").asText()) || login.isEmpty() || login.endsWith("[bot]")
                || login.endsWith("-bot");
    }

    private static final String ACTIVITY = """
            query($owner: String!, $name: String!, $number: Int!) {
              repository(owner: $owner, name: $name) {
                pullRequest(number: $number) {
                  url headRefOid reviewDecision
                  timelineItems(last: 100, itemTypes: [PULL_REQUEST_COMMIT, PULL_REQUEST_REVIEW, ISSUE_COMMENT,
                      REVIEW_REQUESTED_EVENT, HEAD_REF_FORCE_PUSHED_EVENT, READY_FOR_REVIEW_EVENT]) {
                    nodes {
                      __typename
                      ... on PullRequestCommit { url commit { oid messageHeadline committedDate author { name user { login } } } }
                      ... on PullRequestReview { url state submittedAt body author { login } comments { totalCount } }
                      ... on IssueComment { url createdAt body author { login } }
                      ... on ReviewRequestedEvent { createdAt actor { login } requestedReviewer { ... on User { login } ... on Team { name } } }
                      ... on HeadRefForcePushedEvent { createdAt actor { login } }
                      ... on ReadyForReviewEvent { createdAt actor { login } }
                    }
                  }
                }
              }
            }
            """;

    /**
     * What happened on a PR since your last review or comment.
     *
     * @param since     when you last reviewed or commented (null: you haven't, so it's the last 100 events)
     * @param sinceWhat "your review", "your comment" or null
     */
    public record Activity(String itemKey, Instant since, String sinceWhat, List<Event> events, String reviewDecision,
            String headSha) {
    }

    /** @param kind commit, force-push, review, comment, review-request, ready */
    public record Event(String kind, String who, Instant at, String text, String url) {
    }

    private static final String REVIEWS = """
            query($owner: String!, $name: String!, $number: Int!) {
              repository(owner: $owner, name: $name) {
                pullRequest(number: $number) {
                  url author { login }
                  lastCommit: commits(last: 1) { nodes { commit { committedDate } } }
                  allReviews: reviews(last: 50) { nodes { url state body submittedAt authorAssociation author { __typename login } } }
                  comments(last: 50) { nodes { url body createdAt authorAssociation author { __typename login } } }
                  reviewThreads(last: 100) {
                    nodes {
                      isResolved isOutdated path line originalLine
                      comments(first: 50) { nodes { url body createdAt diffHunk authorAssociation author { __typename login } } }
                    }
                  }
                }
              }
            }
            """;

    /**
     * What reviewers said on a PR, in full, for reading it on the desk: review bodies and plain comments from people
     * (not bots), and every inline thread with the code it is on.
     *
     * @param you      your login
     * @param yourLast your last commit, comment or review: what came after is new
     */
    public record Reviews(String itemKey, String url, String you, Instant yourLast, List<Said> said, List<ReviewThread> threads) {

        /** Text from people outside the repo, for the runner's safety triage (as {@link ItemContext#untrustedText}). */
        public String untrustedText() {
            StringBuilder sb = new StringBuilder();
            java.util.stream.Stream.concat(said.stream(), threads.stream().flatMap(t -> t.comments().stream()))
                    .filter(s -> !ItemContext.TRUSTED.contains(s.association()) && s.body() != null && !s.body().isBlank())
                    .forEach(s -> sb.append("---\n").append(s.body()).append("\n\n"));
            return sb.toString().strip();
        }
    }

    /**
     * A review's summary or a plain comment.
     *
     * @param state       a review's state (COMMENTED, CHANGES_REQUESTED, APPROVED), or COMMENT for a plain comment
     * @param association the author's GitHub association with the repo (MEMBER, CONTRIBUTOR, NONE...)
     */
    public record Said(String who, Instant at, String state, String body, String url, boolean isNew, String association) {
    }

    /**
     * An inline thread.
     *
     * @param line     the line in the current code, or the original line when outdated
     * @param diffHunk the code the thread starts on (GitHub's hunk, ending at that line)
     * @param isNew    a reply from someone else came after your last move
     */
    public record ReviewThread(String path, Integer line, boolean resolved, boolean outdated, String diffHunk,
            List<Said> comments, boolean isNew) {
    }

    public Reviews reviews(String token, String login, String repo, int number) {
        String[] parts = repo.split("/", 2);
        Map<String, Object> vars = new HashMap<>();
        vars.put("owner", parts[0]);
        vars.put("name", parts[1]);
        vars.put("number", number);
        JsonNode pr = call(token, REVIEWS, vars).path("repository").path("pullRequest");
        if (!pr.isObject()) throw new GitHubException(repo + "#" + number + " is not a pull request");
        return reviewsFrom(Item.key(repo, number), pr, login);
    }

    static Reviews reviewsFrom(String key, JsonNode pr, String login) {
        // Your last move: your last commit, comment, review or thread reply.
        Instant mine = instant(pr.path("lastCommit").path("nodes").path(0).path("commit").path("committedDate"));
        List<JsonNode> all = new ArrayList<>();
        pr.path("allReviews").path("nodes").forEach(all::add);
        pr.path("comments").path("nodes").forEach(all::add);
        for (JsonNode t : pr.path("reviewThreads").path("nodes")) t.path("comments").path("nodes").forEach(all::add);
        for (JsonNode n : all) {
            Instant when = instant(n.path(n.has("submittedAt") ? "submittedAt" : "createdAt"));
            if (when != null && n.path("author").path("login").asText("").equalsIgnoreCase(login)
                    && (mine == null || when.isAfter(mine))) mine = when;
        }
        final Instant yourLast = mine;
        List<Said> said = new ArrayList<>();
        for (JsonNode n : pr.path("allReviews").path("nodes")) {
            String state = n.path("state").asText();
            if ("PENDING".equals(state) || isBot(n.path("author"))) continue;
            // A "Commented" review without a summary is only the wrapper of its inline comments: the threads show those.
            if ("COMMENTED".equals(state) && n.path("body").asText("").isBlank()) continue;
            said.add(said(n, "submittedAt", state, login, yourLast));
        }
        for (JsonNode n : pr.path("comments").path("nodes")) {
            if (!isBot(n.path("author"))) said.add(said(n, "createdAt", "COMMENT", login, yourLast));
        }
        said.sort(Comparator.comparing(Said::at, Comparator.nullsFirst(Comparator.naturalOrder())));
        List<ReviewThread> threads = new ArrayList<>();
        for (JsonNode t : pr.path("reviewThreads").path("nodes")) {
            List<Said> comments = new ArrayList<>();
            String hunk = null;
            for (JsonNode c : t.path("comments").path("nodes")) {
                if (hunk == null) hunk = textOrNull(c.path("diffHunk"));
                comments.add(said(c, "createdAt", "COMMENT", login, yourLast));
            }
            if (comments.isEmpty()) continue;
            boolean outdated = t.path("isOutdated").asBoolean();
            JsonNode line = t.path(outdated || t.path("line").isNull() ? "originalLine" : "line");
            threads.add(new ReviewThread(t.path("path").asText(""), line.isInt() ? line.asInt() : null,
                    t.path("isResolved").asBoolean(), outdated, hunk, comments, comments.stream().anyMatch(Said::isNew)));
        }
        // Open threads with something new first, then open ones, then resolved; each group in file order.
        threads.sort(Comparator.comparing((ReviewThread t) -> t.resolved())
                .thenComparing(t -> !t.isNew())
                .thenComparing(ReviewThread::path)
                .thenComparing(t -> t.line() == null ? 0 : t.line()));
        return new Reviews(key, pr.path("url").asText(null), login, yourLast, said, threads);
    }

    private static Said said(JsonNode n, String atField, String state, String login, Instant yourLast) {
        String who = n.path("author").path("login").asText("");
        Instant at = instant(n.path(atField));
        boolean theirs = !who.equalsIgnoreCase(login);
        return new Said(who, at, state, n.path("body").asText(""), n.path("url").asText(null),
                theirs && at != null && (yourLast == null || at.isAfter(yourLast)), n.path("authorAssociation").asText("NONE"));
    }

    public Activity activity(String token, String login, String repo, int number) {
        String[] parts = repo.split("/", 2);
        Map<String, Object> vars = new HashMap<>();
        vars.put("owner", parts[0]);
        vars.put("name", parts[1]);
        vars.put("number", number);
        JsonNode pr = call(token, ACTIVITY, vars).path("repository").path("pullRequest");
        if (!pr.isObject()) throw new GitHubException(repo + "#" + number + " is not a pull request");
        return activityFrom(Item.key(repo, number), pr, login);
    }

    static Activity activityFrom(String key, JsonNode pr, String login) {
        List<Event> all = new ArrayList<>();
        Instant since = null;
        String sinceWhat = null;
        for (JsonNode n : pr.path("timelineItems").path("nodes")) {
            Event e = switch (n.path("__typename").asText()) {
                case "PullRequestCommit" -> {
                    JsonNode c = n.path("commit");
                    String who = c.path("author").path("user").path("login").asText(c.path("author").path("name").asText(""));
                    yield new Event("commit", who, instant(c.path("committedDate")),
                            c.path("oid").asText("").substring(0, Math.min(7, c.path("oid").asText("").length())) + " "
                                    + c.path("messageHeadline").asText(), n.path("url").asText(null));
                }
                case "PullRequestReview" -> new Event("review", n.path("author").path("login").asText(""),
                        instant(n.path("submittedAt")), reviewText(n), n.path("url").asText(null));
                case "IssueComment" -> new Event("comment", n.path("author").path("login").asText(""),
                        instant(n.path("createdAt")), snippet(n.path("body").asText("")), n.path("url").asText(null));
                case "ReviewRequestedEvent" -> new Event("review-request", n.path("actor").path("login").asText(""),
                        instant(n.path("createdAt")), "requested a review from "
                                + n.path("requestedReviewer").path("login").asText(n.path("requestedReviewer").path("name").asText("someone")),
                        null);
                case "HeadRefForcePushedEvent" -> new Event("force-push", n.path("actor").path("login").asText(""),
                        instant(n.path("createdAt")), "force-pushed the branch", null);
                case "ReadyForReviewEvent" -> new Event("ready", n.path("actor").path("login").asText(""),
                        instant(n.path("createdAt")), "marked it ready for review", null);
                default -> null;
            };
            if (e == null || e.at() == null) continue;
            all.add(e);
            boolean yours = e.who().equalsIgnoreCase(login) && (e.kind().equals("review") || e.kind().equals("comment"));
            if (yours && (since == null || e.at().isAfter(since))) {
                since = e.at();
                sinceWhat = e.kind().equals("review") ? "your review" : "your comment";
            }
        }
        Instant cut = since;
        List<Event> after = all.stream()
                .filter(e -> cut == null || e.at().isAfter(cut))
                .filter(e -> !(e.who().equalsIgnoreCase(login) && !e.kind().equals("commit")))
                .sorted(Comparator.comparing(Event::at))
                .toList();
        return new Activity(key, since, sinceWhat, after, textOrNull(pr.path("reviewDecision")), pr.path("headRefOid").asText(null));
    }

    private static String reviewText(JsonNode review) {
        String state = switch (review.path("state").asText()) {
            case "APPROVED" -> "approved";
            case "CHANGES_REQUESTED" -> "requested changes";
            case "DISMISSED" -> "review dismissed";
            default -> "commented";
        };
        int inline = review.path("comments").path("totalCount").asInt();
        String body = snippet(review.path("body").asText(""));
        return state + (inline > 0 ? " (" + inline + " inline)" : "") + (body.isEmpty() ? "" : ": " + body);
    }

    /**
     * A comment's gist as plain text, without the markdown. When it starts with a heading (a bot's report, say:
     * "# Status for workflow `Quarkus CI`" then "## Failing Jobs"), the heading and the next one.
     */
    static String snippet(String body) {
        String text = body.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)```.*?(```|$)", "");
        List<String> lines = text.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("|")).toList();
        if (lines.isEmpty()) return "";
        String s;
        if (lines.get(0).startsWith("#")) {
            s = plain(lines.get(0)) + lines.stream().skip(1).filter(l -> l.startsWith("#")).findFirst()
                    .map(h -> ": " + plain(h)).orElse("");
        } else {
            s = String.join(" ", lines.stream().map(GitHub::plain).toList());
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /** One line of markdown as text: no heading marks, quotes, emphasis, code ticks, or link targets. */
    private static String plain(String line) {
        return line.replaceFirst("^#+\\s*", "").replaceFirst("^>\\s*", "")
                .replaceAll("!?\\[([^\\]]*)]\\([^)]*\\)", "$1").replaceAll("[`*_]", "").replaceAll("\\s+", " ").strip();
    }

    /**
     * New activity aimed at you on ignored items since you ignored them: a review request for you, or a comment or
     * review by someone else that @mentions you. One query for all of them. Returns item key to reason
     * ("Review requested" or "You were mentioned"), only for items with such activity.
     */
    public Map<String, String> activityForMe(String token, String login, Map<String, Instant> since) {
        if (since.isEmpty()) return Map.of();
        List<String> keys = new ArrayList<>(since.keySet());
        StringBuilder q = new StringBuilder("query {\n");
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (!key.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) continue;
            String[] repo = key.substring(0, key.indexOf('#')).split("/", 2);
            String number = key.substring(key.indexOf('#') + 1);
            String sinceArg = "timelineItems(since: \"" + since.get(key) + "\", first: 50, ";
            String comment = "... on IssueComment { body author { login } } ";
            // Issue timelines can't contain PR-only event types, so each kind gets its own selection.
            String issue = sinceArg + "itemTypes: [ISSUE_COMMENT]) { nodes { __typename " + comment + "} }";
            String pr = sinceArg + "itemTypes: [REVIEW_REQUESTED_EVENT, ISSUE_COMMENT, PULL_REQUEST_REVIEW]) { nodes { "
                    + "__typename ... on ReviewRequestedEvent { requestedReviewer { ... on User { login } } } " + comment
                    + "... on PullRequestReview { body author { login } comments(first: 30) { nodes { body } } } } }";
            q.append("  i").append(i).append(": repository(owner: \"").append(repo[0]).append("\", name: \"")
                    .append(repo[1]).append("\") { issueOrPullRequest(number: ").append(number).append(") { ")
                    .append("... on Issue { ").append(issue).append(" } ")
                    .append("... on PullRequest { ").append(pr).append(" } } }\n");
        }
        q.append("}");
        JsonNode data = call(token, q.toString(), Map.of());
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            JsonNode nodes = data.path("i" + i).path("issueOrPullRequest").path("timelineItems").path("nodes");
            String reason = reasonFor(nodes, login);
            if (reason != null) out.put(keys.get(i), reason);
        }
        return out;
    }

    static String reasonFor(JsonNode timeline, String login) {
        Pattern mention = Pattern.compile("(^|[^A-Za-z0-9-])@" + Pattern.quote(login) + "(?![A-Za-z0-9-])",
                Pattern.CASE_INSENSITIVE);
        boolean mentioned = false;
        for (JsonNode n : timeline) {
            switch (n.path("__typename").asText()) {
                case "ReviewRequestedEvent" -> {
                    if (n.path("requestedReviewer").path("login").asText().equalsIgnoreCase(login)) return "Review requested";
                }
                case "IssueComment", "PullRequestReview" -> {
                    if (n.path("author").path("login").asText().equalsIgnoreCase(login)) continue;
                    if (mention.matcher(n.path("body").asText()).find()) mentioned = true;
                    for (JsonNode c : n.path("comments").path("nodes")) {
                        if (mention.matcher(c.path("body").asText()).find()) mentioned = true;
                    }
                }
                default -> { }
            }
        }
        return mentioned ? "You were mentioned" : null;
    }

    /**
     * CI state as the desk shows it: PENDING while any check is still queued or running, otherwise GitHub's own
     * rollup (SUCCESS, FAILURE, ...). GitHub's rollup alone says FAILURE during a re-run when a summary check (like
     * Quarkus's "CI Merge Gate") still holds the previous attempt's result. Null when no checks ran.
     */
    static String ciState(JsonNode rollup) {
        if (!rollup.isObject()) return null;
        JsonNode contexts = rollup.path("contexts");
        for (JsonNode c : contexts.path("checkRunCountsByState")) {
            if (RUNNING_CHECKS.contains(c.path("state").asText()) && c.path("count").asInt() > 0) return "PENDING";
        }
        for (JsonNode c : contexts.path("statusContextCountsByState")) {
            if (c.path("state").asText().equals("PENDING") && c.path("count").asInt() > 0) return "PENDING";
        }
        return rollup.path("state").asText(null);
    }

    /** {@link #ciState} of a commit, SUCCESS when it only fails on runs a newer run replaced ({@link #passedOnRerun}). */
    static String commitCi(JsonNode commit) {
        String state = ciState(commit.path("statusCheckRollup"));
        return ("FAILURE".equals(state) || "ERROR".equals(state)) && passedOnRerun(commit) ? "SUCCESS" : state;
    }

    static final Set<String> PASSED = Set.of("SUCCESS", "NEUTRAL", "SKIPPED");

    /**
     * The workflow runs on a commit that a newer run of the same workflow replaced. Closing and reopening a PR (or a
     * new trigger) starts a second run beside the failed one, and GitHub keeps counting the failed one in its rollup.
     */
    static Set<Long> replacedRuns(JsonNode commit) {
        Map<Long, Long> newest = new HashMap<>();
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun");
            if (!run.path("databaseId").isNumber() || !run.path("workflow").path("databaseId").isNumber()) continue;
            newest.merge(run.path("workflow").path("databaseId").asLong(), run.path("databaseId").asLong(), Math::max);
        }
        Set<Long> replaced = new HashSet<>();
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun");
            if (!run.path("databaseId").isNumber() || !run.path("workflow").path("databaseId").isNumber()) continue;
            long id = run.path("databaseId").asLong();
            if (id != newest.get(run.path("workflow").path("databaseId").asLong())) replaced.add(id);
        }
        return replaced;
    }

    /**
     * Every check on the commit passed, once the runs a newer run replaced ({@link #replacedRuns}) are left out:
     * at least one was, every other check suite passed, and no commit status failed.
     */
    static boolean passedOnRerun(JsonNode commit) {
        Set<Long> replaced = replacedRuns(commit);
        if (replaced.isEmpty()) return false;
        String status = commit.path("status").path("state").asText("SUCCESS");
        if (!status.equals("SUCCESS")) return false;
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun").path("databaseId");
            if (run.isNumber() && replaced.contains(run.asLong())) continue;
            String conclusion = suite.path("conclusion").asText("");
            // A suite of an app that never ran a check has no conclusion and no runs: it doesn't count.
            if (conclusion.isEmpty() && !run.isNumber()) continue;
            if (!PASSED.contains(conclusion)) return false;
        }
        return true;
    }

    /**
     * CI state for a commit whose GitHub Actions runs wait for you to approve them: a fork PR's runs ("Approve and
     * run"), or a run held at a deployment environment you review (quarkusio/quarkus holds Dependabot PRs' CI at the
     * "dependabot" environment).
     */
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";

    /** A workflow run's databaseId and the deployment requests on it (for {@link #awaitingApproval}). */
    static final String WORKFLOW_RUN = "workflowRun { databaseId workflow { databaseId } pendingDeploymentRequests(first: 3) { nodes { "
            + "currentUserCanApprove environment { databaseId } } } }";

    /**
     * A run waiting for approval.
     *
     * @param environments the deployment environments it waits at, which you can approve; empty for a fork PR's run
     */
    public record Approval(long run, List<Long> environments) {
        public Approval(long run) {
            this(run, List.of());
        }
    }

    /** Workflow runs on this commit waiting for you to approve them. */
    static List<Approval> awaitingApproval(JsonNode commit) {
        List<Approval> runs = new ArrayList<>();
        for (JsonNode suite : commit.path("checkSuites").path("nodes")) {
            JsonNode run = suite.path("workflowRun").path("databaseId");
            if (!run.isNumber()) continue;
            if (suite.path("conclusion").asText().equals("ACTION_REQUIRED")) {
                runs.add(new Approval(run.asLong()));
                continue;
            }
            List<Long> environments = new ArrayList<>();
            for (JsonNode d : suite.path("workflowRun").path("pendingDeploymentRequests").path("nodes")) {
                JsonNode env = d.path("environment").path("databaseId");
                if (d.path("currentUserCanApprove").asBoolean() && env.isNumber()) environments.add(env.asLong());
            }
            if (!environments.isEmpty()) runs.add(new Approval(run.asLong(), List.copyOf(environments)));
        }
        return runs;
    }

    static final Set<String> RUNNING_CHECKS = Set.of("QUEUED", "IN_PROGRESS", "PENDING", "WAITING", "REQUESTED");

    /** The latest opinionated review state of each reviewer with write access. */
    static List<String> writerReviews(JsonNode pr) {
        List<String> states = new ArrayList<>();
        pr.path("latestOpinionatedReviews").path("nodes").forEach(r -> states.add(r.path("state").asText()));
        return states;
    }

    private static Instant instant(JsonNode n) {
        return n.isTextual() ? Instant.parse(n.asText()) : null;
    }

    private static String textOrNull(JsonNode n) {
        return n.isTextual() ? n.asText() : null;
    }

    public static class GitHubException extends RuntimeException {
        public GitHubException(String message) {
            super(message);
        }
    }
}
