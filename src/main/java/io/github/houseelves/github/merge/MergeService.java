package io.github.houseelves.github.merge;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.elf.Live;
import io.github.houseelves.github.elf.Log;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.GitHubWriter;
import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.thread.DeskThread;
import io.github.houseelves.github.thread.GitHubAction;
import io.github.houseelves.github.thread.ThreadStore;
import io.github.houseelves.github.thread.Turn;

/**
 * Merge. Offered only for approved PRs with green CI, and merged straight away, pinned to the head commit you saw.
 * If GitHub is still working out mergeability, the request waits (checked by every refresh, see {@link #poll})
 * and the same rules apply: new commits, red CI, conflicts or a lost approval stop it. The watches are in
 * state/merges.json.
 */
public class MergeService {

    private static final Log LOG = Log.of(MergeService.class);

    final GitHub github;
    final GitHubWriter writer;
    final Live live;
    final ThreadStore store;
    final ObjectMapper mapper = Json.MAPPER;
    private final Path file;
    private List<MergeWatch> watches;

    public MergeService(Path stateDir, GitHub github, GitHubWriter writer, ThreadStore store, Live live) {
        this.file = stateDir.resolve("merges.json");
        this.github = github;
        this.writer = writer;
        this.store = store;
        this.live = live;
    }

    private synchronized List<MergeWatch> all() {
        if (watches == null) watches = new ArrayList<>(Json.read(file, new TypeReference<List<MergeWatch>>() {}, List.of()));
        return watches;
    }

    private void save() {
        Json.write(file, all());
    }

    private MergeWatch waitingFor(String itemKey) {
        return all().stream().filter(w -> w.itemKey.equals(itemKey) && w.state.equals(MergeWatch.WAITING)).findFirst().orElse(null);
    }

    /** The newest watch for a PR, whatever its state. */
    MergeWatch latestFor(String itemKey) {
        return all().stream().filter(w -> w.itemKey.equals(itemKey)).reduce((a, b) -> b).orElse(null);
    }

    private MergeWatch byId(long id) {
        return all().stream().filter(w -> w.id == id).findFirst().orElse(null);
    }

    /** Your options for one PR: what the repo allows, your usual method, and where CI stands. */
    public record Options(String itemKey, List<String> methods, String defaultMethod, String ci, String mergeable,
            String headSha, boolean open, boolean approved) {
    }

    /**
     * A watch as the page shows it.
     *
     * @param state waiting, merged, failed or cancelled
     */
    public record View(String itemKey, String method, String state, String outcome, Instant created, Instant finished) {
    }

    public Options options(String itemKey, String token) {
        GitHub.PullState pr = github.pullState(token, repo(itemKey), number(itemKey));
        return new Options(itemKey, pr.methods(), pr.defaultMethod(), pr.ci(), pr.mergeable(), pr.headSha(),
                pr.state().equals("OPEN"), pr.approved());
    }

    /**
     * Starts watching. {@code headSha} is the head shown to you when you confirmed; if the PR has moved on since,
     * this refuses rather than merge commits you haven't seen. Checks once straight away, so an already green PR
     * merges now.
     */
    public View request(String itemKey, String method, String headSha, String login, String token) {
        return request(itemKey, method, headSha, login, token, false);
    }

    /**
     * @param withoutApproval "Merge without approval": you know it's fine, so merge without an approval. CI must still be
     *                        green, and branch protection on GitHub may still refuse it.
     */
    public View request(String itemKey, String method, String headSha, String login, String token,
            boolean withoutApproval) {
        GitHub.PullState pr = github.pullState(token, repo(itemKey), number(itemKey));
        if (!pr.state().equals("OPEN")) throw new IllegalStateException(itemKey + " isn't open");
        if (pr.draft()) throw new IllegalStateException(itemKey + " is a draft PR");
        if (!pr.approved() && !withoutApproval) {
            throw new IllegalStateException("Merging is only for approved PRs, and " + itemKey + " isn't approved.");
        }
        boolean green = "SUCCESS".equals(pr.ci()) || (pr.ci() == null && Item.requiredChecksPassed(pr.mergeState()));
        if (!green) {
            throw new IllegalStateException("CI isn't green on " + itemKey
                    + ("FAILURE".equals(pr.ci()) || "ERROR".equals(pr.ci()) ? " (it's failing; re-run or fix it first)." : " yet."));
        }
        // GitHub's mergeStateStatus: CLEAN means every required check passed and nothing else blocks it. UNKNOWN
        // (still computing) is allowed through and settled by the next check round.
        if (!Item.requiredChecksPassed(pr.mergeState()) && !"UNKNOWN".equals(pr.mergeState())) {
            throw new IllegalStateException(switch (pr.mergeState()) {
                case "BEHIND" -> itemKey + " is behind its base branch, and the repo requires it to be up to date.";
                case "DIRTY" -> itemKey + " has merge conflicts.";
                default -> "GitHub doesn't consider " + itemKey + " mergeable yet: required checks haven't all passed"
                        + (withoutApproval && !pr.approved() ? ", or the repo requires an approval." : ".");
            });
        }
        if (!pr.methods().contains(method)) {
            throw new IllegalArgumentException(repo(itemKey) + " doesn't allow " + method + "; it allows " + pr.methods());
        }
        if (!pr.headSha().equals(headSha)) {
            throw new IllegalStateException("The PR got new commits while you were looking. Open it again to see them.");
        }
        if (waitingFor(itemKey) != null) {
            throw new IllegalStateException("A merge is already in progress for " + itemKey);
        }
        MergeWatch w = new MergeWatch();
        w.id = all().stream().mapToLong(x -> x.id).max().orElse(0) + 1;
        w.itemKey = itemKey;
        w.method = method;
        w.headSha = headSha;
        w.login = login;
        w.requireApproval = !withoutApproval;
        all().add(w);
        save();
        note(itemKey, withoutApproval && !pr.approved()
                ? "You asked to merge this without an approval (" + method + ")."
                : "You asked to merge this (" + method + ").");
        check(itemKey, pr);
        announce(itemKey);
        return view(itemKey);
    }

