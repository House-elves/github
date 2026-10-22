package io.github.houseelves.github;

import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.houseelves.github.elf.Elf;
import io.github.houseelves.github.elf.Home;
import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.GitHubWriter;
import io.github.houseelves.github.mvnpm.MvnpmElf;

/**
 * The elf in a temp directory, with a fake GitHub (what the desk's tests mocked with @InjectMock): GitHub's reads
 * and writes are Mockito mocks, mvnpm-dependabot is the test copy of its config and state, and systemd answers
 * nothing.
 */
public final class TestElf {

    public static final String LOGIN = "phillip-kruger";

    private TestElf() {
    }

    public static Elf create(Path dir) {
        return create(dir, mock(GitHub.class), mock(GitHubWriter.class));
    }

    public static Elf create(Path dir, GitHub github, GitHubWriter writer) {
        MvnpmElf mvnpm = new MvnpmElf(Path.of("src/test/resources/mvnpm/config"), Path.of("src/test/resources/mvnpm"),
                Json.MAPPER);
        return new Elf(new Home(dir), LOGIN, () -> "gho_test", github, writer, mvnpm, cmd -> "");
    }

    /** Every job, oldest first: what the desk's tests read from the job table. */
    public static java.util.List<io.github.houseelves.github.thread.Job> jobs(Elf elf) {
        return elf.store.threads().stream().flatMap(t -> elf.store.jobs(t.id).stream())
                .sorted(java.util.Comparator.comparing(j -> j.id)).toList();
    }

    /** The runner's report on an agent, as it writes it in agents/{id}.json. */
    public static void report(Elf elf, String agent, String json) {
        try {
            Files.createDirectories(elf.agents.dir());
            Files.writeString(elf.agents.dir().resolve(agent + ".json"), json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A file the agent left, where the runner keeps it: agents/{id}--{name}. */
    public static void agentFile(Elf elf, String agent, String name, byte[] data) {
        try {
            Files.createDirectories(elf.agents.dir());
            Files.write(elf.agents.dir().resolve(agent + "--" + name), data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode json(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
