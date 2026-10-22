package io.github.houseelves.github.elf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The GitHub account the elf works as: GITHUB_ACCOUNT, one of the accounts {@code gh} is logged in to on this
 * machine. Its token comes from {@code gh auth token -u <account>}, read fresh for each run, held in memory only,
 * never written or logged.
 */
public class GhAccount {

    private final String login;
    private String token;

    public GhAccount(String login) {
        if (login == null || !login.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")) {
            throw new IllegalStateException("Set the GitHub account in the GitHub elf's settings");
        }
        this.login = login;
    }

    public String login() {
        return login;
    }

    public synchronized String token() {
        if (token == null) token = gh("auth", "token", "-u", login);
        return token;
    }

    private static String gh(String... args) {
        List<String> cmd = new ArrayList<>(List.of("gh"));
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            // A GH_TOKEN in the environment would win over the account we ask for.
            pb.environment().remove("GH_TOKEN");
            pb.environment().remove("GITHUB_TOKEN");
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isEmpty() || out.contains(" ")) {
                throw new IllegalStateException("gh has no login for " + args[args.length - 1]
                        + ". Run: gh auth login (as that account)");
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("gh isn't installed or isn't on the PATH");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while asking gh for a token");
        }
    }
}
