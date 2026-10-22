package io.github.houseelves.github.agent;

import java.util.List;

/**
 * What every agent run returns, enforced with Claude Code's --json-schema.
 *
 * @param reply your answer in the thread, Markdown
 * @param draft something to post, or null when there's nothing to post (or the previous draft still stands)
 * @param fixes self-reviews only: how many fixes the reply lists as worth making
 */
public record AgentOutput(String reply, DraftOut draft, Integer fixes) {

    /**
     * @param kind     review (PRs only), comment, issue (a new issue in another public repo, such as upstream), or pr
     *                 (a fix for this issue: the PR's title and body for the commit the agent made)
     * @param event    reviews only: COMMENT, APPROVE or REQUEST_CHANGES
     * @param comments reviews only: inline comments on lines of the diff
     * @param repo     issues only: owner/name of the repo to open it in
     * @param title    issues and PRs
     * @param then     issues only, optional: a comment for this thread's item to draft once the issue is open, with
     *                 {@value #ISSUE_LINK} where the new issue's link goes
     */
    public record DraftOut(String kind, String event, String body, List<InlineComment> comments, String repo,
            String title, String then) {

        public static final String ISSUE_LINK = "{issue}";

        public DraftOut(String kind, String event, String body, List<InlineComment> comments) {
            this(kind, event, body, comments, null, null, null);
        }

        public List<InlineComment> comments() {
            return comments == null ? List.of() : comments;
        }
    }

    /** @param line a line number in the new version of the file, inside the PR's diff */
    public record InlineComment(String path, int line, String body) {
    }

    public static final String SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["reply", "draft"],
              "properties": {
                "reply": {"type": "string", "description": "Your answer to the maintainer, in Markdown. Shown in the thread, never posted."},
                "fixes": {"type": "integer", "minimum": 0, "description": "Self-review of the maintainer's own PR only: how many fixes worth making your reply lists (0 when none)."},
                "draft": {
                  "description": "Text to post on GitHub under the maintainer's name, or null.",
                  "anyOf": [
                    {"type": "null"},
                    {
                      "type": "object",
                      "additionalProperties": false,
                      "required": ["kind", "body"],
                      "properties": {
                        "kind": {"enum": ["review", "comment", "issue", "pr"]},
                        "event": {"enum": ["COMMENT", "APPROVE", "REQUEST_CHANGES"]},
                        "body": {"type": "string"},
                        "repo": {"type": "string", "description": "issue only: owner/name of the public repo to open it in"},
                        "title": {"type": "string", "description": "issue or pr: the title"},
                        "then": {"type": "string", "description": "issue only, optional: a comment for this item, drafted once the issue is open; {issue} is replaced by the new issue's URL"},
                        "comments": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "additionalProperties": false,
                            "required": ["path", "line", "body"],
                            "properties": {
                              "path": {"type": "string", "description": "Path from the repo root"},
                              "line": {"type": "integer", "description": "Line number in the new file; must be inside the diff"},
                              "body": {"type": "string"}
                            }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """;
}
