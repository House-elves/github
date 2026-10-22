package io.github.houseelves.github.board;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.merge.MergeService;
import io.github.houseelves.github.thread.ThreadView;

/**
 * Works out each item's {@link Flow}: the stage it's at, whose move it is, and the next step. Pure, like
 * {@link BoardClassifier}. In order:
 * <ol>
 * <li>a merge the desk runs: merged (done), waiting for green (CI), or stopped (you);</li>
 * <li>the agent: working (agent), or a draft, a new answer or a failed run to look at (you);</li>
 * <li>otherwise GitHub's state, by flow: your PR, a PR to review, an assigned issue, or just involved.</li>
 * </ol>
 */
public final class FlowClassifier {

    static final List<String> MINE_STEPS = List.of("Self-review", "CI", "Reviews", "Merge");
    static final List<String> REVIEW_STEPS = List.of("Review", "Author", "CI", "Merge");
    static final List<String> ISSUE_STEPS = List.of("Investigate", "Fix", "PR");

    private static final Pattern DEPENDABOT = Pattern.compile("^(app/)?dependabot(\\[bot])?$");

    private FlowClassifier() {
    }

    /** Everything the rules look at, for one item. */
    record Ctx(String login, ThreadView.Summary thread, MergeService.View merge, String sinceReview, Instant reviewedAt,
            List<GitHub.LinkedPr> links, Instant now, GitHub.Feedback feedback, Instant rebaseAsked) {

        Ctx(String login, ThreadView.Summary thread, MergeService.View merge, String sinceReview, Instant reviewedAt,
                List<GitHub.LinkedPr> links, Instant now) {
            this(login, thread, merge, sinceReview, reviewedAt, links, now, null, null);
        }

        Ctx(String login, ThreadView.Summary thread, MergeService.View merge, String sinceReview, Instant reviewedAt,
                List<GitHub.LinkedPr> links, Instant now, GitHub.Feedback feedback) {
            this(login, thread, merge, sinceReview, reviewedAt, links, now, feedback, null);
        }
    }

    public static Map<String, Flow> flows(Board b, Instant now) {
        Map<String, Flow> out = new LinkedHashMap<>();
        for (Board.Lane lane : b.lanes()) {
            for (Item i : lane.items()) out.put(i.key(), flow(lane.id(), i, ctx(b, i, now)));
        }
        return out;
    }

    private static Ctx ctx(Board b, Item i, Instant now) {
        return new Ctx(b.login(), get(b.threads(), i.key()), get(b.merges(), i.key()), get(b.sinceReview(), i.key()),
                get(b.reviewedAt(), i.key()), Objects.requireNonNullElse(get(b.linkedPrs(), i.key()), List.of()),
                now, get(b.feedback(), i.key()), rebaseAsked(b.rebasing(), i, now));
    }

    /** You asked Dependabot to rebase this head commit within the last hour: it hasn't pushed yet. */
    static Instant rebaseAsked(Map<String, Board.Rebase> asked, Item i, Instant now) {
        Board.Rebase r = get(asked, i.key());
        if (r == null || r.sha() == null || !r.sha().equals(i.headSha()) || r.at().isBefore(now.minus(REBASE_WAIT))) return null;
        return r.at();
    }

    /** How long a rebase you asked Dependabot for counts as on its way. */
    static final java.time.Duration REBASE_WAIT = java.time.Duration.ofHours(1);

    private static <T> T get(Map<String, T> m, String key) {
        return m == null ? null : m.get(key);
    }

    static Flow flow(String laneId, Item i, Ctx c) {
        String role = switch (laneId) {
            case BoardClassifier.MINE -> "mine";
            case BoardClassifier.REVIEW -> "review";
            case BoardClassifier.ASSIGNED -> i.isPr() ? "review" : "issue";
            // Just involved, until you start something on it with the agent: then it's yours, an issue to fix or a
            // PR to review, and stays in its flow after the agent's done.
            default -> c.thread() == null ? "involved" : i.isPr() ? "review" : "issue";
        };
        Flow base = switch (role) {
            case "mine" -> mine(i, c);
            case "review" -> review(i, c);
            case "issue" -> issue(i, c);
            default -> involved(i, c);
        };
        return merge(agent(rebasing(base, c), c), c);
    }

