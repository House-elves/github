package io.github.houseelves.github.board;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.scope.Scope;

/**
 * Sorts fetched items into the board's lanes. Pure: no I/O, so the rules are
 * easy to test. Order of precedence, first match wins:
 * <ol>
 * <li>anything you chose to ignore goes to the ignored list;</li>
 * <li>anything outside the scope is hidden, review requests included;</li>
 * <li>your own PRs; then PRs you were asked to review or have reviewed (they stay until merged or closed, even
 * after you approve, which removes GitHub's review request); then issues assigned to you; then everything else
 * you're involved in.</li>
 * </ol>
 */
public final class BoardClassifier {

    public static final String REVIEW = "review";
    public static final String MINE = "mine";
    public static final String ASSIGNED = "assigned";
    public static final String INVOLVED = "involved";

    private static final Comparator<Item> NEWEST_FIRST = Comparator.comparing(Item::updatedAt,
            Comparator.nullsLast(Comparator.reverseOrder()));

    private BoardClassifier() {
    }

    /** What was fetched from GitHub, before any classification. */
    public record Fetched(String login, Instant at, List<Item> involved, List<Item> reviewRequested,
            List<Item> reviewedByMe, boolean truncated) {
    }

    public static Board classify(Fetched f, Scope scope, int refreshMinutes) {
        return classify(f, scope, refreshMinutes, Set.of());
    }

    public static Board classify(Fetched f, Scope scope, int refreshMinutes, Set<String> ignoredKeys) {
        List<Item> ignored = new ArrayList<>();
        Set<String> reviewKeys = new HashSet<>();
        f.reviewRequested().forEach(i -> reviewKeys.add(i.key()));
        f.reviewedByMe().forEach(i -> reviewKeys.add(i.key()));
        Map<String, Item> all = dedupe(f.reviewRequested(), f.reviewedByMe(), f.involved());

        Map<String, List<Item>> lanes = new LinkedHashMap<>();
        for (String id : List.of(REVIEW, MINE, ASSIGNED, INVOLVED)) lanes.put(id, new ArrayList<>());
        List<Item> hidden = new ArrayList<>();

        for (Item item : all.values()) {
            if (ignoredKeys.contains(item.key())) {
                ignored.add(item);
            } else if (!scope.covers(item.repo())) {
                hidden.add(item);
            } else if (item.isPr() && item.author().equalsIgnoreCase(f.login())) {
                lanes.get(MINE).add(item);
            } else if (reviewKeys.contains(item.key())) {
                lanes.get(REVIEW).add(item);
            } else if (!item.isPr() && item.assignees().stream().anyMatch(a -> a.equalsIgnoreCase(f.login()))) {
                lanes.get(ASSIGNED).add(item);
            } else {
                lanes.get(INVOLVED).add(item);
            }
        }
        lanes.values().forEach(l -> l.sort(NEWEST_FIRST));
        hidden.sort(NEWEST_FIRST);
        ignored.sort(NEWEST_FIRST);

        List<Board.Lane> laneList = List.of(
                new Board.Lane(REVIEW, "Needs my review", lanes.get(REVIEW)),
                new Board.Lane(MINE, "My PRs", lanes.get(MINE)),
                new Board.Lane(ASSIGNED, "Assigned issues", lanes.get(ASSIGNED)),
                new Board.Lane(INVOLVED, "Mentioned / involved", lanes.get(INVOLVED)));

        return new Board(f.login(), f.at(), refreshMinutes, scope.isEmpty(), f.truncated(), laneList,
                hidden, ignored, Map.of(), false, false,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    @SafeVarargs
    private static Map<String, Item> dedupe(List<Item>... lists) {
        Map<String, Item> byKey = new LinkedHashMap<>();
        for (List<Item> list : lists) {
            for (Item i : list) byKey.putIfAbsent(i.key(), i);
        }
        return byKey;
    }
}