    public View cancel(String itemKey) {
        MergeWatch w = waitingFor(itemKey);
        if (w != null) end(w, MergeWatch.CANCELLED, "You cancelled the merge.");
        announce(itemKey);
        return view(itemKey);
    }

    public View view(String itemKey) {
        MergeWatch w = latestFor(itemKey);
        return w == null ? null : new View(w.itemKey, w.method, w.state, w.outcome, w.created, w.finished);
    }

    /** Waiting watches, plus ones that ended in the last day, for the board's chips. */
    public Map<String, View> recent() {
        Map<String, View> out = new HashMap<>();
        Instant since = Instant.now().minus(MergeDecision.GIVE_UP_AFTER);
        for (MergeWatch w : all()) {
            if (w.state.equals(MergeWatch.WAITING) || (w.finished != null && w.finished.isAfter(since))) {
                out.put(w.itemKey, new View(w.itemKey, w.method, w.state, w.outcome, w.created, w.finished));
            }
        }
        return out;
    }

    /** One round over the waiting watches: what the desk did every minute, the elf does on each refresh. */
    public void poll(String token) {
        List<MergeWatch> waiting = all().stream().filter(w -> w.state.equals(MergeWatch.WAITING)).toList();
        for (MergeWatch w : waiting) {
            try {
                check(w.itemKey, github.pullState(token, repo(w.itemKey), number(w.itemKey)));
            } catch (Exception e) {
                LOG.warnf("Merge on green: checking %s failed: %s", w.itemKey, e.getMessage());
            }
        }
    }

    /** One round for one PR: decide, and act. */
    void check(String itemKey, GitHub.PullState pr) {
        MergeWatch w = waitingFor(itemKey);
        if (w == null) return;
        w.checked = Instant.now();
        save();
        MergeDecision d = MergeDecision.decide(w.headSha, w.created, Instant.now(), pr, w.requireApproval);
        switch (d.step()) {
            case WAIT -> { }
            case DONE -> finish(w.id, pr.state().equals("MERGED") ? MergeWatch.MERGED : MergeWatch.CANCELLED, d.reason());
            case STOP -> finish(w.id, MergeWatch.FAILED, d.reason());
            case MERGE -> merge(w);
        }
    }

    private void merge(MergeWatch w) {
        try {
            var response = writer.merge(repo(w.itemKey), number(w.itemKey), w.method, w.headSha);
            GitHubAction a = new GitHubAction();
            DeskThread t = store.byKey(w.itemKey);
            a.threadId = t == null ? null : t.id;
            a.kind = "merge";
            a.request = "{\"item\":\"" + w.itemKey + "\",\"merge_method\":\"" + w.method + "\",\"sha\":\""
                    + w.headSha + "\"}";
            a.resultUrl = "https://github.com/" + repo(w.itemKey) + "/pull/" + number(w.itemKey);
            a.login = w.login;
            store.audit(a);
            String sha = response.path("sha").asText("");
            finish(w.id, MergeWatch.MERGED, "Merged" + (sha.isEmpty() ? "." : " (" + sha.substring(0, Math.min(7, sha.length())) + ")."));
        } catch (GitHubWriter.WriteFailed e) {
            if (e.status == 0) {
                // GitHub couldn't be reached: nothing was merged (or GitHub will tell us next round).
                LOG.warnf("Merge on green: couldn't merge %s yet: %s", w.itemKey, e.getMessage());
                return;
            }
            String why = gitHubMessage(e);
            finish(w.id, MergeWatch.FAILED, "GitHub refused the merge: " + why + (why.endsWith(".") ? "" : "."));
        }
    }

    private void finish(long watchId, String state, String outcome) {
        MergeWatch w = byId(watchId);
        if (w == null || !w.state.equals(MergeWatch.WAITING)) return;
        end(w, state, outcome);
        announce(w.itemKey);
    }

    private void end(MergeWatch w, String state, String outcome) {
        w.state = state;
        w.outcome = outcome;
        w.finished = Instant.now();
        save();
        note(w.itemKey, outcome);
    }

    /** A line in the PR's thread, if it has one. */
    private void note(String itemKey, String text) {
        DeskThread t = store.byKey(itemKey);
        if (t != null) store.addTurn(t.id, Turn.SYSTEM, null, text);
    }

    private void announce(String itemKey) {
        live.changed(itemKey);
        live.boardUpdated();
    }

    private String gitHubMessage(GitHubWriter.WriteFailed e) {
        try {
            return mapper.readTree(e.body).path("message").asText(e.getMessage());
        } catch (Exception ignored) {
            return e.getMessage();
        }
    }

    static String repo(String itemKey) {
        return itemKey.substring(0, itemKey.indexOf('#'));
    }

    static int number(String itemKey) {
        return Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
    }
}