    /** You asked Dependabot to rebase: wait for it rather than ask again. */
    private static Flow rebasing(Flow f, Ctx c) {
        if (c.rebaseAsked() == null) return f;
        List<Flow.Next> more = f.more().stream().filter(n -> n.id().equals("close")).toList();
        return f.with(Flow.OTHERS, c.rebaseAsked(), "You asked Dependabot to rebase it. Waiting for its push.", null, more);
    }

    // ---- the desk's own state, over GitHub's ----

    /** A merge the desk runs decides first: it's what you last asked for. */
    private static Flow merge(Flow f, Ctx c) {
        MergeService.View m = c.merge();
        if (m == null || f.steps().isEmpty()) return f;
        Flow last = f.at(f.steps().size() - 1);
        return switch (m.state()) {
            case "merged" -> last.with(Flow.DONE, m.finished(), "Merged.", null, List.of());
            case "waiting" -> last.with(Flow.CI, m.created(), "Merges once CI is green.", new Flow.Next("merge", "Merge"), List.of());
            case "failed" -> last.with(Flow.YOU, m.finished(), "The merge stopped: " + m.outcome(), new Flow.Next("merge", "Merge"),
                    f.more());
            default -> f;
        };
    }

    /** The agent: working on it, or something it made for you to read. */
    private static Flow agent(Flow f, Ctx c) {
        ThreadView.Summary t = c.thread();
        if (t == null) return f;
        List<Flow.Next> after = new ArrayList<>();
        // Your self-review found fixes: making them is the next step, until they're made or you review again.
        boolean fixes = f.role().equals("mine") && t.reviewFixes() > 0;
        if (fixes) after.add(new Flow.Next("self-fix", "Fix the review items"));
        if (f.next() != null && !f.next().id().equals("open")) after.add(f.next());
        after.addAll(f.more());
        Flow at = agentStep(f, t.action());
        return switch (t.state()) {
            case "working" -> at.with(Flow.AGENT, t.since(), "The agent is " + doing(t.action()) + ".", new Flow.Next("open", "Watch"),
                    List.of());
            case "draft" -> at.with(Flow.YOU, t.since(), drafted(t.draftKind()), new Flow.Next("open", readDraft(t.draftKind())),
                    after);
            case "answered" -> at.with(Flow.YOU, t.since(), "The agent " + did(t.action()) + (fixes && t.action().equals("review")
                    ? ": " + (t.reviewFixes() == 1 ? "1 fix" : t.reviewFixes() + " fixes") + " worth making" : "") + ".",
                    new Flow.Next("open", "Read answer"), after);
            // The agent stopped looking into CI that is running again (you re-ran it): what CI says now counts.
            default -> t.failed() && !(t.action() != null && t.action().startsWith("ci-") && Flow.CI.equals(f.waiting()))
                    ? at.with(Flow.YOU, t.since(), "The agent stopped before it finished " + doing(t.action()) + ".",
                            new Flow.Next("open", "See what happened"), after)
                    : fixes ? f.with(f.waiting(), f.since(), f.say(), f.next(), after) : f;
        };
    }

    private static Flow agentStep(Flow f, String action) {
        if (action == null) return f;
        int step = switch (f.role()) {
            case "mine" -> action.equals("review") || action.equals("self-fix") ? 0 : action.startsWith("ci-") || action.equals("conflicts") ? 1
                    : action.equals("address") ? 2 : -1;
            case "review" -> action.equals("review") ? 0 : action.startsWith("ci-") ? 2 : -1;
            case "issue" -> action.equals("investigate") ? 0 : action.equals("fix") ? 1 : -1;
            default -> -1;
        };
        return step < 0 ? f : f.at(step);
    }

    private static String doing(String action) {
        return switch (action == null ? "" : action) {
            case "review" -> "reviewing";
            case "investigate" -> "investigating";
            case "fix" -> "working on a fix";
            case "ci-why" -> "looking into the failing CI";
            case "ci-fix" -> "fixing the failing CI";
            case "conflicts" -> "resolving the conflicts";
            case "address" -> "addressing the review";
            case "self-fix" -> "fixing the review items";
            default -> "answering";
        };
    }

