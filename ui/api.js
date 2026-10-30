// The board's half of the desk's DeskApi, as the GitHub elf's actions through the kit: each desk.someMethod(...) is
// elf.act('some-method', ...) with the same values and the same answer. The board, the threads and the settings are
// files the elf writes (board/board.json, threads/<key>.json, state/settings-view.json), read here, not asked for.
// Nothing here writes to GitHub on its own: an action that does waits for your click in the desk's bar.
import { elf } from '/elf-kit/1/kit.js';

/** A readable message from a failed call. */
export function errorMessage(e) {
    return e?.message || String(e);
}

/** You clicked Cancel in the desk's bar: nothing was sent, and there's nothing to say about it. */
export function cancelled(e) {
    return !!e?.cancelled;
}

/** owner/repo#123 as its threads/ file: every character outside [A-Za-z0-9._-] made _. */
export function threadFile(itemKey) {
    return `${itemKey.replace(/[^A-Za-z0-9._-]/g, '_')}.json`;
}

/** The writes to GitHub, whose confirm bar names the account they're done as ({as}). */
const AS = new Set(['post', 'close', 'ready-for-review', 'assign-me', 're-request-review', 'ci-rerun', 'ci-approve',
    'ci-reopen', 'ask-dependabot-to-rebase', 'update-branch', 'merge-on-green', 'merge-without-approval']);

/** The account an item's writes are done as: the one that found it (the board's first), else the elf's own. */
async function accountFor(itemKey) {
    const b = await board().catch(() => null);
    return b?.found?.[itemKey]?.[0] ?? b?.login ?? (await settingsView().catch(() => null))?.login;
}

/**
 * One of the elf's actions. Values that are null or missing are left out (the elf.yaml makes those optional), and
 * an answer too big for one line ({"file": name}) is read from cache/. A write to GitHub says which account does it
 * (as), for the desk's bar; the elf refuses it if that isn't the item's account any more.
 */
export async function act(action, params = {}) {
    const values = Object.fromEntries(Object.entries(params).filter(([, v]) => v != null));
    if (AS.has(action) && values.itemKey && !values.as) {
        const as = await accountFor(values.itemKey);
        if (as) values.as = as;
    }
    const r = await elf.act(action, values);
    if (r && typeof r === 'object' && !Array.isArray(r) && typeof r.file === 'string' && Object.keys(r).length === 1) {
        return elf.readJson('cache', r.file);
    }
    return r;
}

/** The board, as the elf's last run wrote it (DeskApi.board), or null before its first refresh. */
export function board() {
    return elf.readJson('board', 'board.json');
}

/** One item's thread (DeskApi.thread), or null if there is none yet. */
export function thread(itemKey) {
    return elf.readJson('threads', threadFile(itemKey));
}

/** The board's settings (DeskApi.settings), or null before the elf first ran. */
export function settingsView() {
    return elf.readJson('state', 'settings-view.json');
}

/** An agent's report, with its lines so far (agents/<id>.json), or null. */
export async function agentReport(id) {
    try {
        return id ? await elf.agent(id) : null;
    } catch {
        return null;
    }
}

/**
 * An agent start (start, fix-review, reply, ci-why, ci-fix, ...): the runner starts the agent the answer asks for
 * and answers with its id in its place. Resolves with the thread as it is now.
 */
export async function startAgent(action, params) {
    const r = await act(action, params);
    return r?.thread ?? null;
}

/** Stops the item's agent: the elf notes it in the thread and names the agent, which the desk then stops. */
export async function cancel(itemKey) {
    const r = await act('cancel', { itemKey });
    if (r?.cancelAgent) await elf.cancelAgent(r.cancelAgent);
    return r?.thread ?? null;
}

/**
 * Posts a previewed draft, after your click in the desk's bar. Commits (a push, a new PR) come back as a push the
 * runner made from its worktree ({push: {status, error}}): post-done then finishes the post. Resolves with what was
 * posted ({url, pushedTo}).
 */
export async function post({ draftId, confirm, asDraft, kind, itemKey, askAgain }) {
    const r = await act('post', { draftId, confirm, asDraft, kind, itemKey, ...(askAgain ? { askAgain } : {}) });
    if (r?.push) return act('post-done', { draftId, status: r.push.status ?? 0, error: r.push.error });
    return r;
}

/**
 * Squashes a PR into one commit on its base, after your click in the desk's bar: the runner squashes it in its
 * worktree ({squash: {status, file, error}}), and squash-done offers it in the thread as a push you confirm.
 * Nothing is pushed here. Resolves with the thread.
 */
export async function squash(itemKey) {
    const r = await act('squash', { itemKey });
    const s = r?.squash ?? {};
    return act('squash-done', { itemKey, status: s.status ?? 0, file: s.file, error: s.error });
}

// ---- places in the tab ----

const goListeners = new Set();

/** Goes to a place in the tab (the board: '', a thread page: thread/owner/repo/123), as a desk link. */
export function go(route, { replace = false } = {}) {
    elf.go(route, { replace }).catch(() => { /* the desk said no: the tab still shows it */ });
    goListeners.forEach(l => l(route));
}

/** Calls back when the tab goes somewhere, by a click here or the desk's link (Back); returns the unsubscribe. */
export function onGo(callback) {
    goListeners.add(callback);
    const off = elf.onRoute(callback);
    return () => {
        goListeners.delete(callback);
        off();
    };
}
