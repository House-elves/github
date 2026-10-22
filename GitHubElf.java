///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2
//DEPS com.h2database:h2:2.4.240
//SOURCES src/main/java/**/*.java

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.houseelves.github.api.Actions;
import io.github.houseelves.github.board.Board;
import io.github.houseelves.github.elf.Config;
import io.github.houseelves.github.elf.Elf;
import io.github.houseelves.github.elf.ElfLock;
import io.github.houseelves.github.elf.GhAccount;
import io.github.houseelves.github.elf.Home;
import io.github.houseelves.github.elf.Import;
import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.elf.Views;
import io.github.houseelves.github.github.Item;

/**
 * The GitHub elf: Elf Desk's GitHub board, run by the House Elves runner. It works as the gh account in
 * GITHUB_ACCOUNT (~/.config/github/config), keeps the board and its threads in ~/.config/github/, and draws the
 * desk's GitHub tab from them.
 *
 * <pre>
 * GitHubElf.java --refresh         fetch GitHub, take in ended agents, merge what's green; write the board
 * GitHubElf.java --dry-run         fetch GitHub and print what the board would show; write nothing
 * GitHubElf.java --action=NAME     one of the tab's actions, its values as JSON on stdin; prints its answer
 * GitHubElf.java --import=DB       move the desk's data in from its H2 file (desk.mv.db)
 * </pre>
 */
public class GitHubElf {

    static final String USAGE = """
            The GitHub elf: Elf Desk's GitHub board, as an elf.

              --refresh         fetch GitHub, take in ended agents, merge what's green; write the board
              --dry-run         fetch GitHub and print what the board would show; write nothing
              --action=NAME     one of the tab's actions, its values as JSON on stdin; prints its answer as JSON
              --import=DB       move the desk's data in from its H2 file (~/.local/share/house-elves/desk.mv.db)
              --help            this

            It works as the gh account GITHUB_ACCOUNT in ~/.config/github/config, and keeps its files next to it.""";

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        String action = null, importFrom = null;
        boolean refresh = false, dryRun = false;
        for (String a : args) {
            if (a.equals("--help") || a.equals("-h")) {
                System.out.println(USAGE);
                return 0;
            } else if (a.equals("--refresh")) {
                refresh = true;
            } else if (a.equals("--dry-run")) {
                dryRun = true;
            } else if (a.startsWith("--action=")) {
                action = a.substring("--action=".length());
            } else if (a.startsWith("--import=")) {
                importFrom = a.substring("--import=".length());
            } else {
                System.err.println("Unknown argument " + a + "\n\n" + USAGE);
                return 2;
            }
        }
        if (action == null && importFrom == null && !refresh && !dryRun) {
            System.out.println(USAGE);
            return 0;
        }
        Home home = Home.user();
        try {
            if (importFrom != null) return importDesk(home, Path.of(importFrom));
            GhAccount account = new GhAccount(Config.load(home.config()).githubAccount());
            if (dryRun) return dryRun(Elf.real(home, account), account);
            if (refresh) return refresh(home, account);
            return action(home, account, action);
        } catch (Exception e) {
            if (System.getenv("GITHUB_ELF_DEBUG") != null) e.printStackTrace();
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            System.err.println(message.replace('\n', ' '));
            return 1;
        }
    }

    /** One action: its values on stdin, its answer as the last line on stdout. */
    static int action(Home home, GhAccount account, String name) throws Exception {
        String stdin = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        JsonNode params = Json.MAPPER.readTree(stdin.isBlank() ? "{}" : stdin);
        if (Actions.READS.contains(name)) {
            Elf elf = Elf.real(home, account);
            print(new Actions(elf, account::token).run(name, params));
            return 0;
        }
        JsonNode answer;
        try (ElfLock lock = ElfLock.take(home.state())) {
            Elf elf = Elf.real(home, account);
            answer = new Actions(elf, account::token).run(name, params);
            Views.write(elf);
        }
        print(answer);
        return 0;
    }

    static int refresh(Home home, GhAccount account) throws Exception {
        Board b;
        try (ElfLock lock = ElfLock.take(home.state())) {
            Elf elf = Elf.real(home, account);
            b = elf.api.refresh();
            Views.writeAll(elf);
        }
        System.out.println("Refreshed the board for " + account.login() + ": " + count(b) + " items"
                + (b.truncated() ? " (GitHub had more than one search returns)" : "") + ".");
        return 0;
    }

    static int dryRun(Elf elf, GhAccount account) {
        Board b = elf.boards.preview(elf.login, account.token());
        StringBuilder sb = new StringBuilder("The board for " + elf.login + " as GitHub has it now:\n");
        for (Board.Lane lane : b.lanes()) {
            sb.append("\n").append(lane.title()).append(" (").append(lane.items().size()).append(")\n");
            for (Item i : lane.items()) sb.append("  ").append(i.key()).append("  ").append(i.title()).append('\n');
        }
        sb.append("\nhidden: ").append(b.hidden().size())
                .append(", ignored: ").append(b.ignored().size()).append(b.scopeEmpty() ? " (nothing watched yet)" : "")
                .append("\n\nDry run: nothing was written.");
        System.out.println(sb);
        return 0;
    }

    /** The desk's data, moved in; then the tab's files written from it. */
    static int importDesk(Home home, Path db) throws Exception {
        String login = Config.load(home.config()).githubAccount();
        try (ElfLock lock = ElfLock.take(home.state())) {
            Import.Summary s = Import.run(home, db);
            Elf elf = new Elf(home, login, () -> {
                throw new IllegalStateException("The import doesn't ask GitHub");
            }, null, null);
            Views.writeAll(elf);
            System.out.println(s);
        }
        return 0;
    }

    private static int count(Board b) {
        return b.lanes().stream().mapToInt(l -> l.items().size()).sum();
    }

    private static void print(JsonNode answer) throws Exception {
        System.out.println(Json.MAPPER.writeValueAsString(answer));
    }
}
