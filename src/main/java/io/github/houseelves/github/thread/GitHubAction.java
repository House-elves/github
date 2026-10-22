package io.github.houseelves.github.thread;

import java.time.Instant;

/** Audit row for one write to GitHub, in state/actions.json. Never updated or deleted. */
public class GitHubAction {

    public Long id;

    public Long threadId;

    public Long draftId;

    public String kind;

    /** What was sent, as JSON. */
    public String request;

    public String resultUrl;

    public String login;

    public Instant at = Instant.now();
}
