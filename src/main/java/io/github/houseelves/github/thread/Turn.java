package io.github.houseelves.github.thread;

import java.time.Instant;

/** One message in a thread: yours, the agent's, or a system note. */
public class Turn {

    public static final String YOU = "you", AGENT = "agent", SYSTEM = "system";

    public Long id;

    public Long threadId;

    public String role;

    public String action;

    public String text;

    public Instant created = Instant.now();

    /** The agent's answers: the job that wrote it (its files, see {@link JobFile}). */
    public Long jobId;
}
