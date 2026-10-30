package io.github.houseelves.github.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.houseelves.github.agent.AgentOutput;
import io.github.houseelves.github.board.Board;
import io.github.houseelves.github.board.BoardMerge;
import io.github.houseelves.github.board.BoardService;
import io.github.houseelves.github.ci.CiService;
import io.github.houseelves.github.conflicts.ConflictService;
import io.github.houseelves.github.elf.Accounts;
import io.github.houseelves.github.elf.Live;
import io.github.houseelves.github.elf.Log;
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

    private static final Log LOG = Log.of(BoardApi.class);

    /** The board's accounts: everything is done as the acting one (the item's, picked before an action runs). */
    final Accounts accounts;
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

    public BoardApi(Accounts accounts, BoardService boards, ScopeService scopes, Settings settings,
            ThreadService threads, Poster poster, MergeService merges, CiService ci,
            GitHub github, ConflictService conflicts, IgnoreService ignores,
            io.github.houseelves.github.later.LaterService later, Live live) {
        this.accounts = accounts;
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
        return accounts.token();
    }

    private String login() {
        return accounts.login();
    }

    // ---- board ----

    /** The board, as board/board.json has it. {@code refresh} fetches GitHub now; otherwise it's built from the cache. */
    public Board board(boolean refresh) {
        return boards(refresh).board();
    }

    /**
     * The board of every account, merged ({@link BoardMerge}), with which account each item belongs to. Built from
     * the accounts with something cached; with {@code refresh} (or nothing cached at all), GitHub is asked first.
     */
    public BoardMerge.Merged boards(boolean refresh) {
        List<String> logins = accounts.logins().stream().filter(l -> refresh || boards.cached(l)).toList();
        if (logins.isEmpty()) logins = List.of(accounts.primary());
        String was = login();
        List<BoardMerge.Part> parts = new java.util.ArrayList<>();
        try {
            for (String l : logins) {
                accounts.actAs(l);
                Board b = boards.board(l, refresh || !boards.cached(l) ? token() : null, refresh)
                        .with(threads.summaries(), merges.recent(), true)
                        .withFlows(java.time.Instant.now()).later(later.keys());
                parts.add(new BoardMerge.Part(l, b));
            }
        } finally {
            accounts.actAs(was);
        }
        return BoardMerge.merge(parts);
    }

    /**
     * The elf's refresh: fetches the board, takes in any agents that ended without reaching the elf, and runs
     * merge on green once (what the desk did every minute).
     */
    public Board refresh() {
        return refresh(List.of(fetch()));
    }

    /** The refresh's read of GitHub, which takes the longest: nothing changes, so the elf runs it without its lock. */
    public BoardService.Fetch fetch() {
        return boards.fetch(login(), token());
    }

    /**
     * The rest of the refresh, under the elf's lock: keeps what {@link #fetch} read (once per account), then agents
     * and merges, each merge as the account that asked for it.
     */
    public Board refresh(List<BoardService.Fetch> fetched) {
        threads.takeIn();
        String was = login();
        try {
            for (String l : accounts.logins()) {
                accounts.actAs(l);
                boolean first = l.equals(accounts.primary());
                try {
                    merges.poll(token(), by -> by == null ? first : by.equalsIgnoreCase(l));
                } catch (RuntimeException e) {
                    // gh has no token for it now: its merges wait for the next refresh.
                    LOG.warnf("Merge on green as %s: %s", l, e.getMessage());
                }
            }
            for (BoardService.Fetch f : fetched) {
                accounts.actAs(f.fetched().login());
                boards.board(f);
            }
        } finally {
            accounts.actAs(was);
        }
        return board(false);
    }

    // ---- threads ----

    /**
     * The item as it is on GitHub now (null once it's closed or merged), for the thread page: what it offers
     * depends on it, and it changes with what you do there. The board's copy is updated too.
     */
    public Item item(String itemKey) {
        if (!itemKey.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) throw new IllegalArgumentException("Not an item: " + itemKey);
        return boards.refreshItem(login(), token(), itemKey);
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
        return threads.start(itemKey, action, question, login(), token());
    }

    /** After a self-review: the agent makes the fixes it listed. */
    public ThreadView fixReview(String itemKey) {
        return threads.fixReview(itemKey);
    }

    public ThreadView reply(String itemKey, String text) {
        return threads.reply(itemKey, text, login(), token());
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
        return poster.preview(draftId, token(), login());
    }

    /**
     * Step two: post to GitHub as you. asDraft: a PR opens as a draft (else ready). Commits come back as the push
     * the runner makes ({@link Poster.Result#push}); {@link #postDone} finishes them.
     */
    public Poster.Result post(long draftId, String confirm, Boolean asDraft) {
        return poster.post(draftId, confirm, login(), asDraft == null || asDraft);
    }

    /** As above, with what the confirm bar named ({kind} on {itemKey}), which must be the draft's. */
    public Poster.Result post(long draftId, String confirm, Boolean asDraft, String kind, String itemKey) {
        return post(draftId, confirm, asDraft, kind, itemKey, null);
    }

    /** @param askAgain a push: also ask these reviewers (comma separated, ones its preview offered) to review again */
    public Poster.Result post(long draftId, String confirm, Boolean asDraft, String kind, String itemKey, String askAgain) {
        poster.checkWords(draftId, kind, itemKey);
        List<String> ask = askAgain == null ? List.of()
                : java.util.Arrays.stream(askAgain.split(",")).map(String::strip).filter(r -> !r.isEmpty()).toList();
        return poster.post(draftId, confirm, login(), asDraft == null || asDraft, ask);
    }

    /** The runner pushed (status 200) or didn't: finishes the post that handed it the push. */
    public Poster.Posted postDone(long draftId, int status, String error) {
        Poster.Posted posted = poster.postDone(draftId, status, error, login());
        // A push starts CI again: show it running now, not the old result until the next fetch.
        if (posted.pushedTo() != null) {
            String to = posted.url().substring(posted.url().lastIndexOf('/') + 1);
            boards.markPushed(login(), posted.pushedTo(), to);
            if (!posted.askAgain().isEmpty()) {
                // You ticked it with the push: now it's there, ask them. The push stands if GitHub refuses this.
                try {
                    poster.requestReviewers(posted.pushedTo(), posted.askAgain(), login());
                    boards.markReviewRequested(login(), posted.pushedTo(), posted.askAgain());
                } catch (RuntimeException e) {
                    poster.note(posted.pushedTo(), "Pushed, but asking " + String.join(", ", posted.askAgain())
                            + " to review again failed: " + e.getMessage());
                }
            }
        }
        return posted;
    }

    /** Close an issue or PR on GitHub, as you. The page confirms first. Returns its URL. */
    public String close(String itemKey) {
        String url = poster.close(itemKey, login());
        boards.forget(login(), itemKey);
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
        poster.ready(itemKey, login());
        return boards.refreshItem(login(), token(), itemKey);
    }

    /** Assign yourself to an issue or PR on GitHub. */
    /**
     * Asks the reviewers of your PR to review it again, after you pushed since their review. Only those GitHub says
     * reviewed before your latest commit, read now: {@code reviewers} (what the confirm bar named) must be them.
     */
    public boolean reRequestReview(String itemKey, String reviewers) {
        GitHub.PrDetails d = github.prDetails(token(), List.of(itemKey), login()).get(itemKey);
        List<String> due = d == null || d.feedback() == null ? List.of() : d.feedback().reReview();
        List<String> asked = java.util.Arrays.stream(reviewers.split(",")).map(String::strip).filter(r -> !r.isEmpty()).toList();
        if (asked.isEmpty() || !asked.stream().allMatch(a -> due.stream().anyMatch(a::equalsIgnoreCase))) {
            throw new IllegalStateException("Only " + (due.isEmpty() ? "nobody" : String.join(", ", due))
                    + " reviewed before your latest commit, so only they can be asked again. Refresh the board.");
        }
        poster.requestReviewers(itemKey, asked, login());
        boards.markReviewRequested(login(), itemKey, asked);
        return true;
    }

    public boolean assignMe(String itemKey) {
        poster.assign(itemKey, login());
        boards.markAssigned(login(), itemKey);
        return true;
    }

    // ---- what's new ----

    /** What happened on a PR since your last review or comment: commits, reviews, comments, review requests. */
    public GitHub.Activity prActivity(String itemKey) {
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        return github.activity(token(), login(), repo, number);
    }

    /** What reviewers said on a PR, in full: review summaries, comments and inline threads with their code. */
    public GitHub.Reviews prReviews(String itemKey) {
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        return github.reviews(token(), login(), repo, number);
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
        ignores.ignore(itemKey, login());
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
        if (!CiService.failing(report)) boards.refreshItem(login(), token(), itemKey);
        return report;
    }

    /** Re-run the failed GitHub Actions jobs. Returns how many workflow runs. */
    public int ciRerun(String itemKey) {
        int runs = ci.rerun(itemKey, login(), token());
        boards.markCi(login(), itemKey, "PENDING"); // CI is running again: don't keep showing the failure
        return runs;
    }

    /** Your own PR, no write access to the repo: close and reopen it so CI runs again. */
    public void ciReopen(String itemKey) {
        ci.reopenToRerun(itemKey, login(), token());
        boards.markCi(login(), itemKey, "PENDING");
    }

    /** "Approve and run": approve the workflow runs waiting for a maintainer on a fork PR. Returns how many runs. */
    public int ciApprove(String itemKey) {
        int runs = ci.approve(itemKey, login(), token());
        boards.markCi(login(), itemKey, "PENDING");
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
            return ci.why(itemKey, login(), token(), again);
        } catch (CiService.NotFailing e) {
            boards.refreshItem(login(), token(), itemKey);
            throw e;
        }
    }

    /** Start the agent on fixing CI; its commits come back as a push draft for you to confirm. */
    public ThreadView ciFix(String itemKey) {
        return ci.fix(itemKey, login(), token());
    }

    /** Start the agent on the open review threads of your PR; its commits come back as a push draft. */
    public ThreadView addressReview(String itemKey) {
        return ci.address(itemKey, login(), token());
    }

    // ---- merge conflicts ----

    public ConflictService.Options conflictOptions(String itemKey) {
        return conflicts.options(itemKey, token());
    }

    /** Comment "@dependabot rebase" on a Dependabot PR, as you. Returns the comment's URL. */
    public String askDependabotToRebase(String itemKey) {
        String url = conflicts.askDependabotToRebase(itemKey, login(), token());
        boards.markRebasing(login(), itemKey);
        return url;
    }

    /** Bring a PR that's behind its base up to date: method REBASE (Dependabot PRs: "@dependabot rebase"). */
    public String updateBranch(String itemKey, String method) {
        String done = conflicts.updateBranch(itemKey, method, login(), token());
        boards.markCi(login(), itemKey, "PENDING"); // new head: CI runs again
        return done;
    }

    /** Start the agent on merging the base branch in; its merge commit comes back as a push draft. */
    public ThreadView resolveConflicts(String itemKey) {
        return conflicts.resolve(itemKey, login(), token());
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
        return merges.request(itemKey, method, headSha, login(), token());
    }

    /** Merge anyway: green CI but no approval, because you know it's fine (e.g. a routine parent bump). */
    public MergeService.View mergeWithoutApproval(String itemKey, String method, String headSha) {
        return merges.request(itemKey, method, headSha, login(), token(), true);
    }

    public MergeService.View cancelMerge(String itemKey) {
        return merges.cancel(itemKey);
    }

    /** The latest merge on green for a PR, or null. */
    public MergeService.View mergeStatus(String itemKey) {
        return merges.view(itemKey);
    }

    // ---- settings ----

    /** @param accounts the board's accounts with items in it, as the last refresh found them */
    public record ScopeRow(long id, String kind, String name, String mode, List<String> accounts) {
    }

    /** @param alwaysInvolved repos you maintain: every open PR and issue in them is on the board */
    /**
     * @param accounts      the gh accounts the board works as, GITHUB_ACCOUNT ({@code login}) first, each named
     * @param maintainedBy  for each repo you maintain, the accounts its items on the board belong to (every account
     *                      searches them, so it's who the items are done as that tells them apart)
     */
    public record SettingsView(String login, List<ScopeRow> scope, int refreshMinutes, List<String> alwaysInvolved,
            List<Accounts.Named> accounts, Map<String, List<String>> maintainedBy) {
    }

    /** What state/settings-view.json has. */
    public SettingsView settings() {
        List<ScopeRow> rows = scopes.rows().stream()
                .map(w -> new ScopeRow(w.id, w.kind.name(), w.name, w.mode.name(), accountsIn(w.name)))
                .toList();
        Map<String, List<String>> maintainedBy = new LinkedHashMap<>();
        for (String repo : settings.alwaysInvolved()) {
            String prefix = repo.toLowerCase(java.util.Locale.ROOT) + "#";
            maintainedBy.put(repo, accounts.logins().stream().filter(l -> accounts.owners().entrySet().stream()
                    .anyMatch(e -> e.getValue().equalsIgnoreCase(l) && e.getKey().toLowerCase(java.util.Locale.ROOT)
                            .startsWith(prefix))).toList());
        }
        return new SettingsView(accounts.primary(), rows, settings.refreshMinutes(), settings.alwaysInvolved(), accounts.all(),
                maintainedBy);
    }

    /** The accounts that have items in an owner's repos (a name without /) or in one repo. */
    private List<String> accountsIn(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return accounts.logins().stream().filter(l -> boards.repos(l).stream()
                .anyMatch(r -> n.contains("/") ? r.equals(n) : r.startsWith(n + "/"))).toList();
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

    /** Owners to watch, from every account: an owner's items added up, and a member of it if any account is. */
    public List<BoardService.Suggestion> suggestions() {
        Map<String, BoardService.Suggestion> byOwner = new LinkedHashMap<>();
        String was = login();
        try {
            for (String l : accounts.logins()) {
                accounts.actAs(l);
                List<BoardService.Suggestion> found;
                try {
                    found = boards.suggestions(l, token());
                } catch (RuntimeException e) {
                    // One account gh can't answer for: the others' suggestions still show.
                    if (accounts.logins().size() == 1) throw e;
                    continue;
                }
                for (BoardService.Suggestion s : found) {
                    byOwner.merge(s.owner().toLowerCase(java.util.Locale.ROOT), s, (a, b) -> new BoardService.Suggestion(
                            a.owner(), a.items() + b.items(), a.member() || b.member(), a.preselect() || b.preselect(),
                            java.util.stream.Stream.concat(a.accounts().stream(), b.accounts().stream()).distinct().toList()));
                }
            }
        } finally {
            accounts.actAs(was);
        }
        return byOwner.values().stream()
                .sorted(java.util.Comparator.comparingInt(BoardService.Suggestion::items).reversed()
                        .thenComparing(BoardService.Suggestion::owner, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ---- accounts ----

    /** Another gh account on the board, named (Work, Personal). Its items come with the next refresh. */
    public SettingsView addAccount(String login, String name) {
        accounts.add(login, name);
        return changed();
    }

    public SettingsView renameAccount(String login, String name) {
        accounts.rename(login, name);
        live.boardUpdated();
        return changed();
    }

    /** Takes an account off the board, with its items. Nothing changes on GitHub. */
    public SettingsView removeAccount(String login) {
        accounts.remove(login);
        boards.drop(login);
        return changed();
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
