package io.github.houseelves.github.api;

import java.util.List;
import java.util.function.Supplier;

import io.github.houseelves.github.agent.AgentOutput;
import io.github.houseelves.github.board.Board;
import io.github.houseelves.github.board.BoardService;
import io.github.houseelves.github.ci.CiService;
import io.github.houseelves.github.conflicts.ConflictService;
import io.github.houseelves.github.elf.Live;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.ignore.IgnoreService;
import io.github.houseelves.github.merge.MergeService;
import io.github.houseelves.github.scope.ScopeService;
import io.github.houseelves.github.settings.Settings;
import io.github.houseelves.github.thread.Poster;
import io.github.houseelves.github.thread.ThreadService;
import io.github.houseelves.github.thread.ThreadView;

/**
 * The board half of the desk's DeskApi, method for method, with the same params and answers: each is one of the
 * elf's actions (see {@link Actions}). Only {@link #post} (after {@link #previewPost}, for reviews, comments,
 * issues, pushes and PRs), {@link #close}, {@link #mergeOnGreen} and the CI, branch and assign actions lead to
 * GitHub writes, each from a click you confirm in the desk's bar.
 */
public class BoardApi {

    final String login;
    final Supplier<String> token;
    final BoardService boards;
    final ScopeService scopes;
    final Settings settings;
    final ThreadService threads;
    final Poster poster;
    final MergeService merges;
    final CiService ci;
    final GitHub github;
    final ConflictService conflicts;
    final IgnoreService ignores;
    final io.github.houseelves.github.later.LaterService later;
    final Live live;

    public BoardApi(String login, Supplier<String> token, BoardService boards, ScopeService scopes, Settings settings,
            ThreadService threads, Poster poster, MergeService merges, CiService ci,
            GitHub github, ConflictService conflicts, IgnoreService ignores,
            io.github.houseelves.github.later.LaterService later, Live live) {
        this.login = login;
        this.token = token;
        this.boards = boards;
        this.scopes = scopes;
        this.settings = settings;
        this.threads = threads;
        this.poster = poster;
        this.merges = merges;
        this.ci = ci;
        this.github = github;
        this.conflicts = conflicts;
        this.ignores = ignores;
        this.later = later;
        this.live = live;
    }

    private String token() {
        return token.get();
    }

    // ---- board ----

    /** The board, as board/board.json has it. {@code refresh} fetches GitHub now; otherwise it's built from the cache. */
    public Board board(boolean refresh) {
        return boards.board(login, refresh || !boards.cached(login) ? token() : null, refresh)
                .with(threads.summaries(), merges.recent(), true)
                .withFlows(java.time.Instant.now()).later(later.keys());
    }

    /**
     * The elf's refresh: fetches the board, takes in any agents that ended without reaching the elf, and runs
     * merge on green once (what the desk did every minute).
     */
    public Board refresh() {
        threads.takeIn();
        merges.poll(token());
        return board(true);
    }

    // ---- threads ----

