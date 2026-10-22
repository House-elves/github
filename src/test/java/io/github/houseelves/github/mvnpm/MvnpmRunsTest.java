package io.github.houseelves.github.mvnpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.houseelves.github.elf.Live;

/** Starting mvnpm-dependabot's checks as systemd units, the way the runner's mvnpm.run did: systemd is faked. */
class MvnpmRunsTest {

    @TempDir
    Path dir;

    final List<List<String>> ran = new ArrayList<>();
    String units = "";
    String refusal;
    Live live = new Live();
    MvnpmRuns runs;

    @BeforeEach
    void setUp() {
        runs = new MvnpmRuns(dir.resolve("state"), live, cmd -> {
            if (cmd.contains("list-units")) return units;
            ran.add(cmd);
            if (refusal != null) throw new IOException(refusal);
            return "";
        }, dir, false);
    }

    @Test
    void startsAFullRoundOrOnePrAsSystemdUnits() throws Exception {
        runs.run(null, null);
        runs.run("smallrye/smallrye-open-api", 2696);
        assertEquals(2, ran.size());
        List<String> round = ran.get(0), one = ran.get(1);
        assertEquals("systemd-run", round.get(0));
        assertTrue(round.contains("--unit=mvnpm-dependabot.service"));
        assertTrue(round.contains("--once"));
        assertTrue(one.contains("--unit=mvnpm-dependabot-pr-smallrye-smallrye-open-api-2696"));
        assertEquals(List.of("--repo", "smallrye/smallrye-open-api", "--pr", "2696"), one.subList(one.size() - 4, one.size()));
        assertTrue(live.board());

        assertThrows(IllegalArgumentException.class, () -> runs.run("smallrye/smallrye-open-api", null));
        assertThrows(IllegalArgumentException.class, () -> runs.run("not a repo; rm -rf", 1));
    }

    @Test
    void itsOwnServiceRunsTheRoundWhenItHasOne() throws Exception {
        Files.createDirectories(dir.resolve(".config/systemd/user"));
        Files.writeString(dir.resolve(".config/systemd/user/mvnpm-dependabot.service"), "[Service]");
        runs.run(null, null);
        assertEquals(List.of("systemctl", "--user", "start", "--no-block", "mvnpm-dependabot.service"), ran.get(0));
    }

    @Test
    void anInstalledElfRunsWithJbang() throws Exception {
        Path elf = dir.resolve(".local/share/house-elves/elves/mvnpm-dependabot");
        Files.createDirectories(elf);
        Files.writeString(elf.resolve("elf.yaml"), "name: mvnpm-dependabot\nmain: MvnpmDependabot.java\n");
        runs.run("quarkusio/quarkus", 1);
        assertTrue(ran.get(0).contains(elf.resolve("MvnpmDependabot.java").toString()));
        assertTrue(ran.get(0).contains("--quiet"));
    }

    @Test
    void aRunningRoundOrSystemdsRefusalIsShown() {
        units = "mvnpm-dependabot.service loaded active running mvnpm-dependabot\n";
        var busy = assertThrows(IllegalStateException.class, () -> runs.run(null, null));
        assertEquals("mvnpm-dependabot is already running; wait for it to finish", busy.getMessage());

        units = "";
        refusal = "Unit mvnpm-dependabot.service already exists.";
        var e = assertThrows(IllegalStateException.class, () -> runs.run(null, null));
        assertEquals("Couldn't start the check: Unit mvnpm-dependabot.service already exists.", e.getMessage());
    }

    @Test
    void theStateIsWhatSystemdRunsAndSinceWhen() {
        assertEquals(List.of(), runs.state().running());
        units = "mvnpm-dependabot-pr-quarkusio-quarkus-1.service loaded active running x\nother.socket loaded active\n";
        MvnpmRuns.State s = runs.state();
        assertEquals(List.of("mvnpm-dependabot-pr-quarkusio-quarkus-1.service"), s.running());
        assertNotNull(s.since());
        assertEquals(s.since(), runs.state().since()); // kept in state/mvnpm.json

        units = "";
        assertNull(runs.state().since());
    }
}
