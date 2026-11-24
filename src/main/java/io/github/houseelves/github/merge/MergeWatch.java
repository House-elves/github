package io.github.houseelves.github.merge;

import java.time.Instant;

/** A PR to merge once CI is green, in state/merges.json (the desk's merge_watch table). */
public class MergeWatch {

    public static final String WAITING = "waiting", MERGED = "merged", FAILED = "failed", CANCELLED = "cancelled";

    public Long id;

    public String itemKey;

    /** merge, squash or rebase. */
    public String method;

    /** The head you saw when you clicked; the merge only goes through for this commit. */
    public String headSha;

    public String state = WAITING;

    /**
     * The PR's change when you clicked (a hash of its diff without line numbers, see MergeService.changeOf), kept when the desk
     * brought it up to date with its base for you: a new head with this same change is the rebase, and the merge
     * follows it. Null otherwise: then any new head stops the merge.
     */
    public String change;

    /**
     * A Dependabot PR (Dependabot was asked to rebase it): only Dependabot pushes to it, and its context lines move as
     * the other bumps merge, so {@link #change} is its added and removed lines only. Otherwise context counts too.
     */
    public boolean dependabot;

    /** When the desk last brought it up to date (rebased it, or asked Dependabot to), or null. */
    public Instant updated;

    /** The head it had then: it's brought up to date again only once that rebase landed and it fell behind again. */
    public String updatedFrom;

    /** False for "Merge without approval": you merged without an approval, so a missing approval doesn't stop it. */
    public boolean requireApproval = true;

    /** Why it ended, in words, once it has. */
    public String outcome;

    public String login;

    public Instant created = Instant.now();

    public Instant checked;

    public Instant finished;
}
