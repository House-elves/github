package io.github.houseelves.github.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.github.TestElf;
import io.github.houseelves.github.elf.Elf;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.GitHubWriter;

class AssignTest {

    @TempDir
    Path dir;

    GitHubWriter writer = mock(GitHubWriter.class);
    Elf elf;
    Poster poster;

    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        elf = TestElf.create(dir, mock(GitHub.class), writer);
        poster = elf.poster;
    }

    @Test
    void assignsYouAndRecordsIt() throws Exception {
        when(writer.assign("quarkusio/quarkus", 501, "phillip-kruger")).thenReturn(mapper.readTree("""
                {"html_url": "https://github.com/quarkusio/quarkus/issues/501", "assignees": [{"login": "phillip-kruger"}]}"""));
        poster.assign("quarkusio/quarkus#501", "phillip-kruger");
        GitHubAction a = TestElf.create(dir).store.actions().get(0);
        assertEquals("assign", a.kind);
    }

    @Test
    void gitHubIgnoringTheAssigneeIsAnError() throws Exception {
        // No write access: GitHub answers 201 but leaves you out.
        when(writer.assign("someone/thing", 7, "phillip-kruger")).thenReturn(mapper.readTree("{\"assignees\": []}"));
        var e = assertThrows(IllegalStateException.class, () -> poster.assign("someone/thing#7", "phillip-kruger"));
        assertTrue(e.getMessage().contains("write access"));
        assertEquals(0, elf.store.actions().size());
    }
}
