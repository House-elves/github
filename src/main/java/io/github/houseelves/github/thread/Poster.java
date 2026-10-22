package io.github.houseelves.github.thread;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.github.agent.AgentOutput;
import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.elf.Live;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.GitHubWriter;

/**
 * The only code that posts drafts to GitHub. Posting takes two calls: {@link #preview} shows exactly what will be
 * sent and returns a one-time confirmation, and {@link #post} sends it, as the elf's account, only if that
 * confirmation matches the draft as it was previewed.
 *
 * <p>
 * Commits are in the runner's worktree, so for a push (or a new PR, which starts with one) {@link #post} answers
 * with what the runner should push, and leaves a mark on the draft; once the runner has pushed, the tab calls
 * {@link #postDone}, which finishes it (the posted mark, and for a new PR, opening it).
 */
public class Poster {

    static final Duration CONFIRM_FOR = Duration.ofMinutes(10);
    /** How long a push handed to the runner waits for {@link #postDone}. */
    static final Duration PUSH_FOR = Duration.ofMinutes(15);

    final GitHubWriter writes;
    final GitHub github;
    final ThreadStore store;
    final Live live;
    final ObjectMapper mapper = Json.MAPPER;
    final Clock clock;

    public Poster(GitHubWriter writes, GitHub github, ThreadStore store, Live live) {
        this(writes, github, store, live, Clock.systemUTC());
    }

    Poster(GitHubWriter writes, GitHub github, ThreadStore store, Live live, Clock clock) {
        this.writes = writes;
        this.github = github;
        this.store = store;
        this.live = live;
        this.clock = clock;
    }

    /**
     * @param target  in words, where this goes: "Review on quarkusio/quarkus#50087"
     * @param confirm pass this to {@link #post} to send it
     * @param warning something to know before sending it (a new PR: open PRs that already mention the issue)
     */
    public record Preview(long draftId, String target, String kind, AgentOutput.DraftOut draft, PushOut push, PrOut pr,
            String url, String confirm, String warning) {

        Preview(long draftId, String target, String kind, AgentOutput.DraftOut draft, PushOut push, String url,
                String confirm) {
            this(draftId, target, kind, draft, push, null, url, confirm, null);
        }
    }

    /** @param pushedTo the item whose PR branch was pushed to (CI starts again), or null for a review or comment */
    public record Posted(String url, String pushedTo) {
    }

    /**
     * Commits for the runner to push from the item's worktree: the desk's git.push. {@code squash}: {@code to} is one
     * commit on {@code base} that replaces the branch, with a lease on {@code from}.
     */
    public record Push(String repo, int number, String headRepo, String headRef, String from, String to, boolean squash,
            String base) {
    }

    /** What {@link #post} did: posted it, or handed a push to the runner ({@link #postDone} finishes it). */
    public record Result(Posted posted, Push push) {
    }

    public Preview preview(long draftId) {
        return preview(draftId, null);
    }

    public Preview preview(long draftId, String githubToken) {
        return preview(draftId, githubToken, null);
    }

