package io.github.houseelves.github.api;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.github.agent.AgentOutput;
import io.github.houseelves.github.elf.Elf;
import io.github.houseelves.github.elf.Home;
import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.thread.Poster;

/**
 * The elf's actions: each board method of DeskApi under its name in kebab case, with the same params (as JSON on
 * stdin, checked by the runner against elf.yaml first) and the same answer, printed as the last line. Answers too
 * big for one line (a diff, a PR's activity or reviews, a CI report) go in cache/{name}.json, and the answer is
 * {"file": name}.
 *
 * <p>
 * Some answers are for the runner: {"agent": ...} (start this agent), {"push": ...} (push these commits, with the
 * elf's token) and {"squash": ...} (squash this PR). The runner answers the tab in their place.
 */
public class Actions {

    /** Reads that only ask GitHub: they don't wait for a refresh to finish. */
    public static final Set<String> READS = Set.of("about", "pr-diff", "pr-activity", "pr-reviews", "commit-count",
            "merge-options", "conflict-options");

    /** Reads whose answers go in cache/. */
    static final Set<String> TO_FILE = Set.of("pr-diff", "pr-activity", "pr-reviews", "ci-report");

    /** Bigger answers go in cache/ too, unless they're for the runner. */
    static final int MAX_LINE = 256 * 1024;

    final Elf elf;
    final Supplier<String> token;
    final ObjectMapper mapper = Json.MAPPER;

    public Actions(Elf elf, Supplier<String> token) {
        this.elf = elf;
        this.token = token;
    }

    /** Runs one action. Throws with a message for the tab when it can't. */
    public JsonNode run(String action, JsonNode params) {
        JsonNode p = params == null || params.isNull() ? mapper.createObjectNode() : params;
        BoardApi api = elf.api;
        Object answer = switch (action) {
            // reads
            case "item" -> api.item(key(p));
            case "about" -> api.about(key(p));
            case "thread" -> api.thread(key(p));
            case "pr-diff" -> api.prDiff(key(p));
            case "pr-activity" -> api.prActivity(key(p));
            case "pr-reviews" -> api.prReviews(key(p));
            case "ci-report" -> api.ciReport(key(p));
            case "conflict-options" -> api.conflictOptions(key(p));
            case "commit-count" -> api.commitCount(key(p));
            case "merge-options" -> api.mergeOptions(key(p));
            case "merge-status" -> api.mergeStatus(key(p));
            case "suggestions" -> api.suggestions();
            case "preview-post" -> api.previewPost(p.path("draftId").asLong());
            case "settings" -> api.settings();
            case "is-ignored" -> api.isIgnored(key(p));
            // local changes
            case "update-draft" -> api.updateDraft(p.path("draftId").asLong(), draft(p.path("draft").asText("")));
            case "discard-draft" -> api.discardDraft(p.path("draftId").asLong());
            case "ignore" -> {
                api.ignore(key(p));
                yield null;
            }
            case "unignore" -> {
                api.unignore(key(p));
                yield null;
            }
            case "ci-reopen" -> {
                api.ciReopen(key(p));
                yield null;
            }
            case "cancel-merge" -> api.cancelMerge(key(p));
            case "watch" -> api.watch(text(p, "name"));
            case "watch-all" -> api.watchAll(owners(text(p, "owners")));
            case "exclude" -> api.exclude(text(p, "repo"));
            case "remove" -> api.remove(p.path("id").asLong());
            case "add-always-involved" -> api.addAlwaysInvolved(text(p, "repo"));
            case "remove-always-involved" -> api.removeAlwaysInvolved(text(p, "repo"));
            case "set-mvnpm-enabled" -> api.setMvnpmEnabled(p.path("on").asBoolean());
            case "set-refresh-minutes" -> api.setRefreshMinutes(p.path("minutes").asInt());
            case "seen" -> api.seen(key(p));
            case "post-done" -> api.postDone(p.path("draftId").asLong(), p.path("status").asInt(), p.path("error").asText(null));
            case "squash-done" -> api.squashDone(key(p), p.path("status").asInt(), squashed(p.path("file").asText(null)),
                    p.path("error").asText(null));
            case "agent-done" -> api.agentDone(text(p, "id"));
            // agent starts
            case "start" -> started(api.start(key(p), text(p, "action"), p.path("question").asText(null)));
            case "fix-review" -> started(api.fixReview(key(p)));
            case "reply" -> started(api.reply(key(p), text(p, "text")));
            case "ci-why" -> started(api.ciWhy(key(p)));
            case "ci-why-again" -> started(api.ciWhyAgain(key(p)));
            case "ci-fix" -> started(api.ciFix(key(p)));
            case "address-review" -> started(api.addressReview(key(p)));
            case "resolve-conflicts" -> started(api.resolveConflicts(key(p)));
            case "cancel" -> {
                var view = api.cancel(key(p));
                ObjectNode out = mapper.createObjectNode().put("cancelAgent", elf.agents.cancelled());
                out.set("thread", mapper.valueToTree(view));
                yield out;
            }
            // GitHub writes
            case "post" -> posted(api.post(p.path("draftId").asLong(), text(p, "confirm"),
                    p.path("asDraft").isBoolean() ? p.path("asDraft").asBoolean() : null,
                    p.path("kind").asText(null), p.path("itemKey").asText(null)));
            case "close" -> api.close(key(p));
            case "ready-for-review" -> api.readyForReview(key(p));
            case "assign-me" -> api.assignMe(key(p));
            case "ci-rerun" -> api.ciRerun(key(p));
            case "ci-approve" -> api.ciApprove(key(p));
            case "ask-dependabot-to-rebase" -> api.askDependabotToRebase(key(p));
            case "update-branch" -> api.updateBranch(key(p), text(p, "method"));
            case "squash" -> {
                ObjectNode out = mapper.createObjectNode();
                out.set("squash", api.squash(key(p)));
                yield out;
            }
            case "merge-on-green" -> api.mergeOnGreen(key(p), text(p, "method"), text(p, "headSha"));
            case "merge-without-approval" -> api.mergeWithoutApproval(key(p), text(p, "method"), text(p, "headSha"));
            case "mvnpm-run" -> api.mvnpmRun(p.path("repo").isTextual() ? p.path("repo").asText() : null,
                    p.path("pr").isIntegralNumber() ? p.path("pr").asInt() : null);
            default -> throw new IllegalArgumentException("Not one of the GitHub elf's actions: " + action);
        };
        JsonNode json = answer == null ? mapper.nullNode() : mapper.valueToTree(answer);
        if (TO_FILE.contains(action) || (!forRunner(json) && json.toString().length() > MAX_LINE)) {
            return toFile(action, p, json);
        }
        return json;
    }

