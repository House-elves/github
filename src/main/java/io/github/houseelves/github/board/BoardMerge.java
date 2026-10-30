package io.github.houseelves.github.board;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.houseelves.github.github.Item;

/**
 * One board from each account's: every item once, as the account it's most yours as (your own PR before one you
 * review, before an issue assigned to you, before one you're only involved in), the first account winning a tie. That
 * account is the item's owner: what's done with it is done as that account.
 */
public final class BoardMerge {

    private static final Comparator<Item> NEWEST_FIRST = Comparator.comparing(Item::updatedAt,
            Comparator.nullsLast(Comparator.reverseOrder()));

    /** Lanes in the order an item is most yours; then hidden, then ignored. */
    private static final List<String> RANK = List.of(BoardClassifier.MINE, BoardClassifier.REVIEW,
            BoardClassifier.ASSIGNED, BoardClassifier.INVOLVED);

    private BoardMerge() {
    }

    /** One account's board. */
    public record Part(String login, Board board) {
    }

    /**
     * @param owners item key to the account it belongs to
     * @param found  item key to every account that found it, its owner first
     */
    public record Merged(Board board, Map<String, String> owners, Map<String, List<String>> found) {
    }

    public static Merged merge(List<Part> parts) {
        if (parts.isEmpty()) throw new IllegalArgumentException("No board to show");
        // Where each account has each item, by rank: 0..3 the lanes, 4 hidden, 5 ignored.
        Map<String, String> owners = new LinkedHashMap<>();
        Map<String, Integer> best = new LinkedHashMap<>();
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (Part p : parts) {
            for (Board.Lane lane : p.board().lanes()) {
                for (Item i : lane.items()) see(p.login(), i.key(), RANK.indexOf(lane.id()), owners, best, found);
            }
            for (Item i : p.board().hidden()) see(p.login(), i.key(), 4, owners, best, found);
            for (Item i : p.board().ignored()) see(p.login(), i.key(), 5, owners, best, found);
        }
        found.replaceAll((key, logins) -> {
            List<String> ordered = new ArrayList<>(logins);
            ordered.remove(owners.get(key));
            ordered.addFirst(owners.get(key));
            return List.copyOf(ordered);
        });
        if (parts.size() == 1) return new Merged(parts.getFirst().board(), owners, found);

        Map<String, Board> byLogin = new LinkedHashMap<>();
        parts.forEach(p -> byLogin.put(p.login(), p.board()));
        Function<String, Board> ownerOf = key -> byLogin.get(owners.get(key));

        Board first = parts.getFirst().board();
        List<Board.Lane> lanes = new ArrayList<>();
        for (Board.Lane lane : first.lanes()) {
            List<Item> items = new ArrayList<>();
            for (Part p : parts) {
                p.board().lanes().stream().filter(l -> l.id().equals(lane.id())).flatMap(l -> l.items().stream())
                        .filter(i -> owned(p, i, owners)).forEach(items::add);
            }
            items.sort(NEWEST_FIRST);
            lanes.add(new Board.Lane(lane.id(), lane.title(), List.copyOf(items)));
        }
        List<Item> hidden = owned(parts, Board::hidden, owners);
        List<Item> ignored = owned(parts, Board::ignored, owners);

        Instant fetchedAt = parts.stream().map(p -> p.board().fetchedAt()).filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(first.fetchedAt());
        boolean truncated = parts.stream().anyMatch(p -> p.board().truncated());
        boolean refreshing = parts.stream().anyMatch(p -> p.board().refreshing());

        Board merged = new Board(first.login(), fetchedAt, first.refreshMinutes(), first.scopeEmpty(), truncated, lanes,
                hidden, ignored, first.threads(), first.runnerConnected(), refreshing, first.merges(),
                byOwner(owners, ownerOf, Board::returned), byOwner(owners, ownerOf, Board::sinceReview),
                byOwner(owners, ownerOf, Board::linkedPrs), byOwner(owners, ownerOf, Board::reviewedAt),
                byOwner(owners, ownerOf, Board::flows), byOwner(owners, ownerOf, Board::feedback),
                byOwner(owners, ownerOf, Board::rebasing));
        return new Merged(merged, owners, found);
    }

    private static void see(String login, String key, int rank, Map<String, String> owners, Map<String, Integer> best,
            Map<String, List<String>> found) {
        List<String> logins = found.computeIfAbsent(key, k -> new ArrayList<>());
        if (!logins.contains(login)) logins.add(login);
        Integer was = best.get(key);
        if (was == null || rank < was) {
            best.put(key, rank);
            owners.put(key, login);
        }
    }

    private static boolean owned(Part p, Item i, Map<String, String> owners) {
        return p.login().equals(owners.get(i.key()));
    }

    private static List<Item> owned(List<Part> parts, Function<Board, List<Item>> list, Map<String, String> owners) {
        List<Item> out = new ArrayList<>();
        for (Part p : parts) list.apply(p.board()).stream().filter(i -> owned(p, i, owners)).forEach(out::add);
        out.sort(NEWEST_FIRST);
        return List.copyOf(out);
    }

    /**
     * A per-item map, each entry from its owner's board. Items only one board has (an issue's linked PRs, say,
     * keyed by the issue) come from whichever board has them.
     */
    private static <V> Map<String, V> byOwner(Map<String, String> owners, Function<String, Board> ownerOf,
            Function<Board, Map<String, V>> map) {
        Map<String, V> out = new LinkedHashMap<>();
        for (String key : owners.keySet()) {
            Map<String, V> m = map.apply(ownerOf.apply(key));
            if (m != null && m.containsKey(key)) out.put(key, m.get(key));
        }
        return out;
    }
}
