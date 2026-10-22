package io.github.houseelves.github;

import java.time.Instant;
import java.util.List;

import io.github.houseelves.github.github.Item;

/** Test builders for {@link Item}. */
public final class Items {

    private Items() {
    }

    public static Item pr(String repo, int number, String author) {
        return pr(repo, number, author, "Some change", null, "sha" + number);
    }

    public static Item pr(String repo, int number, String author, String title, String headRef, String headSha) {
        return new Item(Item.key(repo, number), repo, number, Item.Kind.PR, title,
                "https://github.com/" + repo + "/pull/" + number, author, List.of(),
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-05T00:00:00Z").plusSeconds(number),
                0, false, headRef, headSha, "SUCCESS", null, "MERGEABLE", "CLEAN", false);
    }

    public static Item issue(String repo, int number, String author, String... assignees) {
        return new Item(Item.key(repo, number), repo, number, Item.Kind.ISSUE, "An issue",
                "https://github.com/" + repo + "/issues/" + number, author, List.of(assignees),
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-05T00:00:00Z").plusSeconds(number),
                2, null, null, null, null, null, null, null, null);
    }

    public static Item mvnpmBump(String repo, int number, String sha) {
        return pr(repo, number, "dependabot", "Bump org.mvnpm:lit from 3.3.1 to 3.3.3",
                "dependabot/maven/org.mvnpm-lit-3.3.3", sha);
    }
}