    private static String did(String action) {
        return switch (action == null ? "" : action) {
            case "review" -> "reviewed it";
            case "self-fix" -> "worked on the review items";
            case "investigate" -> "investigated it";
            case "ci-why" -> "looked into the failing CI";
            default -> "answered";
        };
    }

    private static String drafted(String kind) {
        return switch (kind == null ? "" : kind) {
            case "review" -> "A review is drafted.";
            case "push" -> "Commits are ready to push.";
            case "pr" -> "A fix is ready to open as a PR.";
            case "issue" -> "A new issue is drafted.";
            default -> "A comment is drafted.";
        };
    }

    private static String readDraft(String kind) {
        return switch (kind == null ? "" : kind) {
            case "review" -> "Read the review";
            case "push" -> "Check and push";
            case "pr" -> "Check and open PR";
            default -> "Read the draft";
        };
    }

    // ---- GitHub's state, per flow ----

    /** Your own PR: self-review, CI green, others' reviews, merge. Self-review fits at every stage, CI running included. */
    static Flow mine(Item i, Ctx c) {
        Flow f = mineState(i, c);
        Flow.Next self = new Flow.Next("review", "Self-review");
        if (f.next() != null && f.next().id().equals("review") || f.more().stream().anyMatch(n -> n.id().equals("review"))) return f;
        List<Flow.Next> more = new ArrayList<>(f.more());
        more.add(self);
        return f.with(f.waiting(), f.since(), f.say(), f.next(), List.copyOf(more));
    }

    private static Flow mineState(Item i, Ctx c) {
        String last = c.thread() == null ? null : c.thread().action();
        if (Boolean.TRUE.equals(i.draft())) {
            return flow("mine", MINE_STEPS, 0, Flow.PARKED, i.updatedAt(), "A draft PR. Mark it ready for review when you're happy with it.",
                    new Flow.Next("ready", "Ready for review"), List.of(new Flow.Next("review", "Self-review")));
        }
        Flow problem = ciProblem("mine", MINE_STEPS, 1, i, last, true);
        if (problem != null) return problem;
        GitHub.Feedback said = c.feedback();
        if (said != null && said.afterYou() && !"APPROVED".equals(said.state())) {
            String what = switch (said.state()) {
                case "CHANGES_REQUESTED" -> " asked for changes";
                case "COMMENT" -> " commented";
                default -> " reviewed";
            };
            return flow("mine", MINE_STEPS, 2, Flow.YOU, said.at(), said.who() + what + openThreads(said) + ".",
                    new Flow.Next("read-review", "Read the review"), List.of(new Flow.Next("address", "Address the review")));
        }
        if (pending(i, c)) return flow("mine", MINE_STEPS, 1, Flow.CI, i.updatedAt(), "CI is running.", null, List.of());
        if (ciStarting(i, c)) return flow("mine", MINE_STEPS, 1, Flow.CI, i.updatedAt(), "Pushed. Waiting for CI to start.", null, List.of());
        if (said != null && !said.reReview().isEmpty()) {
            // You pushed since their review: they see it once you ask them to review again.
            List<String> who = said.reReview();
            String names = who.size() == 1 ? who.get(0) : String.join(", ", who.subList(0, who.size() - 1)) + " and " + who.get(who.size() - 1);
            return flow("mine", MINE_STEPS, 2, Flow.YOU, i.updatedAt(), "You pushed since " + names + " reviewed" + openThreads(said) + ".",
                    new Flow.Next("re-request", who.size() == 1 ? "Ask " + who.get(0) + " to review again" : "Ask them to review again",
                            String.join(",", who)),
                    List.of(new Flow.Next("read-review", "Read the review"), new Flow.Next("whats-new", "What's new")));
        }
        if ("CHANGES_REQUESTED".equals(i.reviewDecision()) && said != null && !said.requested().isEmpty()) {
            // Changes asked for, you pushed and asked them again: theirs now.
            return flow("mine", MINE_STEPS, 2, Flow.OTHERS, i.updatedAt(), "Waiting for " + String.join(", ", said.requested())
                    + " to review again" + waited(i, c) + openThreads(said) + ".", null,
                    List.of(new Flow.Next("whats-new", "What's new")));
        }
        if ("CHANGES_REQUESTED".equals(i.reviewDecision())) {
            return flow("mine", MINE_STEPS, 2, Flow.YOU, i.updatedAt(), "A reviewer asked for changes" + openThreads(said) + ".",
                    new Flow.Next("read-review", "Read the review"), List.of(new Flow.Next("address", "Address the review")));
        }
        if (mergeable(i)) {
            return flow("mine", MINE_STEPS, 3, Flow.YOU, i.updatedAt(), "Approved and green.", new Flow.Next("merge", "Merge"), List.of());
        }
        if (green(i)) {
            return flow("mine", MINE_STEPS, 2, Flow.OTHERS, i.updatedAt(), "Green. Waiting for a review" + waited(i, c) + ".",
                    null, List.of(new Flow.Next("merge-anyway", "Merge without approval"), new Flow.Next("whats-new", "What's new")));
        }
        return flow("mine", MINE_STEPS, 2, Flow.OTHERS, i.updatedAt(), "Waiting on reviewers" + waited(i, c) + openThreads(said) + ".", null,
                said != null && said.openThreads() > 0
                        ? List.of(new Flow.Next("read-review", "Read the review"), new Flow.Next("address", "Address the review"))
                        : List.of(new Flow.Next("whats-new", "What's new")));
    }

