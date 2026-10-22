package io.github.houseelves.github.board;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.mvnpm.MvnpmElf;
import io.github.houseelves.github.scope.Scope;

/**
 * Sorts fetched items into the board's lanes. Pure: no I/O, so the rules are
 * easy to test. Order of precedence, first match wins:
 * <ol>
 * <li>anything you chose to ignore goes to the ignored list;</li>
 * <li>a Dependabot mvnpm bump in a repo mvnpm-dependabot watches goes to the mvnpm lane, whatever the scope;</li>
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
            List<Item> reviewedByMe, List<Item> mvnpmCandidates, boolean truncated) {
    }

    public static Board classify(Fetched f, Scope scope, List<String> mvnpmRepos, boolean mvnpmInstalled,
            JsonNode mvnpmState, int refreshMinutes) {
        return classify(f, scope, mvnpmRepos, mvnpmInstalled, mvnpmState, refreshMinutes, Set.of());
    }

    public static Board classify(Fetched f, Scope scope, List<String> mvnpmRepos, boolean mvnpmInstalled,
            JsonNode mvnpmState, int refreshMinutes, Set<String> ignoredKeys) {
        List<Item> ignored = new ArrayList<>();
        Set<String> elfRepos = mvnpmRepos.stream().map(r -> r.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());

        List<Board.MvnpmRow> mvnpmRows = new ArrayList<>();
        Set<String> mvnpmKeys = new HashSet<>();
        for (Item item : dedupe(f.mvnpmCandidates()).values()) {
            if (!isElfHandled(item, elfRepos)) continue;
            if (ignoredKeys.contains(item.key())) {
                ignored.add(item);
                mvnpmKeys.add(item.key());
                continue;
            }
            mvnpmKeys.add(item.key());
            MvnpmElf.Check check = MvnpmElf.check(mvnpmState, item.repo(), item.number()).orElse(null);
            boolean current = check != null && check.sha() != null && check.sha().equals(item.headSha());
            mvnpmRows.add(new Board.MvnpmRow(item, check, current));
        }
        mvnpmRows.sort(Comparator.comparing(Board.MvnpmRow::item, NEWEST_FIRST));

        Set<String> reviewKeys = new HashSet<>();
        f.reviewRequested().forEach(i -> reviewKeys.add(i.key()));
        f.reviewedByMe().forEach(i -> reviewKeys.add(i.key()));
        Map<String, Item> all = dedupe(f.reviewRequested(), f.reviewedByMe(), f.involved());

        Map<String, List<Item>> lanes = new LinkedHashMap<>();
        for (String id : List.of(REVIEW, MINE, ASSIGNED, INVOLVED)) lanes.put(id, new ArrayList<>());
        List<Item> hidden = new ArrayList<>();

        for (Item item : all.values()) {
            if (mvnpmKeys.contains(item.key()) || isElfHandled(item, elfRepos)) continue;
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
                new Board.MvnpmLane(mvnpmInstalled, mvnpmRepos, mvnpmRows), hidden, ignored, Map.of(), false, false,
                Map.of(), Map.of(), null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static boolean isElfHandled(Item item, Set<String> elfRepos) {
        return elfRepos.contains(item.repo().toLowerCase(Locale.ROOT)) && MvnpmElf.isMvnpmBump(item);
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