    /**
     * @param githubToken your read token, to work out where a push goes when the draft doesn't say yet (commits
     *                    from a reply rather than "Fix this")
     * @param login       you: a new PR comes from your fork
     */
    public Preview preview(long draftId, String githubToken, String login) {
        resolvePushTarget(draftId, githubToken);
        checkIssueRepo(draftId, githubToken);
        checkReviewLines(draftId, githubToken);
        String warning = resolvePrTarget(draftId, githubToken, login);
        Draft d = pendingDraft(draftId);
        DeskThread t = store.thread(d.threadId);
        String token = UUID.randomUUID().toString();
        d.confirm = new Draft.Confirm(token, d.payload, clock.instant().plus(CONFIRM_FOR));
        store.save(d.threadId);
        try {
            if (d.kind.equals(Draft.PUSH)) {
                PushOut push = mapper.readValue(d.payload, PushOut.class);
                String target = push.squashed()
                        ? "Replace " + push.headRepo() + ":" + push.headRef() + " (" + push.replaces()
                                + (push.replaces() == 1 ? " commit" : " commits") + ") with 1 squashed commit on "
                                + push.base() + ". A force push, refused if the branch changed since."
                        : "Push " + push.commits().size() + (push.commits().size() == 1 ? " commit" : " commits")
                                + " to " + push.headRepo() + ":" + push.headRef();
                return new Preview(draftId, target, d.kind, null, push, t.url, token);
            }
            if (d.kind.equals(Draft.PR)) {
                PrOut pr = mapper.readValue(d.payload, PrOut.class);
                return new Preview(draftId, "New PR on " + pr.repo() + " from " + pr.headRepo() + ":" + pr.headRef(), d.kind,
                        null, null, pr, "https://github.com/" + pr.repo() + "/pulls", token, warning);
            }
            AgentOutput.DraftOut out = mapper.readValue(d.payload, AgentOutput.DraftOut.class);
            if (d.kind.equals(Draft.ISSUE)) {
                return new Preview(draftId, "New issue in " + out.repo(), d.kind, out, null,
                        "https://github.com/" + out.repo() + "/issues", token);
            }
            String target = (d.kind.equals(Draft.REVIEW) ? "Review on " : "Comment on ") + t.itemKey;
            return new Preview(draftId, target, d.kind, out, null, t.url, token);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unreadable draft", e);
        }
    }

    /**
     * What the desk's confirm bar said you're posting ({@code kind} on {@code itemKey}, from the tab), checked
     * against the draft: the bar can't name one thing while another is posted. Null: the bar didn't say.
     */
    public void checkWords(long draftId, String kind, String itemKey) {
        Draft d = pendingDraft(draftId);
        if (kind != null && !kind.equals(d.kind)) throw new IllegalStateException("That draft is a " + d.kind + ", not a " + kind);
        if (itemKey != null && !itemKey.equals(store.thread(d.threadId).itemKey)) {
            throw new IllegalStateException("That draft isn't on " + itemKey);
        }
    }

    public Result post(long draftId, String confirm, String login) {
        return post(draftId, confirm, login, true);
    }

    /** @param asDraft for a PR draft: open it as a draft PR (true) or ready for review straight away (false) */
    public Result post(long draftId, String confirm, String login, boolean asDraft) {
        Draft draft = pendingDraft(draftId);
        Draft.Confirm p = draft.confirm;
        draft.confirm = null; // one time only
        store.save(draft.threadId);
        if (p == null || !p.token().equals(confirm) || clock.instant().isAfter(p.expires())
                || !p.payload().equals(draft.payload)) {
            throw new IllegalStateException("Preview it again before posting: the draft changed or the preview expired");
        }
        DeskThread t = store.thread(draft.threadId);
        if (draft.kind.equals(Draft.PUSH)) return new Result(null, push(draft, t, asDraft));
        if (draft.kind.equals(Draft.PR)) return new Result(null, openPr(draft, t, asDraft));
        AgentOutput.DraftOut out = read(draft.payload);
        if (draft.kind.equals(Draft.ISSUE)) return new Result(openIssue(draft, out, t, login), null);

        Map<String, Object> request = new LinkedHashMap<>();
        JsonNode response;
        try {
            if (draft.kind.equals(Draft.REVIEW)) {
                request.put("event", out.event() == null ? "COMMENT" : out.event());
                request.put("body", out.body());
                request.put("comments", out.comments().stream()
                        .map(c -> Map.of("path", c.path(), "line", c.line(), "side", "RIGHT", "body", c.body()))
                        .toList());
                response = writes.createReview(t.repo(), t.number(), request);
            } else {
                request.put("body", out.body());
                response = writes.createComment(t.repo(), t.number(), request);
            }
        } catch (GitHubWriter.WriteFailed e) {
            throw new IllegalStateException("GitHub refused it: " + githubMessage(e));
        }
        String url = response.path("html_url").asText(t.url);
        String requestJson = mapper.valueToTree(request).toString();

        draft.state = Draft.POSTED;
        draft.postedUrl = url;
        draft.postedAt = Instant.now();
        store.save(t.id);
        GitHubAction a = new GitHubAction();
        a.threadId = t.id;
        a.draftId = draftId;
        a.kind = draft.kind.equals(Draft.REVIEW) ? "review" : "comment";
        a.request = requestJson;
        a.resultUrl = url;
        a.login = login;
        store.audit(a);
        store.addTurn(t.id, Turn.SYSTEM, null,
                "You posted the " + (draft.kind.equals(Draft.REVIEW) ? "review" : "comment") + ": " + url);
        live.changed(t.itemKey);
        return new Result(new Posted(url, null), null);
    }