    /** ", 2 threads open" (none: ""). */
    static String openThreads(GitHub.Feedback said) {
        if (said == null || said.openThreads() == 0) return "";
        return said.openThreads() == 1 ? ", 1 thread open" : ", " + said.openThreads() + " threads open";
    }

    /** Someone else's PR: review it, wait for the author, then CI and merge. */
    static Flow review(Item i, Ctx c) {
        String last = c.thread() == null ? null : c.thread().action();
        // A review you posted from here counts at once, before GitHub's next fetch says so.
        Instant reviewedAt = c.reviewedAt();
        ThreadView.Summary t = c.thread();
        if (t != null && "review".equals(t.postedKind()) && t.postedAt() != null
                && (reviewedAt == null || t.postedAt().isAfter(reviewedAt))) {
            reviewedAt = t.postedAt();
        }
        boolean reviewed = reviewedAt != null;
        if (reviewed && c.sinceReview() != null) {
            boolean commits = c.sinceReview().startsWith("New commits");
            return flow("review", REVIEW_STEPS, 0, Flow.YOU, i.updatedAt(), c.sinceReview() + ".",
                    commits ? new Flow.Next("review", "Review again") : new Flow.Next("whats-new", "What's new"),
                    commits ? List.of(new Flow.Next("whats-new", "What's new")) : List.of(new Flow.Next("review", "Review again")));
        }
        if (!reviewed && !Boolean.TRUE.equals(i.approved())) {
            List<Flow.Next> more = new ArrayList<>();
            if (GitHub.AWAITING_APPROVAL.equals(i.ci())) more.add(new Flow.Next("ci-approve", "Approve CI run"));
            return flow("review", REVIEW_STEPS, 0, Flow.YOU, i.updatedAt(),
                    Boolean.TRUE.equals(i.draft()) ? "A draft PR, not reviewed yet." : "Not reviewed yet.",
                    new Flow.Next("review", "Review"), more);
        }
        if (!Boolean.TRUE.equals(i.approved())) {
            return flow("review", REVIEW_STEPS, 1, Flow.OTHERS, reviewedAt != null ? reviewedAt : i.updatedAt(),
                    "Reviewed. Waiting on the author" + waited(i, c) + ".", null,
                    List.of(new Flow.Next("whats-new", "What's new"), new Flow.Next("review", "Review again")));
        }
        Flow problem = ciProblem("review", REVIEW_STEPS, 2, i, last, false);
        if (problem != null) return problem;
        if (pending(i, c)) return flow("review", REVIEW_STEPS, 2, Flow.CI, i.updatedAt(), "Approved. CI is running.", null, List.of());
        if (mergeable(i)) {
            return flow("review", REVIEW_STEPS, 3, Flow.YOU, i.updatedAt(), "Approved and green.", new Flow.Next("merge", "Merge"), List.of());
        }
        return flow("review", REVIEW_STEPS, 3, Flow.OTHERS, i.updatedAt(), "Approved. Waiting on other required reviews or checks.",
                null, List.of(new Flow.Next("whats-new", "What's new")));
    }

