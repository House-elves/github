package io.github.houseelves.github.api;

import static io.github.houseelves.github.Items.issue;
import static io.github.houseelves.github.Items.pr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.houseelves.github.TestElf;
import io.github.houseelves.github.board.Board;
import io.github.houseelves.github.board.BoardClassifier;
import io.github.houseelves.github.board.BoardService;
import io.github.houseelves.github.elf.Elf;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.GitHubWriter;
import io.github.houseelves.github.github.Item;

/** The board half of the desk's DeskApiTest: the same board, scope and settings, from the elf's files. */
class BoardApiTest {

    @TempDir
    Path dir;

    GitHub github = mock(GitHub.class);
    BoardApi api;

    @BeforeEach
    void setUp() {
        api = TestElf.create(dir, github, mock(GitHubWriter.class)).api;
        when(github.search(eq("gho_test"), contains("involves:@me"), anyInt())).thenReturn(new GitHub.SearchResult(
                List.of(pr("quarkusio/quarkus", 2, "phillip-kruger"), issue("quarkusio/quarkus", 3, "x", "phillip-kruger"),
                        issue("phillip-kruger/qwaak", 4, "phillip-kruger")), 3));
        when(github.search(eq("gho_test"), contains("review-requested:@me"), anyInt())).thenReturn(
                new GitHub.SearchResult(List.of(pr("smallrye/smallrye-open-api", 1, "someone")), 1));
        when(github.search(eq("gho_test"), contains("reviewed-by:@me"), anyInt())).thenReturn(
                new GitHub.SearchResult(List.of(), 0));
        when(github.viewerOrgs("gho_test")).thenReturn(List.of("quarkusio", "smallrye", "eclipse"));
    }

    /** The next run of the elf, on the same files. */
    BoardApi again() {
        return TestElf.create(dir, github, mock(GitHubWriter.class)).api;
    }

    static List<String> lane(Board b, String id) {
        return b.lanes().stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow()
                .items().stream().map(Item::key).toList();
    }

    @Test
    void firstRunSuggestsOrgsThenTheBoardFollowsTheScope() {
        Board before = api.board(true);
        assertTrue(before.scopeEmpty());
        assertTrue(before.runnerConnected());
        assertEquals(4, before.hidden().size());

        List<BoardService.Suggestion> sug = again().suggestions();
        assertEquals("quarkusio", sug.get(0).owner());
        assertTrue(sug.get(0).preselect()); // member with items
        assertTrue(sug.stream().filter(s -> s.owner().equals("phillip-kruger")).allMatch(s -> !s.preselect()));
        assertTrue(sug.stream().filter(s -> s.owner().equals("eclipse")).allMatch(s -> s.items() == 0 && !s.preselect()));

        again().watchAll(List.of("quarkusio", "smallrye"));
        Board after = again().board(false);
        assertFalse(after.scopeEmpty());
        assertEquals(List.of("smallrye/smallrye-open-api#1"), lane(after, BoardClassifier.REVIEW));
        assertEquals(List.of("quarkusio/quarkus#2"), lane(after, BoardClassifier.MINE));
        assertEquals(List.of("quarkusio/quarkus#3"), lane(after, BoardClassifier.ASSIGNED));
        assertEquals(List.of("phillip-kruger/qwaak#4"), after.hidden().stream().map(Item::key).toList());
    }

    @Test
    void ignoreMovesAnItemAndStopIgnoringBringsItBack() {
        api.watchAll(List.of("quarkusio", "smallrye"));
        again().ignore("quarkusio/quarkus#2");
        Board b = again().board(true);
        assertTrue(again().isIgnored("quarkusio/quarkus#2"));
        assertEquals(List.of(), lane(b, BoardClassifier.MINE));
        assertEquals(List.of("quarkusio/quarkus#2"), b.ignored().stream().map(Item::key).toList());

        again().unignore("quarkusio/quarkus#2");
        assertEquals(List.of("quarkusio/quarkus#2"), lane(again().board(false), BoardClassifier.MINE));
    }

    @Test
    void settingsRoundTrip() {
        var s = api.watch("quarkusio");
        s = again().exclude("quarkusio/quarkus-quickstarts");
        assertEquals(2, s.scope().size());
        assertEquals("phillip-kruger", s.login());

        s = again().setRefreshMinutes(10);
        assertEquals(10, s.refreshMinutes());
        assertEquals(10, again().settings().refreshMinutes());
        assertThrows(IllegalArgumentException.class, () -> api.setRefreshMinutes(0));
    }
}
