package io.github.houseelves.github.api;

import static io.github.houseelves.github.Items.pr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.houseelves.github.TestElf;
import io.github.houseelves.github.elf.Accounts;
import io.github.houseelves.github.elf.Elf;
import io.github.houseelves.github.elf.Home;
import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.elf.Views;
import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.GitHubWriter;

/**
 * More than one gh account on the board: each one's items fetched with its own token, one board with every item
 * once, and whatever is done with an item done as the account it belongs to.
 */
class AccountsTest {

    static final String HOME = "home-me";
    /** Found by both: the home account opened it, so it's that account's (My PRs beats Mentioned). */
    static final String BOTH = "quarkusio/quarkus#1";
    static final String WORK_ONLY = "quarkusio/quarkus#2";
    static final String HOME_ONLY = "example-org/app#3";

    @TempDir
    Path dir;

    GitHub github = mock(GitHub.class);
    GitHubWriter writer = mock(GitHubWriter.class);
    List<String> tokensAskedFor = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(github.search(eq("tok-phillip-kruger"), contains("involves:@me"), anyInt())).thenReturn(new GitHub.SearchResult(
                List.of(pr("quarkusio/quarkus", 1, HOME), pr("quarkusio/quarkus", 2, "someone")), 2));
        when(github.search(eq("tok-home-me"), contains("involves:@me"), anyInt())).thenReturn(new GitHub.SearchResult(
                List.of(pr("quarkusio/quarkus", 1, HOME), pr("example-org/app", 3, HOME)), 2));
        when(github.search(anyString(), contains("review-requested:@me"), anyInt())).thenReturn(new GitHub.SearchResult(List.of(), 0));
        when(github.search(anyString(), contains("reviewed-by:@me"), anyInt())).thenReturn(new GitHub.SearchResult(List.of(), 0));
    }

    /** One run of the elf, as GitHubElf puts it together: GITHUB_ACCOUNT first, every token from "gh". */
    Elf elf() {
        Home home = new Home(dir);
        Accounts accounts = new Accounts(home.state(), TestElf.LOGIN, l -> {
            tokensAskedFor.add(l);
            return "tok-" + l;
        });
        return new Elf(home, accounts, github, writer);
    }

    JsonNode act(String action, String params) {
        Elf elf = elf();
        JsonNode answer = new Actions(elf, elf.accounts::token).run(action, TestElf.json(params));
        Views.write(elf);
        return answer;
    }

    /** The elf's refresh: each account read with its own token, then kept. */
    void refresh() {
        Elf elf = elf();
        elf.api.refresh(elf.accounts.logins().stream().map(l -> elf.boards.fetch(l, elf.accounts.token(l))).toList());
        Views.writeAll(elf);
    }

    JsonNode board() throws Exception {
        return Json.MAPPER.readTree(dir.resolve("board/board.json").toFile());
    }

    List<String> keys(JsonNode board, String lane) {
        List<String> out = new ArrayList<>();
        for (JsonNode l : board.path("lanes")) {
            if (l.path("id").asText().equals(lane)) l.path("items").forEach(i -> out.add(i.path("key").asText()));
        }
        return out;
    }

    @Test
    void oneBoardFromEveryAccountEachItemOnceAsTheAccountItsMostYoursAs() throws Exception {
        act("watch-all", "{\"owners\": \"quarkusio example-org\"}");
        JsonNode added = act("add-account", "{\"login\": \"home-me\", \"name\": \"Home\"}");
        assertEquals("phillip-kruger", added.path("accounts").path(0).path("login").asText());
        assertEquals("phillip-kruger", added.path("accounts").path(0).path("name").asText());
        assertEquals("Home", added.path("accounts").path(1).path("name").asText());
        assertTrue(tokensAskedFor.contains(HOME)); // only an account gh is logged in to goes in

        act("rename-account", "{\"login\": \"phillip-kruger\", \"name\": \"Work\"}");
        refresh();

        JsonNode b = board();
        assertEquals(List.of(HOME_ONLY, BOTH), keys(b, "mine").stream().sorted().toList());
        assertEquals(List.of(WORK_ONLY), keys(b, "involved"));
        assertEquals("Work", b.path("accounts").path(0).path("name").asText());
        assertEquals("Home", b.path("accounts").path(1).path("name").asText());
        assertEquals(List.of(HOME, "phillip-kruger"), Json.MAPPER.convertValue(b.path("found").path(BOTH), List.class));
        assertEquals(List.of("phillip-kruger"), Json.MAPPER.convertValue(b.path("found").path(WORK_ONLY), List.class));
        assertEquals(HOME, Json.MAPPER.readTree(dir.resolve("state/owners.json").toFile()).path(BOTH).asText());
        assertTrue(b.path("flows").has(HOME_ONLY));
    }

    @Test
    void whatsDoneWithAnItemIsDoneAsTheAccountItBelongsTo() {
        act("watch-all", "{\"owners\": \"quarkusio example-org\"}");
        act("add-account", "{\"login\": \"home-me\", \"name\": \"Home\"}");
        refresh();

        act("item", "{\"itemKey\": \"" + BOTH + "\"}");
        verify(github).item("tok-home-me", "quarkusio/quarkus", 1);
        act("item", "{\"itemKey\": \"" + WORK_ONLY + "\"}");
        verify(github).item("tok-phillip-kruger", "quarkusio/quarkus", 2);
        // Not on the board: as GITHUB_ACCOUNT.
        act("item", "{\"itemKey\": \"other/repo#9\"}");
        verify(github).item("tok-phillip-kruger", "other/repo", 9);

        when(writer.assign(anyString(), anyInt(), anyString())).thenReturn(TestElf.json("{\"assignees\": [{\"login\": \"home-me\"}]}"));
        act("assign-me", "{\"itemKey\": \"" + HOME_ONLY + "\", \"as\": \"home-me\"}");
        verify(writer).assign("example-org/app", 3, HOME);

        // The desk's bar named another account than the item's: nothing is done.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> act("close", "{\"itemKey\": \"" + HOME_ONLY + "\", \"as\": \"phillip-kruger\"}"));
        assertTrue(e.getMessage().contains("nothing was done"));
        verify(writer, never()).close(anyString(), anyInt());
    }

    @Test
    void anAccountTakenOffTakesItsItemsWithItButNotTheElfsOwn() throws Exception {
        act("watch-all", "{\"owners\": \"quarkusio example-org\"}");
        act("add-account", "{\"login\": \"home-me\", \"name\": \"Home\"}");
        refresh();
        assertTrue(keys(board(), "mine").contains(HOME_ONLY));

        JsonNode left = act("remove-account", "{\"login\": \"home-me\"}");
        assertEquals(1, left.path("accounts").size());
        JsonNode b = board();
        assertFalse(keys(b, "mine").contains(HOME_ONLY));
        assertEquals(1, b.path("accounts").size());

        assertThrows(IllegalArgumentException.class, () -> act("remove-account", "{\"login\": \"phillip-kruger\"}"));
        assertThrows(IllegalArgumentException.class, () -> act("add-account", "{\"login\": \"phillip-kruger\"}"));
        assertThrows(IllegalArgumentException.class, () -> act("add-account", "{\"login\": \"-nope\"}"));
    }

    @Test
    void anAccountGhIsntLoggedInToIsntAdded() {
        Home home = new Home(dir);
        Accounts accounts = new Accounts(home.state(), TestElf.LOGIN, l -> {
            throw new IllegalStateException("gh has no login for " + l + ". Run: gh auth login (as that account)");
        });
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> accounts.add("someone-else", "Other"));
        assertTrue(e.getMessage().contains("gh auth login"));
        assertEquals(1, accounts.all().size());
    }
}
