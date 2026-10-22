package io.github.houseelves.github.board;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.merge.MergeService;
import io.github.houseelves.github.thread.ThreadView;

/**
 * Everything the board page renders, in display order.
 *
 * @param lanes     the four work lanes: review, mine, assigned, involved
 * @param hidden    open items you're involved in from repos outside the scope
 * @param ignored   items you chose to ignore (someone else is handling them), whatever lane they'd be in
 * @param truncated GitHub had more results than one search can return, so some items are missing
 * @param threads   the desk's threads by item key, so rows can show working / draft ready
 * @param refreshing this came from a stale cache and newer data is on its way (pushed as desk.board)
 * @param merges    merges: in progress, and recently finished, by item key
 * @param returned  ignored items that came back in the last day, with why
 * @param linkedPrs open PRs that fix or mention an issue, by the issue's key
 * @param reviewedAt others' PRs you reviewed, with when you last did
 * @param feedback  your PRs: what reviewers said last, by item key
 * @param rebasing  Dependabot PRs you asked to rebase, by item key
 * @param flows     where each item is in its flow and whose move it is, by item key (see {@link FlowClassifier})
 */
public record Board(
        String login,
        Instant fetchedAt,
        int refreshMinutes,
        boolean scopeEmpty,
        boolean truncated,
        List<Lane> lanes,
        List<Item> hidden,
        List<Item> ignored,
        Map<String, ThreadView.Summary> threads,
        boolean runnerConnected,
        boolean refreshing,
        Map<String, MergeService.View> merges,
        Map<String, String> returned,
        Map<String, String> sinceReview,
        Map<String, List<GitHub.LinkedPr>> linkedPrs,
        Map<String, Instant> reviewedAt,
        Map<String, Flow> flows,
        Map<String, GitHub.Feedback> feedback,
        Map<String, Rebase> rebasing) {

    /** The same board with the desk's own state (threads, merges, runner) added. */
    public Board with(Map<String, ThreadView.Summary> threads, Map<String, MergeService.View> merges,
            boolean runnerConnected) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, sinceReview, linkedPrs, reviewedAt, flows, feedback, rebasing);
    }

    /** Adds which PRs you reviewed moved on since ("New commits since your review"). */
    public Board sinceReview(Map<String, String> since) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, since, linkedPrs, reviewedAt, flows, feedback, rebasing);
    }

    /** Adds why recently un-ignored items came back ("Review requested", "You were mentioned"). */
    public Board returned(Map<String, String> returned) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, sinceReview, linkedPrs, reviewedAt, flows, feedback, rebasing);
    }

    /** Adds when you last reviewed each of others' PRs. */
    public Board reviewedAt(Map<String, Instant> at) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, sinceReview, linkedPrs, at, flows, feedback, rebasing);
    }

    /** Adds what reviewers said last on your PRs. */
    public Board feedback(Map<String, GitHub.Feedback> said) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, sinceReview, linkedPrs, reviewedAt, flows, said, rebasing);
    }

    /** Adds the Dependabot PRs you asked to rebase. */
    public Board rebasing(Map<String, Rebase> asked) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, sinceReview, linkedPrs, reviewedAt, flows, feedback,
                asked);
    }

    /**
     * You commented "@dependabot rebase".
     *
     * @param sha the PR's head when you asked: once the head moves, Dependabot has done it
     */
    public record Rebase(String sha, Instant at) {
    }

    /** Works out every item's flow from everything else on the board. Call last. */
    public Board withFlows(Instant now) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, sinceReview, linkedPrs, reviewedAt,
                FlowClassifier.flows(this, now), feedback, rebasing);
    }

    /** Your move, but put aside for later: under Later instead (its steps stay, and taking one brings it back). */
    public Board later(java.util.Set<String> keys) {
        if (flows == null || keys.isEmpty()) return this;
        Map<String, Flow> out = new java.util.LinkedHashMap<>(flows);
        out.replaceAll((key, f) -> keys.contains(key) && Flow.YOU.equals(f.waiting())
                ? f.with(Flow.LATER, f.since(), f.say(), f.next(), f.more()) : f);
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, sinceReview, linkedPrs, reviewedAt, out, feedback, rebasing);
    }

    /** Adds the open PRs that fix or mention each issue. */
    public Board linkedPrs(Map<String, List<GitHub.LinkedPr>> links) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, sinceReview, links, reviewedAt, flows, feedback, rebasing);
    }

    /** Marks a board served from a stale cache while fresh data is being fetched. */
    public Board refreshing(boolean refreshing) {
        return new Board(login, fetchedAt, refreshMinutes, scopeEmpty, truncated, lanes, hidden, ignored, threads,
                runnerConnected, refreshing, merges, returned, sinceReview, linkedPrs, reviewedAt, flows, feedback, rebasing);
    }

    public record Lane(String id, String title, List<Item> items) {
    }
}
