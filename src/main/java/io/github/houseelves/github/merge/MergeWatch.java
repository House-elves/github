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

    /** False for "Merge without approval": you merged without an approval, so a missing approval doesn't stop it. */
    public boolean requireApproval = true;

    /** Why it ended, in words, once it has. */
    public String outcome;

    public String login;

    public Instant created = Instant.now();

    public Instant checked;

    public Instant finished;
}