    /**
     * The item as it is on GitHub now (null once it's closed or merged), for the thread page: what it offers
     * depends on it, and it changes with what you do there. The board's copy is updated too.
     */
    public Item item(String itemKey) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) throw new IllegalArgumentException("Not an item: " + itemKey);
        return boards.refreshItem(login, token(), itemKey);
    }

    /** What the item is about, from its description on GitHub: for the thread page's recap. */
    public GitHub.About about(String itemKey) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) throw new IllegalArgumentException("Not an item: " + itemKey);
        int hash = itemKey.indexOf('#');
        return github.about(token(), itemKey.substring(0, hash), Integer.parseInt(itemKey.substring(hash + 1)));
    }

    /** The thread for one item (owner/repo#number), or null if there is none yet: what threads/{key}.json has. */
    public ThreadView thread(String itemKey) {
        return threads.view(itemKey);
    }

    /** The thread page showed it: its answers count as seen (the desk did this when the page read the thread). */
    public ThreadView seen(String itemKey) {
        ThreadView v = threads.view(itemKey);
        if (v != null && v.job() == null) threads.seen(itemKey);
        return threads.view(itemKey);
    }

    /** Start an agent: action is review (PR), investigate (issue) or ask (question required). */
    public ThreadView start(String itemKey, String action, String question) {
        return threads.start(itemKey, action, question, login, token());
    }

    /** After a self-review: the agent makes the fixes it listed. */
    public ThreadView fixReview(String itemKey) {
        return threads.fixReview(itemKey);
    }

    public ThreadView reply(String itemKey, String text) {
        return threads.reply(itemKey, text, login, token());
    }

    public ThreadView cancel(String itemKey) {
        return threads.cancel(itemKey);
    }

    public ThreadView updateDraft(long draftId, AgentOutput.DraftOut draft) {
        return threads.updateDraft(draftId, draft);
    }

    public ThreadView discardDraft(long draftId) {
        return threads.discardDraft(draftId);
    }

    /** Step one of posting: exactly what will be sent, and a one-time confirmation. */
    public Poster.Preview previewPost(long draftId) {
        return poster.preview(draftId, token(), login);
    }

    /**
     * Step two: post to GitHub as you. asDraft: a PR opens as a draft (else ready). Commits come back as the push
     * the runner makes ({@link Poster.Result#push}); {@link #postDone} finishes them.
     */
    public Poster.Result post(long draftId, String confirm, Boolean asDraft) {
        return poster.post(draftId, confirm, login, asDraft == null || asDraft);
    }

    /** As above, with what the confirm bar named ({kind} on {itemKey}), which must be the draft's. */
    public Poster.Result post(long draftId, String confirm, Boolean asDraft, String kind, String itemKey) {
        poster.checkWords(draftId, kind, itemKey);
        return post(draftId, confirm, asDraft);
    }

    /** The runner pushed (status 200) or didn't: finishes the post that handed it the push. */
    public Poster.Posted postDone(long draftId, int status, String error) {
        Poster.Posted posted = poster.postDone(draftId, status, error, login);
        // A push starts CI again: show it running now, not the old result until the next fetch.
        if (posted.pushedTo() != null) {
            String to = posted.url().substring(posted.url().lastIndexOf('/') + 1);
            boards.markPushed(login, posted.pushedTo(), to);
        }
        return posted;
    }

    /** Close an issue or PR on GitHub, as you. The page confirms first. Returns its URL. */
    public String close(String itemKey) {
        String url = poster.close(itemKey, login);
        boards.forget(login, itemKey);
        return url;
    }

    /** A PR's change as a unified diff (the first 2 MB), or null when GitHub won't give one. */
    public GitHub.Diff prDiff(String itemKey) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) throw new IllegalArgumentException("Not a PR: " + itemKey);
        return github.prDiff(token(), itemKey.substring(0, itemKey.indexOf('#')),
                Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1)), 2_000_000);
    }

    /** Mark your own draft PR ready for review. Returns the PR as it is now. */
    public Item readyForReview(String itemKey) {
        poster.ready(itemKey, login);
        return boards.refreshItem(login, token(), itemKey);
    }

    /** Assign yourself to an issue or PR on GitHub. */
    public boolean assignMe(String itemKey) {
        poster.assign(itemKey, login);
        boards.markAssigned(login, itemKey);
        return true;
    }

    // ---- what's new ----

    /** What happened on a PR since your last review or comment: commits, reviews, comments, review requests. */
    public GitHub.Activity prActivity(String itemKey) {
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        return github.activity(token(), login, repo, number);
    }

    /** What reviewers said on a PR, in full: review summaries, comments and inline threads with their code. */
    public GitHub.Reviews prReviews(String itemKey) {
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        return github.reviews(token(), login, repo, number);
    }

    // ---- ignore ----

    /** Puts an item you'd have to move on aside, under Later. Nothing changes on GitHub. */
    public void later(String itemKey) {
        later.later(itemKey);
        live.boardUpdated();
    }

    /** Back to Your move: you asked, or you did something with it. */
    public void now(String itemKey) {
        if (later.back(itemKey)) live.boardUpdated();
    }

    /** Move an item to Ignored: you're involved, but someone else is handling it. Nothing changes on GitHub. */
    public void ignore(String itemKey) {
        ignores.ignore(itemKey, login);
        live.boardUpdated();
    }

    public void unignore(String itemKey) {
        ignores.unignore(itemKey);
        live.boardUpdated();
    }

    public boolean isIgnored(String itemKey) {
        return ignores.isIgnored(itemKey);
    }

    // ---- failing CI ----

    /** What failed on a PR's head, whether there are Actions runs to re-run, and whether a fix could be pushed. */
    public GitHub.CiReport ciReport(String itemKey) {
        GitHub.CiReport report = ci.report(itemKey, token());
        if (!CiService.failing(report)) boards.refreshItem(login, token(), itemKey);
        return report;
    }

    /** Re-run the failed GitHub Actions jobs. Returns how many workflow runs. */
    public int ciRerun(String itemKey) {
        int runs = ci.rerun(itemKey, login, token());
        boards.markCi(login, itemKey, "PENDING"); // CI is running again: don't keep showing the failure
        return runs;
    }

    /** Your own PR, no write access to the repo: close and reopen it so CI runs again. */
    public void ciReopen(String itemKey) {
        ci.reopenToRerun(itemKey, login, token());
        boards.markCi(login, itemKey, "PENDING");
    }

    /** "Approve and run": approve the workflow runs waiting for a maintainer on a fork PR. Returns how many runs. */
    public int ciApprove(String itemKey) {
        int runs = ci.approve(itemKey, login, token());
        boards.markCi(login, itemKey, "PENDING");
        return runs;
    }

    /** The agent on why CI is failing: opens the existing answer if there is one for this commit (or it's working). */
    public ThreadView ciWhy(String itemKey) {
        return why(itemKey, false);
    }

    /** Ask why CI is failing again, even if it already answered for this commit. */
    public ThreadView ciWhyAgain(String itemKey) {
        return why(itemKey, true);
    }

    /** When CI passes by now, the board's copy of the PR is read again so the old failure goes. */
    private ThreadView why(String itemKey, boolean again) {
        try {
            return ci.why(itemKey, login, token(), again);
        } catch (CiService.NotFailing e) {
            boards.refreshItem(login, token(), itemKey);
            throw e;
        }
    }

    /** Start the agent on fixing CI; its commits come back as a push draft for you to confirm. */
    public ThreadView ciFix(String itemKey) {
        return ci.fix(itemKey, login, token());
    }

    /** Start the agent on the open review threads of your PR; its commits come back as a push draft. */
    public ThreadView addressReview(String itemKey) {
        return ci.address(itemKey, login, token());
    }

    // ---- merge conflicts ----

    public ConflictService.Options conflictOptions(String itemKey) {
        return conflicts.options(itemKey, token());
    }

    /** Comment "@dependabot rebase" on a Dependabot PR, as you. Returns the comment's URL. */
    public String askDependabotToRebase(String itemKey) {
        String url = conflicts.askDependabotToRebase(itemKey, login, token());
        boards.markRebasing(login, itemKey);
        return url;
    }

    /** Bring a PR that's behind its base up to date: method REBASE (Dependabot PRs: "@dependabot rebase"). */
    public String updateBranch(String itemKey, String method) {
        String done = conflicts.updateBranch(itemKey, method, login, token());
        boards.markCi(login, itemKey, "PENDING"); // new head: CI runs again
        return done;
    }

    /** Start the agent on merging the base branch in; its merge commit comes back as a push draft. */
    public ThreadView resolveConflicts(String itemKey) {
        return conflicts.resolve(itemKey, login, token());
    }

    /** Commits on the PR: more than one is offered "Squash into one commit". */
    public int commitCount(String itemKey) {
        return conflicts.commitCount(itemKey, token());
    }

    /** The runner squashes the PR into one commit on its base: what it squashes ({"squash": checkout}). */
    public com.fasterxml.jackson.databind.node.ObjectNode squash(String itemKey) {
        return conflicts.squash(itemKey, token());
    }

    /** The runner's squash: it comes back as a push you confirm. */
    public ThreadView squashDone(String itemKey, int status, String body, String error) {
        return threads.squashDone(itemKey, status, body, error);
    }

    /** One of the elf's agents ended (the runner calls this with its id): its report is taken in. */
    public ThreadView agentDone(String id) {
        String itemKey = threads.agentDone(id);
        return itemKey == null ? null : threads.view(itemKey);
    }

    // ---- merge on green ----

    /** What the confirmation needs: allowed methods, your usual one, CI state, and the head you're agreeing to. */
    public MergeService.Options mergeOptions(String itemKey) {
        return merges.options(itemKey, token());
    }

    /** Merge when CI is green, pinned to {@code headSha} (from mergeOptions). Merges at once if it's green already. */
    public MergeService.View mergeOnGreen(String itemKey, String method, String headSha) {
        return merges.request(itemKey, method, headSha, login, token());
    }

    /** Merge anyway: green CI but no approval, because you know it's fine (e.g. a routine parent bump). */
    public MergeService.View mergeWithoutApproval(String itemKey, String method, String headSha) {
        return merges.request(itemKey, method, headSha, login, token(), true);
    }

    public MergeService.View cancelMerge(String itemKey) {
        return merges.cancel(itemKey);
    }

    /** The latest merge on green for a PR, or null. */
    public MergeService.View mergeStatus(String itemKey) {
        return merges.view(itemKey);
    }

    // ---- settings ----

    public record ScopeRow(long id, String kind, String name, String mode) {
    }

    /** @param alwaysInvolved repos you maintain: every open PR and issue in them is on the board */
    public record SettingsView(String login, List<ScopeRow> scope, int refreshMinutes, List<String> alwaysInvolved) {
    }

    /** What state/settings-view.json has. */
    public SettingsView settings() {
        List<ScopeRow> rows = scopes.rows().stream()
                .map(w -> new ScopeRow(w.id, w.kind.name(), w.name, w.mode.name()))
                .toList();
        return new SettingsView(login, rows, settings.refreshMinutes(), settings.alwaysInvolved());
    }

    /** A repo you maintain: its open PRs come to "Needs my review" and its unassigned issues are yours, involved or not. */
    public SettingsView addAlwaysInvolved(String repo) {
        String name = ScopeService.repoName(repo);
        // GitHub refuses a whole search that names a repo it can't find, so only real ones go in.
        if (!github.isPublic(token(), name)) {
            throw new IllegalArgumentException("There's no public repo " + name + " on GitHub. Check the name.");
        }
        // Outside what the board watches it would be hidden, so it's watched too.
        if (!scopes.scope().covers(name)) scopes.watch(name);
        List<String> repos = new java.util.ArrayList<>(settings.alwaysInvolved());
        if (!repos.contains(name)) repos.add(name);
        settings.setAlwaysInvolved(repos);
        return changed();
    }

    public SettingsView removeAlwaysInvolved(String repo) {
        String name = ScopeService.repoName(repo);
        settings.setAlwaysInvolved(settings.alwaysInvolved().stream().filter(r -> !r.equals(name)).toList());
        return changed();
    }

    public List<BoardService.Suggestion> suggestions() {
        return boards.suggestions(login, token());
    }

    /** Watch an org, a user, or one owner/repo. */
    public SettingsView watch(String name) {
        scopes.watch(name);
        return changed();
    }

    /** First-run save: watch these owners in one go. */
    public SettingsView watchAll(List<String> owners) {
        scopes.watchAll(owners);
        return changed();
    }

    /** Stop showing one repo of a watched org. */
    public SettingsView exclude(String repo) {
        scopes.exclude(repo);
        return changed();
    }

    public SettingsView remove(long id) {
        scopes.remove(id);
        return changed();
    }

    public SettingsView setRefreshMinutes(int minutes) {
        settings.setRefreshMinutes(minutes);
        return changed();
    }

    /** The settings changed: the board and state/settings-view.json are written again. */
    private SettingsView changed() {
        live.settingsChanged();
        return settings();
    }
}