    /** The desk only writes to public repos: an issue draft's repo is checked with your read token before preview. */
    private void checkIssueRepo(long draftId, String githubToken) {
        Draft d = pendingDraft(draftId);
        if (!d.kind.equals(Draft.ISSUE)) return;
        String repo = read(d.payload).repo();
        if (githubToken == null || !github.isPublic(githubToken, repo)) {
            throw new IllegalStateException(repo + " isn't a public repo on GitHub, so the desk won't open an issue there."
                    + " Check the repo name in the draft.");
        }
    }

    /**
     * Opens the drafted issue in its repo. If the draft has a follow-up comment for this thread's item, that becomes
     * the next draft, with the new issue's link filled in, for you to post with its own click.
     */
    private Posted openIssue(Draft draft, AgentOutput.DraftOut out, DeskThread t, String login) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", out.title());
        request.put("body", out.body());
        JsonNode response;
        try {
            response = writes.createIssue(out.repo(), out.title(), out.body());
        } catch (GitHubWriter.WriteFailed e) {
            throw new IllegalStateException("GitHub refused it: " + githubMessage(e));
        }
        String url = response.path("html_url").asText(null);
        if (url == null) throw new IllegalStateException("GitHub didn't say where the issue is. Check " + out.repo());
        String requestJson = mapper.valueToTree(Map.of("repo", out.repo(), "issue", request)).toString();
        String followUp = out.then() == null || out.then().isBlank() ? null
                : mapper.valueToTree(new AgentOutput.DraftOut(Draft.COMMENT, null,
                        out.then().replace(AgentOutput.DraftOut.ISSUE_LINK, url), List.of())).toString();

