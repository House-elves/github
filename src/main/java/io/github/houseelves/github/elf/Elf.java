package io.github.houseelves.github.elf;

import java.util.function.Supplier;

import io.github.houseelves.github.agent.Agents;
import io.github.houseelves.github.api.BoardApi;
import io.github.houseelves.github.board.BoardService;
import io.github.houseelves.github.ci.CiService;
import io.github.houseelves.github.conflicts.ConflictService;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.GitHubWriter;
import io.github.houseelves.github.github.HttpGitHubApi;
import io.github.houseelves.github.ignore.IgnoreService;
import io.github.houseelves.github.merge.MergeService;
import io.github.houseelves.github.scope.ScopeService;
import io.github.houseelves.github.settings.Settings;
import io.github.houseelves.github.thread.Poster;
import io.github.houseelves.github.thread.ThreadService;
import io.github.houseelves.github.thread.ThreadStore;

/** The elf put together for one run: what CDI did for the desk. */
public class Elf {

    public final Home home;
    public final String login;
    public final Live live = new Live();
    public final ThreadStore store;
    public final ScopeService scopes;
    public final Settings settings;
    public final IgnoreService ignores;
    public final io.github.houseelves.github.later.LaterService later;
    public final GitHub github;
    public final GitHubWriter writer;
    public final Agents agents;
    public final BoardService boards;
    public final ThreadService threads;
    public final Poster poster;
    public final MergeService merges;
    public final CiService ci;
    public final ConflictService conflicts;
    public final BoardApi api;

    /** The real thing: GitHub with the account's token. */
    public static Elf real(Home home, GhAccount account) {
        Supplier<String> token = account::token;
        return new Elf(home, account.login(), token, new GitHub(new HttpGitHubApi()), new GitHubWriter(account.login(), token));
    }

    public Elf(Home home, String login, Supplier<String> token, GitHub github, GitHubWriter writer) {
        this.home = home;
        this.login = login;
        this.github = github;
        this.writer = writer;
        this.store = new ThreadStore(home.state());
        this.scopes = new ScopeService(home.state());
        this.settings = new Settings(home.state());
        this.ignores = new IgnoreService(home.state(), store);
        this.later = new io.github.houseelves.github.later.LaterService(home.state());
        this.agents = new Agents(home.agents());
        this.boards = new BoardService(github, scopes, settings, live, ignores, home.board());
        this.threads = new ThreadService(github, store, agents, live);
        this.poster = new Poster(writer, github, store, live);
        this.merges = new MergeService(home.state(), github, writer, store, live);
        this.ci = new CiService(github, writer, threads, store, live);
        this.conflicts = new ConflictService(github, writer, threads, store, live);
        this.api = new BoardApi(login, token, boards, scopes, settings, threads, poster, merges, ci,
                github, conflicts, ignores, later, live);
    }
}