    /** An issue assigned to you: investigate, fix, open the PR (which then has its own row). */
    static Flow issue(Item i, Ctx c) {
        ThreadView.Summary t = c.thread();
        GitHub.LinkedPr fix = c.links().stream().filter(GitHub.LinkedPr::fixes).findFirst().orElse(null);
        if (fix != null) {
            boolean mine = c.login() != null && c.login().equalsIgnoreCase(fix.author());
            return mine
                    ? flow("issue", ISSUE_STEPS, 2, Flow.OTHERS, i.updatedAt(), "Your PR " + ref(i, fix.key()) + " fixes it.",
                            new Flow.Next("go", "Open " + ref(i, fix.key()), fix.key()), List.of())
                    : flow("issue", ISSUE_STEPS, 2, Flow.YOU, i.updatedAt(), fix.author() + "'s PR " + ref(i, fix.key()) + " fixes it.",
                            new Flow.Next("go", "Review " + ref(i, fix.key()), fix.key()), List.of(new Flow.Next("ignore", "Ignore the issue")));
        }
        if (t == null) {
            return flow("issue", ISSUE_STEPS, 0, Flow.YOU, i.updatedAt(), "Not looked at yet.", new Flow.Next("investigate", "Investigate"),
                    List.of(new Flow.Next("fix", "Fix it")));
        }
        if ("pr".equals(t.postedKind())) {
            return flow("issue", ISSUE_STEPS, 2, Flow.OTHERS, t.postedAt(), "You opened a PR for it.", null,
                    List.of(new Flow.Next("open", "Open")));
        }
        if ("fix".equals(t.action())) {
            return flow("issue", ISSUE_STEPS, 1, Flow.YOU, i.updatedAt(), "The fix was set aside.", new Flow.Next("fix", "Fix it again"),
                    List.of(new Flow.Next("open", "Open")));
        }
        return flow("issue", ISSUE_STEPS, 1, Flow.YOU, i.updatedAt(), "Investigated.", new Flow.Next("fix", "Fix it"),
                List.of(new Flow.Next("investigate", "Investigate again"), new Flow.Next("open", "Open")));
    }

    /** Mentioned or otherwise involved: no flow of its own; the agent can still look. */
    static Flow involved(Item i, Ctx c) {
        Flow.Next look = i.isPr() ? new Flow.Next("review", "Review") : new Flow.Next("investigate", "Investigate");
        return flow("involved", List.of(), 0, Flow.WATCHING, i.updatedAt(), "You're involved.", null, List.of(look));
    }

    /**
     * Re-running failed jobs needs write access to the repo. Without it, your own PR's CI runs again when you close
     * and reopen it; on someone else's there's nothing to offer.
     */
    static Flow.Next rerun(Item i, boolean own) {
        if (!Boolean.FALSE.equals(i.canWrite())) return new Flow.Next("ci-rerun", "Re-run failed jobs");
        return own ? new Flow.Next("ci-reopen", "Re-run CI (close and reopen)") : null;
    }

