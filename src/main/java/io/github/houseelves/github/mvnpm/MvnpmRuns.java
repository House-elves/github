package io.github.houseelves.github.mvnpm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.elf.Live;
import io.github.houseelves.github.elf.Log;

/**
 * Running mvnpm-dependabot from the board (instead of its morning timer), as the runner's mvnpm.run did for the
 * desk: systemd units on this machine (Linux only), which outlive the elf and keep that elf's settings and logs.
 * Which ones run is asked of systemd when the board is written; when the last one has finished, the next refresh
 * shows the elf's new verdicts. When the checks started is kept in state/mvnpm.json.
 */
public class MvnpmRuns {

    private static final Log LOG = Log.of(MvnpmRuns.class);

    static final String SERVICE = "mvnpm-dependabot.service";
    static final String PR_UNIT = "mvnpm-dependabot-pr-";

    /** How the elf runs a command (systemctl, systemd-run): its output, or an exception when it failed. */
    public interface Commands {
        String run(List<String> cmd) throws IOException;
    }

    final Commands commands;
    final Live live;
    final Path file;
    final Path home;
    final boolean linuxOnly;

    public MvnpmRuns(Path stateDir, Live live) {
        this(stateDir, live, MvnpmRuns::exec, Path.of(System.getProperty("user.home")), true);
    }

    public MvnpmRuns(Path stateDir, Live live, Commands commands, Path home, boolean linuxOnly) {
        this.file = stateDir.resolve("mvnpm.json");
        this.live = live;
        this.commands = commands;
        this.home = home;
        this.linuxOnly = linuxOnly;
    }

    /** @param running the systemd units checking right now; empty when idle */
    public record State(List<String> running, Instant since) {
    }

    record Saved(Instant since) {
    }

    /** What runs now: systemd's units, and since when the elf has seen them run. */
    public State state() {
        List<String> running = running();
        Saved saved = Json.read(file, new TypeReference<Saved>() {}, new Saved(null));
        Instant since = saved.since();
        if (running.isEmpty() && since != null) {
            since = null;
            Json.write(file, new Saved(null));
        } else if (!running.isEmpty() && since == null) {
            since = Instant.now();
            Json.write(file, new Saved(since));
        }
        return new State(running, since);
    }

    /** A full round over every repo ({@code repo} null), or one PR, which is re-checked even if already checked. */
    public void run(String repo, Integer pr) {
        if (repo != null && !repo.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Bad repo");
        if ((repo == null) != (pr == null || pr <= 0)) throw new IllegalArgumentException("Give both repo and pr, or neither");
        if (linuxOnly && !System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            throw new IllegalStateException("mvnpm checks run on Linux only for now");
        }
        List<String> running = running();
        if (!running.isEmpty() && (repo == null || running.contains(SERVICE))) {
            throw new IllegalStateException("mvnpm-dependabot is already running; wait for it to finish");
        }
        List<String> cmd;
        boolean ownUnit = Files.isRegularFile(home.resolve(".config/systemd/user/" + SERVICE));
        if (repo == null && ownUnit) {
            cmd = List.of("systemctl", "--user", "start", "--no-block", SERVICE);
        } else {
            boolean round = repo == null;
            cmd = new ArrayList<>(List.of("systemd-run", "--user",
                    "--unit=" + (round ? SERVICE : PR_UNIT + repo.replace('/', '-') + "-" + pr),
                    "--description=mvnpm-dependabot " + (round ? "round" : "check of " + repo + "#" + pr), "--property=Nice=10",
                    "--property=TimeoutStartSec=4h", "--setenv=PATH=" + System.getenv("PATH"), "--collect"));
            cmd.addAll(mvnpmCommand());
            cmd.addAll(round ? List.of("--once") : List.of("--repo", repo, "--pr", String.valueOf(pr)));
        }
        try {
            commands.run(cmd);
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't start the check: " + e.getMessage());
        }
        LOG.info("Started mvnpm-dependabot " + (repo == null ? "round" : repo + "#" + pr));
        if (Json.read(file, new TypeReference<Saved>() {}, new Saved(null)).since() == null) Json.write(file, new Saved(Instant.now()));
        live.boardUpdated();
    }

    /** Active mvnpm-dependabot units: the elf's service and one-off PR checks. */
    List<String> running() {
        try {
            String out = commands.run(List.of("systemctl", "--user", "list-units", "--state=activating,active",
                    "--no-legend", "--plain", SERVICE, PR_UNIT + "*"));
            List<String> units = new ArrayList<>();
            for (String line : out.split("\n")) {
                String unit = line.strip().split("\\s+")[0];
                if (unit.endsWith(".service")) units.add(unit);
            }
            return units;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * How to run mvnpm-dependabot: as the runner installed it (jbang with its main, from its elf.yaml), or the
     * command on the PATH from before it was an elf.
     */
    List<String> mvnpmCommand() {
        String data = System.getenv("HOUSE_ELVES_DATA");
        Path dir = (data == null || data.isBlank() ? home.resolve(".local/share/house-elves") : Path.of(data))
                .resolve("elves/mvnpm-dependabot");
        try {
            for (String line : Files.readAllLines(dir.resolve("elf.yaml"))) {
                if (line.matches("main:\\s*[A-Za-z0-9_]{1,80}\\.java\\s*")) {
                    return List.of(jbang(), "--quiet", dir.resolve(line.substring(5).strip()).toString());
                }
            }
        } catch (IOException ignored) {
            // not installed as an elf
        }
        return List.of("mvnpm-dependabot");
    }

    private String jbang() {
        Path local = home.resolve(".jbang/bin/jbang");
        return Files.isExecutable(local) ? local.toString() : "jbang";
    }

    static String exec(List<String> cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IOException(out.length() > 300 ? out.substring(0, 300) : out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        return out;
    }
}