    /** An agent start's answer: the thread, and the agent for the runner to start (if a new one was asked for). */
    private ObjectNode started(Object view) {
        ObjectNode out = mapper.createObjectNode();
        if (elf.agents.started() != null) out.set("agent", elf.agents.started());
        out.set("thread", mapper.valueToTree(view));
        return out;
    }

    /** Posted, or the push for the runner to make with the elf's token (it never passes the token on). */
    private Object posted(Poster.Result r) {
        if (r.push() == null) return r.posted();
        ObjectNode push = mapper.valueToTree(r.push());
        if (push.path("base").isNull()) push.remove("base");
        push.put("token", token.get());
        ObjectNode out = mapper.createObjectNode();
        out.set("push", push);
        return out;
    }

    private static boolean forRunner(JsonNode json) {
        return json.isObject() && (json.has("agent") || json.has("push") || json.has("squash"));
    }

    /** cache/{action}-{key}.json, answered as {"file": name}. */
    private JsonNode toFile(String action, JsonNode p, JsonNode json) {
        String about = p.path("itemKey").isTextual() ? "-" + Home.fileName(p.path("itemKey").asText()) : "";
        String name = action + about + ".json";
        Json.write(elf.home.cache().resolve(name), json);
        return mapper.createObjectNode().put("file", name);
    }

    /** The squashed commit the runner kept in agents/ for squash-done (the file it named), read once and removed. */
    private String squashed(String file) {
        if (file == null || file.isBlank()) return null;
        if (!file.matches("squash-\\d{1,20}\\.json")) throw new IllegalArgumentException("Not the runner's squash: " + file);
        java.nio.file.Path f = elf.home.agents().resolve(file);
        try {
            String body = java.nio.file.Files.readString(f);
            java.nio.file.Files.deleteIfExists(f);
            return body;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("The runner's squash isn't there any more. Squash it again.");
        }
    }

    private static String key(JsonNode p) {
        String key = text(p, "itemKey");
        if (!key.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+#\\d+")) throw new IllegalArgumentException("Not an item: " + key);
        return key;
    }

    private static String text(JsonNode p, String name) {
        JsonNode v = p.path(name);
        if (!v.isTextual()) throw new IllegalArgumentException(name + " is missing");
        return v.asText();
    }

    /** The draft as you edited it: its JSON in one text param, since an action's params are plain values. */
    private AgentOutput.DraftOut draft(String json) {
        try {
            return mapper.readValue(json, AgentOutput.DraftOut.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("The edited draft isn't readable");
        }
    }

    /** The owners to watch, comma or space separated (an action's params can't be lists of text). */
    static List<String> owners(String text) {
        return Arrays.stream(text.split("[\\s,]+")).filter(s -> !s.isBlank()).toList();
    }
}