    private static List<Flow.Next> present(Flow.Next... next) {
        return java.util.Arrays.stream(next).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * Failing CI, CI waiting for approval, conflicts, or behind its base: what to do about it, or null. On your own
     * PR the agent can fix it; on others' PRs it explains (for a comment) and the author fixes it.
     */
    private static Flow ciProblem(String role, List<String> steps, int step, Item i, String last, boolean own) {
        if (ciFailing(i)) {
            boolean explained = "ci-why".equals(last);
            Flow.Next why = new Flow.Next("ci-why", "Why did CI fail?");
            Flow.Next fix = new Flow.Next("ci-fix", "Fix CI");
            Flow.Next rerun = rerun(i, own);
            if (own) return flow(role, steps, step, Flow.YOU, i.updatedAt(), "CI is failing.", explained ? fix : why,
                    explained ? present(rerun, why) : present(fix, rerun));
            return flow(role, steps, step, Flow.YOU, i.updatedAt(), "Approved, but CI is failing.", why, present(rerun));
        }
        if (GitHub.AWAITING_APPROVAL.equals(i.ci())) {
            return flow(role, steps, step, Flow.YOU, i.updatedAt(), "CI waits for a maintainer to approve the run.",
                    new Flow.Next("ci-approve", "Approve CI run"), List.of());
        }
        if (hasConflicts(i)) {
            if (own || isDependabot(i)) {
                return flow(role, steps, step, Flow.YOU, i.updatedAt(), "Conflicts with its base.",
                        isDependabot(i) ? new Flow.Next("dependabot-rebase", "Ask Dependabot to rebase")
                                : new Flow.Next("conflicts", "Resolve conflicts"), List.of());
            }
            return flow(role, steps, step, Flow.OTHERS, i.updatedAt(), "Conflicts with its base. Waiting on the author.", null,
                    List.of(new Flow.Next("conflicts", "Resolve conflicts")));
        }
        if (behind(i)) {
            return flow(role, steps, step, Flow.YOU, i.updatedAt(), "Behind its base.",
                    isDependabot(i) ? new Flow.Next("dependabot-rebase", "Ask Dependabot to rebase")
                            : new Flow.Next("update-branch", "Update branch"), List.of());
        }
        return null;
    }

    private static Flow flow(String role, List<String> steps, int step, String waiting, Instant since, String say, Flow.Next next,
            List<Flow.Next> more) {
        return new Flow(role, steps, step, waiting, since, say, next, more);
    }

    /** " for 3 days", once it has been a day. */
    private static String waited(Item i, Ctx c) {
        Instant from = c.reviewedAt() != null && c.reviewedAt().isAfter(Objects.requireNonNullElse(i.updatedAt(), Instant.EPOCH))
                ? c.reviewedAt() : i.updatedAt();
        if (from == null || c.now() == null) return "";
        long days = Duration.between(from, c.now()).toDays();
        return days < 1 ? "" : days == 1 ? " for a day" : " for " + days + " days";
    }

    /** "#123" in the same repo, else "owner/repo#123". */
    private static String ref(Item i, String key) {
        return key.startsWith(i.repo() + "#") ? key.substring(i.repo().length()) : key;
    }

    static boolean ciFailing(Item i) {
        return "FAILURE".equals(i.ci()) || "ERROR".equals(i.ci());
    }

    static boolean pending(Item i, Ctx c) {
        return "PENDING".equals(i.ci()) || "EXPECTED".equals(i.ci())
                // Green so far, but required checks haven't all run (often: the real CI hasn't started yet). Only
                // just after a change: later, a green PR GitHub still blocks waits for something else, such as an
                // approval a ruleset asks for without saying so in reviewDecision.
                || "SUCCESS".equals(i.ci()) && "BLOCKED".equals(i.mergeState()) && !Boolean.TRUE.equals(i.draft())
                        && !"REVIEW_REQUIRED".equals(i.reviewDecision()) && !"CHANGES_REQUESTED".equals(i.reviewDecision())
                        && i.updatedAt() != null && i.updatedAt().isAfter(c.now().minus(java.time.Duration.ofMinutes(30)));
    }

    /**
     * No checks on the head commit yet, but it was pushed moments ago: GitHub takes a few minutes to queue the runs,
     * so this is CI about to start, not a PR without CI. Recent means the desk pushed it in the last half hour, or
     * the PR changed in the last quarter hour.
     */
    static boolean ciStarting(Item i, Ctx c) {
        if (i.ci() != null || !i.isPr()) return false;
        ThreadView.Summary t = c.thread();
        boolean pushedHere = t != null && "push".equals(t.postedKind()) && t.postedAt() != null
                && t.postedAt().isAfter(c.now().minus(java.time.Duration.ofMinutes(30)));
        boolean changed = i.updatedAt() != null && i.updatedAt().isAfter(c.now().minus(java.time.Duration.ofMinutes(15)));
        return pushedHere || changed;
    }

    static boolean green(Item i) {
        return "SUCCESS".equals(i.ci()) && !Boolean.TRUE.equals(i.draft()) && Item.requiredChecksPassed(i.mergeState());
    }

    static boolean mergeable(Item i) {
        return green(i) && Boolean.TRUE.equals(i.approved());
    }

    static boolean hasConflicts(Item i) {
        return "CONFLICTING".equals(i.mergeable());
    }

    static boolean behind(Item i) {
        return "BEHIND".equals(i.mergeState()) && !hasConflicts(i);
    }

    static boolean isDependabot(Item i) {
        return i.author() != null && DEPENDABOT.matcher(i.author()).matches();
    }
}