        draft.state = Draft.POSTED;
        draft.postedUrl = url;
        draft.postedAt = Instant.now();
        store.save(t.id);
        GitHubAction a = new GitHubAction();
        a.threadId = t.id;
        a.draftId = draft.id;
        a.kind = "issue";
        a.request = requestJson;
        a.resultUrl = url;
        a.login = login;
        store.audit(a);
        store.addTurn(t.id, Turn.SYSTEM, null, "You opened the issue in " + out.repo() + ": " + url);
        if (followUp != null) {
            Draft next = new Draft();
            next.threadId = t.id;
            next.jobId = draft.jobId;
            next.kind = Draft.COMMENT;
            next.payload = followUp;
            store.add(next);
        }
        live.changed(t.itemKey);
        return new Posted(url, null);
    }

    /**
     * Closes an issue or PR on GitHub as you. The page asks you to confirm first; there is no draft to preview,
     * the request is just {state: closed}.
     */
    public String close(String itemKey, String login) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) {
            throw new IllegalArgumentException("Not an issue or PR: " + itemKey);
        }
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        JsonNode response;
        try {
            response = writes.close(repo, number);
        } catch (GitHubWriter.WriteFailed e) {
            throw new IllegalStateException("GitHub refused it: " + githubMessage(e));
        }
        String url = response.path("html_url").asText("https://github.com/" + repo + "/issues/" + number);
        boolean pr = response.path("pull_request").isObject();
        DeskThread t = store.byKey(itemKey);
        GitHubAction a = new GitHubAction();
        a.threadId = t == null ? null : t.id;
        a.kind = "close";
        a.request = "{\"item\":\"" + itemKey + "\",\"state\":\"closed\"}";
        a.resultUrl = url;
        a.login = login;
        store.audit(a);
        if (t != null) store.addTurn(t.id, Turn.SYSTEM, null, "You closed this " + (pr ? "PR" : "issue") + " on GitHub.");
        live.changed(itemKey);
        return url;
    }

    /** Marks your own draft PR ready for review: the PR you opened from a fix, once you've read the change. */
    public void ready(String itemKey, String login) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) throw new IllegalArgumentException("Not a PR: " + itemKey);
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        try {
            writes.readyForReview(repo, number);
        } catch (GitHubWriter.WriteFailed e) {
            throw new IllegalStateException("GitHub refused it: " + githubMessage(e));
        }
        DeskThread t = store.byKey(itemKey);
        GitHubAction a = new GitHubAction();
        a.threadId = t == null ? null : t.id;
        a.kind = "ready";
        a.request = "{\"item\":\"" + itemKey + "\",\"ready\":true}";
        a.resultUrl = "https://github.com/" + repo + "/pull/" + number;
        a.login = login;
        store.audit(a);
        if (t != null) store.addTurn(t.id, Turn.SYSTEM, null, "You marked the PR ready for review.");
        live.changed(itemKey);
    }

    /**
     * Assigns you to an issue or PR on GitHub. GitHub quietly ignores assignees without write access to the repo,
     * so the answer is checked.
     */
    public void assign(String itemKey, String login) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) {
            throw new IllegalArgumentException("Not an issue or PR: " + itemKey);
        }
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        JsonNode response;
        try {
            response = writes.assign(repo, number, login);
        } catch (GitHubWriter.WriteFailed e) {
            throw new IllegalStateException("GitHub refused it: " + githubMessage(e));
        }
        boolean assigned = false;
        for (JsonNode a : response.path("assignees")) assigned |= a.path("login").asText().equalsIgnoreCase(login);
        if (!assigned) {
            throw new IllegalStateException("GitHub didn't assign you. It only assigns people with write access to "
                    + repo + ".");
        }
        DeskThread t = store.byKey(itemKey);
        GitHubAction a = new GitHubAction();
        a.threadId = t == null ? null : t.id;
        a.kind = "assign";
        a.request = "{\"item\":\"" + itemKey + "\",\"assignee\":\"" + login + "\"}";
        a.resultUrl = response.path("html_url").asText(null);
        a.login = login;
        store.audit(a);
        if (t != null) store.addTurn(t.id, Turn.SYSTEM, null, "You assigned yourself on GitHub.");
        live.changed(itemKey);
    }

    /**
     * A review's inline comments must sit on lines of the PR's diff as it is now, or GitHub refuses the whole review
     * ("Path could not be resolved"). The PR can change after the agent drafted it, a file taken out or lines moved,
     * so this checks before preview. When the diff can't be read in full, GitHub decides.
     */
    private void checkReviewLines(long draftId, String githubToken) {
        Draft d = pendingDraft(draftId);
        if (!d.kind.equals(Draft.REVIEW) || githubToken == null) return;
        AgentOutput.DraftOut out = read(d.payload);
        if (out.comments() == null || out.comments().isEmpty()) return;
        DeskThread t = store.thread(d.threadId);
        GitHub.Diff diff = github.prDiff(githubToken, t.repo(), t.number(), 50_000_000);
        if (diff == null || diff.cut()) return;
        Map<String, Set<Integer>> lines = rightSideLines(diff.text());
        List<String> gone = new ArrayList<>();
        for (AgentOutput.InlineComment c : out.comments()) {
            Set<Integer> at = lines.get(c.path());
            if (at == null) gone.add(c.path() + " line " + c.line() + " (the file is no longer in the PR)");
            else if (!at.contains(c.line())) gone.add(c.path() + " line " + c.line() + " (no longer in the diff)");
        }
        if (gone.isEmpty()) return;
        throw new IllegalStateException("The PR changed since this review was drafted, and GitHub would refuse it: "
                + (gone.size() == 1 ? "the inline comment on " : "the inline comments on ") + String.join(", ", gone)
                + ". Ask the agent to look at the PR again, or discard this draft.");
    }

    /** For each file of a unified diff, the new-side lines a review comment can go on: added and context lines. */
    static Map<String, Set<Integer>> rightSideLines(String diff) {
        Map<String, Set<Integer>> lines = new HashMap<>();
        Set<Integer> current = null;
        int line = 0;
        for (String l : diff.split("\n", -1)) {
            if (l.startsWith("diff --git ")) {
                current = null;
            } else if (l.startsWith("+++ ") && current == null) {
                String path = l.substring(4);
                current = path.startsWith("b/") ? lines.computeIfAbsent(path.substring(2), k -> new HashSet<>()) : null;
            } else if (l.startsWith("@@ ")) {
                int plus = l.indexOf(" +");
                line = plus < 0 ? 0 : Integer.parseInt(l.substring(plus + 2).split("[, ]")[0]);
            } else if (current != null && line > 0) {
                if (l.startsWith("+") || l.startsWith(" ")) current.add(line++);
            }
        }
        return lines;
    }

    /**
     * Fills in where a PR draft's commit goes: a new branch on your fork of the repo, which must exist and be public.
     * Returns a warning when open PRs already mention the issue (check before opening another), or null.
     */
    private String resolvePrTarget(long draftId, String githubToken, String login) {
        Draft d = pendingDraft(draftId);
        if (!d.kind.equals(Draft.PR)) return null;
        if (githubToken == null || login == null) throw new IllegalStateException("Sign in again to open a PR");
        PrOut pr = readPr(d.payload);
        if (!github.isPublic(githubToken, pr.repo())) {
            throw new IllegalStateException(pr.repo() + " isn't a public repo, so the desk won't open a PR there.");
        }
        String fork = login + "/" + pr.repo().substring(pr.repo().indexOf('/') + 1);
        if (!github.isForkOf(githubToken, fork, pr.repo())) {
            throw new IllegalStateException("You have no fork of " + pr.repo() + " at " + fork + ". Fork it on GitHub first,"
                    + " then preview again.");
        }
        DeskThread t = store.thread(d.threadId);
        PrOut resolved = pr.withTarget(fork, "fix-" + t.number() + "-" + pr.to().substring(0, 7));
        d.payload = mapper.valueToTree(resolved).toString();
        store.save(d.threadId);
        List<GitHub.LinkedPr> open;
        try {
            open = github.linkedPrs(githubToken, List.of(t.itemKey)).getOrDefault(t.itemKey, List.of());
        } catch (Exception e) {
            return "Couldn't check for open PRs that already mention " + t.itemKey + ". Check on GitHub before opening this one.";
        }
        if (open.isEmpty()) return null;
        return "Open PRs already mention " + t.itemKey + ": " + String.join(", ", open.stream()
                .map(p -> p.key() + " (" + (p.fixes() ? "fixes it" : "mentions it") + ", by @" + p.author() + ")").toList())
                + ". Make sure this isn't a duplicate.";
    }

    private PrOut readPr(String json) {
        try {
            return mapper.readValue(json, PrOut.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable PR draft", e);
        }
    }

    /**
     * Opens the PR: brings your fork's base branch up to date (so the push sends only the fix), then hands the
     * runner the commit to push to a new branch there (never a force push). {@link #postDone} opens the PR from it,
     * as a draft (mark it ready for review later, {@link #ready}) or ready for review straight away. If the PR is
     * refused after the push, the branch stays; opening again pushes nothing new.
     */
    private Push openPr(Draft draft, DeskThread t, boolean asDraft) {
        PrOut pr = readPr(draft.payload);
        if (pr.headRepo() == null || pr.headRef() == null) throw new IllegalStateException("Preview it again before opening the PR");
        try {
            writes.syncFork(pr.headRepo(), pr.base());
        } catch (GitHubWriter.WriteFailed e) {
            // Not needed for the push to work, only to keep it small (a fork whose branch diverged can't sync).
        }
        draft.pushing = new Draft.Pushing(pr.from(), pr.to(), clock.instant(), asDraft);
        store.save(t.id);
        return new Push(t.repo(), t.number(), pr.headRepo(), pr.headRef(), pr.from(), pr.to(), false, null);
    }

    /**
     * The runner's answer to the push {@link #post} handed it ({@code status} 200, or why not): the posted mark,
     * the audit and the thread's note, and for a new PR, opening it on GitHub. Only for a push that post started on
     * this draft, for its commits, in the last 15 minutes; it writes nothing else to GitHub.
     */
    public Posted postDone(long draftId, int status, String error, String login) {
        Draft draft = pendingDraft(draftId);
        Draft.Pushing p = draft.pushing;
        if (p == null || p.at().plus(PUSH_FOR).isBefore(clock.instant())) {
            throw new IllegalStateException("There's no push of this draft waiting for the runner. Preview it again.");
        }
        String to = draft.kind.equals(Draft.PR) ? readPr(draft.payload).to() : readPush(draft.payload).to();
        if (!p.to().equals(to)) throw new IllegalStateException("The push waiting for the runner was for other commits. Preview it again.");
        draft.pushing = null;
        store.save(draft.threadId);
        DeskThread t = store.thread(draft.threadId);
        if (status != 200) {
            String why = error == null || error.isBlank() ? "HTTP " + status : error;
            worktreeGone(draft, t, new GitHubWriter.WriteFailed(status, null, why));
            if (draft.kind.equals(Draft.PR)) {
                PrOut pr = readPr(draft.payload);
                throw new IllegalStateException("The push to " + pr.headRepo() + " didn't go through: " + why);
            }
            throw new IllegalStateException("The push didn't go through: " + why);
        }
        return draft.kind.equals(Draft.PR) ? openedPr(draft, t, login, p.asDraft()) : pushed(draft, t, login);
    }

    /** The fix is on your fork: opens the PR from it. */
    private Posted openedPr(Draft draft, DeskThread t, String login, boolean asDraft) {
        PrOut pr = readPr(draft.payload);
        String head = pr.headRepo().substring(0, pr.headRepo().indexOf('/')) + ":" + pr.headRef();
        JsonNode response;
        try {
            response = writes.createPull(pr.repo(), pr.title(), head, pr.base(), pr.body(), asDraft);
        } catch (GitHubWriter.WriteFailed e) {
            throw new IllegalStateException("The commit is on " + pr.headRepo() + ":" + pr.headRef()
                    + ", but GitHub refused the PR: " + githubMessage(e));
        }
        String url = response.path("html_url").asText(null);
        if (url == null) throw new IllegalStateException("GitHub didn't say where the PR is. Check " + pr.repo());
        draft.state = Draft.POSTED;
        draft.postedUrl = url;
        draft.postedAt = Instant.now();
        store.save(t.id);
        GitHubAction a = new GitHubAction();
        a.threadId = t.id;
        a.draftId = draft.id;
        a.kind = "pr";
        a.request = draft.payload;
        a.resultUrl = url;
        a.login = login;
        store.audit(a);
        store.addTurn(t.id, Turn.SYSTEM, null, asDraft
                ? "You opened a draft PR: " + url + ". Mark it ready for review from its page here (or on GitHub)."
                : "You opened a PR: " + url);
        live.changed(t.itemKey);
        return new Posted(url, null);
    }

    /** Fills in a push draft's target branch, and refuses when it can't be pushed to. */
    private void resolvePushTarget(long draftId, String githubToken) {
        Draft d = pendingDraft(draftId);
        if (!d.kind.equals(Draft.PUSH)) return;
        PushOut p = readPush(d.payload);
        if (p.headRepo() != null && p.headRef() != null) return;
        if (githubToken == null) throw new IllegalStateException("Sign in again to push");
        DeskThread t = store.thread(d.threadId);
        GitHub.CiReport target = github.ci(githubToken, t.repo(), t.number());
        if (!target.canPush()) {
            throw new IllegalStateException("These commits can't be pushed: the author of " + t.itemKey
                    + " didn't allow edits from maintainers.");
        }
        PushOut resolved = p.withTarget(target.headRepo(), target.headRef());
        d.payload = mapper.valueToTree(resolved).toString();
        store.save(d.threadId);
    }

    /**
     * The runner no longer has the agent's working copy (cleaned up, or lost when the runner moved it), so the
     * draft's commits exist nowhere: it can never be pushed. Discards it, notes why in the thread, and says what to
     * run again. Returns normally when that isn't the failure.
     */
    void worktreeGone(Draft draft, DeskThread t, GitHubWriter.WriteFailed e) {
        if (e.getMessage() == null || !e.getMessage().contains("No worktree for")) return;
        if (Draft.PENDING.equals(draft.state)) draft.state = Draft.DISCARDED;
        store.save(t.id);
        Job j = store.job(draft.jobId);
        String what = j == null ? null : io.github.houseelves.github.agent.Prompts.label(j.action, null);
        store.addTurn(t.id, Turn.SYSTEM, null, "The agent's working copy for this is gone, so its commits can't be pushed."
                + " The draft is discarded." + (what != null ? " Start \"" + what.replaceAll("\\.$", "") + "\" again for new ones." : ""));
        String again = what;
        live.changed(t.itemKey);
        throw new IllegalStateException("The agent's working copy for " + t.itemKey + " is gone, so these commits can't be "
                + "pushed. The draft is discarded" + (again != null ? "; start \"" + again.replaceAll("\\.$", "") + "\" again." : "."));
    }

    private PushOut readPush(String json) {
        try {
            return mapper.readValue(json, PushOut.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable push draft", e);
        }
    }

    /** Hands the runner the commits to push to the PR's branch, if it's still at {@code from}. */
    private Push push(Draft draft, DeskThread t, boolean asDraft) {
        PushOut p = readPush(draft.payload);
        draft.pushing = new Draft.Pushing(p.from(), p.to(), clock.instant(), asDraft);
        store.save(t.id);
        return new Push(t.repo(), t.number(), p.headRepo(), p.headRef(), p.from(), p.to(), p.squashed(),
                p.squashed() ? p.base() : null);
    }

    /** The runner pushed the commits. */
    private Posted pushed(Draft draft, DeskThread t, String login) {
        PushOut p = readPush(draft.payload);
        String url = t.url + "/commits/" + p.to();
        draft.state = Draft.POSTED;
        draft.postedUrl = url;
        draft.postedAt = Instant.now();
        store.save(t.id);
        GitHubAction a = new GitHubAction();
        a.threadId = t.id;
        a.draftId = draft.id;
        a.kind = "push";
        a.request = draft.payload;
        a.resultUrl = url;
        a.login = login;
        store.audit(a);
        store.addTurn(t.id, Turn.SYSTEM, null, p.squashed()
                ? "You replaced " + p.headRepo() + ":" + p.headRef() + " with one squashed commit. CI will run again."
                : "You pushed " + p.commits().size() + (p.commits().size() == 1 ? " commit" : " commits") + " to "
                        + p.headRepo() + ":" + p.headRef() + ". CI will run again.");
        live.changed(t.itemKey);
        return new Posted(url, t.itemKey);
    }

    private Draft pendingDraft(long draftId) {
        Draft d = store.draft(draftId);
        if (d == null || !d.state.equals(Draft.PENDING)) {
            throw new IllegalStateException("That draft was already posted or discarded");
        }
        return d;
    }

    private AgentOutput.DraftOut read(String json) {
        try {
            return mapper.readValue(json, AgentOutput.DraftOut.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable draft", e);
        }
    }

    /** GitHub's own explanation, e.g. a 422 for an inline comment on a line outside the diff. */
    String githubMessage(GitHubWriter.WriteFailed e) {
        if (e.body == null || e.body.isBlank()) return e.getMessage();
        try {
            JsonNode body = mapper.readTree(e.body);
            StringBuilder sb = new StringBuilder(body.path("message").asText(e.getMessage()));
            List<String> details = new ArrayList<>();
            body.path("errors").forEach(x -> details.add(x.isTextual() ? x.asText() : x.path("message").asText(x.toString())));
            if (!details.isEmpty()) sb.append(" (").append(String.join("; ", details)).append(')');
            return sb.toString();
        } catch (Exception ignored) {
            return e.getMessage();
        }
    }
}
