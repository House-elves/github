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
import io.github.houseelves.github.mvnpm.MvnpmElf;
import io.github.houseelves.github.mvnpm.MvnpmRuns;
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
    public final MvnpmElf mvnpm;
    public final MvnpmRuns mvnpmRuns;
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

    /** The real thing: GitHub with the account's token, mvnpm-dependabot as it is on this machine. */
    public static Elf real(Home home, GhAccount account) {
        Supplier<String> token = account::token;
        return new Elf(home, account.login(), token, new GitHub(new HttpGitHubApi()), new GitHubWriter(account.login(), token),
                MvnpmElf.onThisMachine(Json.MAPPER), null);
    }

    /**
     * @param mvnpmCommands how mvnpm-dependabot's checks are started and listed; null: systemd on this machine
     */
    public Elf(Home home, String login, Supplier<String> token, GitHub github, GitHubWriter writer, MvnpmElf mvnpm,
            MvnpmRuns.Commands mvnpmCommands) {
        this.home = home;
        this.login = login;
        this.github = github;
        this.writer = writer;
        this.mvnpm = mvnpm;
        this.store = new ThreadStore(home.state());
        this.scopes = new ScopeService(home.state());
        this.settings = new Settings(home.state());
        this.ignores = new IgnoreService(home.state(), store);
        this.mvnpmRuns = mvnpmCommands == null ? new MvnpmRuns(home.state(), live)
                : new MvnpmRuns(home.state(), live, mvnpmCommands, home.dir(), false);
        this.agents = new Agents(home.agents());
        this.boards = new BoardService(github, mvnpm, scopes, settings, live, ignores, home.board());
        this.threads = new ThreadService(github, store, agents, live);
        this.poster = new Poster(writer, github, store, live);
        this.merges = new MergeService(home.state(), github, writer, store, live);
        this.ci = new CiService(github, writer, threads, store, live);
        this.conflicts = new ConflictService(github, writer, threads, store, live);
        this.api = new BoardApi(login, token, boards, scopes, settings, mvnpm, this.mvnpmRuns, threads, poster, merges, ci,
                github, conflicts, ignores, live);
    }
}
