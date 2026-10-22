package io.github.houseelves.github.conflicts;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.github.agent.Prompts;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.ItemContext;
import io.github.houseelves.github.github.GitHubWriter;
import io.github.houseelves.github.elf.Live;
import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.thread.DeskThread;
import io.github.houseelves.github.thread.GitHubAction;
import io.github.houseelves.github.thread.ThreadService;
import io.github.houseelves.github.thread.ThreadStore;
import io.github.houseelves.github.thread.ThreadView;
import io.github.houseelves.github.thread.Turn;

/**
 * A PR with merge conflicts. Dependabot PRs: ask Dependabot to rebase (it regenerates the PR itself, and keeps
 * maintaining it, which it stops doing once someone else pushes to its branch). Other PRs you can push to: the
 * agent merges the base branch in and resolves the conflicts; its merge commit comes back as a push you confirm.
 */
public class ConflictService {

    static final String DEPENDABOT_REBASE = "@dependabot rebase";

    final GitHub github;
    final GitHubWriter writer;
    final ThreadService threads;
    final ThreadStore store;
    final Live live;
    final ObjectMapper mapper = Json.MAPPER;

    public ConflictService(GitHub github, GitHubWriter writer, ThreadService threads, ThreadStore store, Live live) {
        this.github = github;
        this.writer = writer;
        this.threads = threads;
        this.store = store;
        this.live = live;
    }

    /**
     * @param dependabot the PR is Dependabot's: offer "ask Dependabot to rebase"
     * @param canPush    the agent's merge commit could be pushed to the PR's branch
     */
    public record Options(String itemKey, boolean dependabot, boolean canPush, String headRepo, String headRef,
            String base) {
    }

    public Options options(String itemKey, String token) {
        ItemContext ctx = github.context(token, repo(itemKey), number(itemKey));
        GitHub.CiReport target = github.ci(token, repo(itemKey), number(itemKey));
        return new Options(itemKey, isDependabot(ctx.author()), target.canPush(), target.headRepo(), target.headRef(),
                ctx.baseRef());
    }

    /** Posts "@dependabot rebase" on the PR, as you. */
    public String askDependabotToRebase(String itemKey, String login, String token) {
        ItemContext ctx = github.context(token, repo(itemKey), number(itemKey));
        if (!isDependabot(ctx.author())) throw new IllegalStateException(itemKey + " isn't a Dependabot PR");
        var response = writer.createComment(repo(itemKey), number(itemKey), Map.of("body", DEPENDABOT_REBASE));
        String url = response.path("html_url").asText(ctx.url());
        {
            DeskThread t = store.byKey(itemKey);
            GitHubAction a = new GitHubAction();
            a.threadId = t == null ? null : t.id;
            a.kind = "comment";
            a.request = mapper.createObjectNode().put("item", itemKey).put("body", DEPENDABOT_REBASE).toString();
            a.resultUrl = url;
            a.login = login;
            store.audit(a);
            if (t != null) store.addTurn(t.id, Turn.SYSTEM, null, "You asked Dependabot to rebase this PR.");
        }
        live.changed(itemKey);
        return url;
    }

    /**
     * A PR behind its base (no conflicts): GitHub rebases the branch on its base, as you, pinned to the head GitHub reports now. Dependabot PRs get "@dependabot rebase" instead,
     * so Dependabot keeps maintaining them.
     *
     * @param method REBASE: merging the base in would add a merge commit, which these repos refuse
     */
    public String updateBranch(String itemKey, String method, String login, String token) {
        if (!method.equals("REBASE")) throw new IllegalArgumentException("Only REBASE: no merge commits");
        ItemContext ctx = github.context(token, repo(itemKey), number(itemKey));
        if (isDependabot(ctx.author())) {
            askDependabotToRebase(itemKey, login, token);
            return "rebase-requested";
        }
        GitHub.CiReport target = github.ci(token, repo(itemKey), number(itemKey));
        if (!target.canPush()) {
            throw new IllegalStateException("This PR's branch can't be updated from here: the author didn't allow edits "
                    + "from maintainers.");
        }
        try {
            writer.updateBranch(repo(itemKey), number(itemKey), target.headSha(), method);
        } catch (GitHubWriter.WriteFailed e) {
            String why = e.body == null ? e.getMessage() : message(e);
            throw new IllegalStateException("GitHub didn't update the branch: " + why);
        }
        {
            DeskThread t = store.byKey(itemKey);
            GitHubAction a = new GitHubAction();
            a.threadId = t == null ? null : t.id;
            a.kind = "update-branch";
            a.request = mapper.createObjectNode().put("item", itemKey).put("method", method)
                    .put("expectedHead", target.headSha()).toString();
            a.resultUrl = ctx.url();
            a.login = login;
            store.audit(a);
            if (t != null) {
                store.addTurn(t.id, Turn.SYSTEM, null, method.equals("REBASE")
                        ? "You rebased this PR on " + ctx.baseRef() + "."
                        : "You merged " + ctx.baseRef() + " into this PR.");
            }
        }
        live.changed(itemKey);
        return method.equals("REBASE") ? "rebased" : "merged";
    }

    private String message(GitHubWriter.WriteFailed e) {
        try {
            return mapper.readTree(e.body).path("message").asText(e.getMessage());
        } catch (Exception ignored) {
            return e.getMessage();
        }
    }

    /** The agent merges the base branch in and resolves the conflicts; its commit comes back as a push draft. */
    public ThreadView resolve(String itemKey, String login, String token) {
        GitHub.CiReport target = github.ci(token, repo(itemKey), number(itemKey));
        if (!target.canPush()) {
            throw new IllegalStateException("This PR's branch can't be pushed to from here: the author didn't allow "
                    + "edits from maintainers.");
        }
        ThreadView existing = threads.existing(itemKey, Prompts.CONFLICTS, target.headSha());
        if (existing != null) return existing;
        ObjectNode extras = mapper.createObjectNode().put("head", target.headSha());
        extras.putObject("push").put("headRepo", target.headRepo()).put("headRef", target.headRef())
                .put("headSha", target.headSha());
        return threads.start(itemKey, Prompts.CONFLICTS, null, login, token, null, extras);
    }

    /** How many commits the PR has: more than one gets "Squash into one commit". */
    public int commitCount(String itemKey, String token) {
        return github.commitCount(token, repo(itemKey), number(itemKey));
    }

    /**
     * Squash the PR into one commit on its base (the runner does it, no agent): what the runner squashes, the
     * action's {"squash": checkout} answer. Its answer comes back through squash-done as a push draft
     * ({@link ThreadService#squashDone}).
     */
    public com.fasterxml.jackson.databind.node.ObjectNode squash(String itemKey, String token) {
        GitHub.CiReport target = github.ci(token, repo(itemKey), number(itemKey));
        if (!target.canPush()) {
            throw new IllegalStateException("This PR's branch can't be pushed to from here: the author didn't allow "
                    + "edits from maintainers.");
        }
        ItemContext ctx = github.context(token, repo(itemKey), number(itemKey));
        return threads.squash(ctx, target.headRepo(), target.headRef());
    }

    static boolean isDependabot(String author) {
        return author.equals("dependabot") || author.equals("dependabot[bot]") || author.equals("app/dependabot");
    }

    static String repo(String itemKey) {
        return itemKey.substring(0, itemKey.indexOf('#'));
    }

    static int number(String itemKey) {
        return Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
    }
}
