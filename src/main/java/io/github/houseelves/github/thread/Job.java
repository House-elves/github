package io.github.houseelves.github.thread;

import java.math.BigDecimal;
import java.time.Instant;

/** One agent run, which the runner does for the elf (see {@link io.github.houseelves.github.agent.Agents}). */
public class Job {

    public static final String QUEUED = "queued", RUNNING = "running", DONE = "done", FAILED = "failed",
            CANCELLED = "cancelled";

    public Long id;

    /** The runner's name for it: its report is agents/{agent}.json. */
    public String agent;

    public Long threadId;

    public String action;

    /** The full prompt sent to the agent, kept for the record. */
    public String prompt;

    /** JSON telling the runner what to check out: {repo, number, kind, base}. */
    public String checkout;

    /** Text from outsiders that the runner triages before the agent sees it; null when none. */
    public String untrusted;

    public String state = QUEUED;

    public String error;

    /** A self-review only: how many fixes it found worth making. */
    public Integer fixes;

    public BigDecimal costUsd;

    public Instant created = Instant.now();

    public Instant started;

    public Instant finished;

    public boolean open() {
        return state.equals(QUEUED) || state.equals(RUNNING);
    }
}
