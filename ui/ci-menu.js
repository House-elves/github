// The desk's CI menu (ci-menu.js), with its calls as the GitHub elf's actions. Re-running, reopening and approving CI
// write to GitHub, so each waits for your click in the desk's bar.
import { LitElement, html, css, shared, spinner, elf } from '/elf-kit/1/kit.js';
import { onThreadPage, threadRoute } from './shared.js';
import { act, cancelled, errorMessage, go, startAgent } from './api.js';

/**
 * The "CI failing" chip as a button. Opens a small panel with the failed checks and three ways forward:
 * re-run the failed GitHub Actions jobs, ask the agent why it fails, or have it fix it (only when the PR's
 * branch can be pushed to; its commits come back as a push you confirm).
 */
class CiMenu extends LitElement {

    static properties = {
        itemKey: { type: String },
        /** Full width, for the thread page's side panel. */
        block: { type: Boolean },
        /** CI waits for a maintainer's approval instead of failing: offer "Approve and run". */
        approval: { type: Boolean },
        _open: { state: true },
        _report: { state: true },
        _busy: { state: true },
        _error: { state: true },
        _rerun: { state: true },
    };

    static styles = [shared, css`
        :host { display: contents; }
        button.chip { border: 0; cursor: pointer; font-family: var(--font); }
        button.chip:hover { filter: brightness(0.96); }
        .panel-ci.approval { border-color: var(--warn); }
        .panel-ci {
            flex-basis: 100%; display: grid; gap: 10px; padding: 12px 14px; margin-top: 6px;
            border: 1.5px solid var(--bad); border-radius: var(--r-panel); background: var(--surface); font-size: 14px;
            color: var(--ink); text-align: left;
        }
        ul { margin: 0; padding-left: 18px; display: grid; gap: 2px; }
        li { overflow-wrap: anywhere; }
        .acts { display: flex; flex-wrap: wrap; gap: 8px; }
        .block .acts { display: grid; }
        .note { color: var(--muted); font-size: 13px; margin: 0; }
        .error { font-size: 13px; padding: 6px 10px; }
    `];

    constructor() {
        super();
        this._open = false;
        this._busy = null;
    }

    async _toggle() {
        this._open = !this._open;
        this._error = null;
        if (this._open && !this._report) {
            this._busy = 'load';
            try {
                this._report = await act('ci-report', { itemKey: this.itemKey });
            } catch (e) {
                this._error = errorMessage(e);
            } finally {
                this._busy = null;
            }
        }
    }

    async _retry() {
        this._busy = 'retry';
        this._error = null;
        try {
            const n = await act('ci-rerun', { itemKey: this.itemKey });
            this._rerun = n;
            // The board now shows this PR's CI as in progress; tell it to reload.
            this.dispatchEvent(new CustomEvent('ci-changed', { bubbles: true, composed: true }));
        } catch (e) {
            if (!cancelled(e)) this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    async _reopen() {
        this._busy = 'reopen';
        this._error = null;
        try {
            await act('ci-reopen', { itemKey: this.itemKey });
            this._rerun = 1;
            this.dispatchEvent(new CustomEvent('ci-changed', { bubbles: true, composed: true }));
        } catch (e) {
            if (!cancelled(e)) this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    async _approve() {
        this._busy = 'approve';
        this._error = null;
        try {
            this._rerun = await act('ci-approve', { itemKey: this.itemKey });
            this.dispatchEvent(new CustomEvent('ci-changed', { bubbles: true, composed: true }));
        } catch (e) {
            if (!cancelled(e)) this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    async _agent(what) {
        this._busy = what;
        this._error = null;
        try {
            const v = await startAgent(what === 'why' ? 'ci-why' : what === 'again' ? 'ci-why-again' : 'ci-fix',
                { itemKey: this.itemKey });
            // On the thread page, show its current state. On the board, stay there while the agent works (the row
            // shows it); an answer it already gave for this commit (no job started) opens the thread.
            this._open = false;
            if (onThreadPage(this.itemKey, elf.route())) {
                this.dispatchEvent(new CustomEvent('thread-changed', { bubbles: true, composed: true }));
            } else if (!v?.job) {
                go(threadRoute(this.itemKey));
            }
        } catch (e) {
            if (!cancelled(e)) this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    render() {
        if (this._rerun) {
            return html`<span class="chip run">${spinner}CI in progress</span>`;
        }
        if (this.approval) {
            return html`
                <button class="chip warn" aria-expanded=${this._open ? 'true' : 'false'} @click=${() => { this._open = !this._open; }}
                        title="CI is waiting for a maintainer to approve it">CI needs approval ▾</button>
                ${this._open ? this._renderApproval() : ''}
            `;
        }
        return html`
            <button class="chip bad" aria-expanded=${this._open ? 'true' : 'false'} @click=${this._toggle}
                    title="Show what failed and what to do">CI failing ▾</button>
            ${this._open ? this._renderPanel() : ''}
        `;
    }

    _renderApproval() {
        return html`
            <div class="panel-ci approval ${this.block ? 'block' : ''}" role="group" aria-label="CI needs approval">
                <span>GitHub holds CI for this fork PR until a maintainer approves it, usually because the author is
                    contributing for the first time.</span>
                <div class="acts">
                    <button class="sm primary" ?disabled=${this._busy} @click=${this._approve}>
                        ${this._busy === 'approve' ? spinner : ''}Approve and run</button>
                </div>
                <p class="note">Approving runs the PR's workflows as they are in its branch. Have a look at what it changes
                    first, especially build and workflow files.</p>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            </div>
        `;
    }

    _renderPanel() {
        const r = this._report;
        const spin = what => this._busy === what ? spinner : '';
        return html`
            <div class="panel-ci ${this.block ? 'block' : ''}" role="group" aria-label="Failing CI">
                ${this._busy === 'load' ? html`<span class="note">${spinner} Reading the checks…</span>` : ''}
                ${r ? html`
                    ${r.failed.length ? html`<ul>${r.failed.map(f => html`<li>
                        ${f.url ? html`<a href=${f.url} target="_blank" rel="noopener">${f.name}</a>` : f.name}
                        <span class="note">${f.conclusion.toLowerCase().replace('_', ' ')}</span></li>`)}</ul>`
                        : html`<p class="note">GitHub reports no failed checks any more. CI may already be running again.</p>`}
                    <div class="acts">
                        ${r.rerunnableRuns.length && r.canRerun ? html`<button class="sm" ?disabled=${this._busy} @click=${this._retry}>
                            ${spin('retry')}Retry failed builds</button>` : ''}
                        ${!r.canRerun && r.own ? html`<button class="sm" ?disabled=${this._busy} @click=${this._reopen}
                            title="Close and reopen your PR, which runs its CI again">${spin('reopen')}Re-run CI (close and reopen)</button>` : ''}
                        <button class="sm" ?disabled=${this._busy} @click=${() => this._agent('why')}>${spin('why')}Why is this failing</button>
                        ${this.block ? html`<button class="sm quiet" ?disabled=${this._busy} @click=${() => this._agent('again')}
                            title="Have the agent look at this commit again">${spin('again')}Ask again</button>` : ''}
                        ${r.canPush ? html`<button class="sm primary" ?disabled=${this._busy} @click=${() => this._agent('fix')}>
                            ${spin('fix')}Fix this</button>` : ''}
                    </div>
                    ${!r.canPush ? html`<p class="note">Fixing isn't offered: the author didn't allow edits from maintainers, so
                        nothing can be pushed to ${r.headRepo}:${r.headRef}.</p>`
                        : html`<p class="note">Fix this: the agent commits a fix in its own copy of ${r.headRepo}:${r.headRef}.
                            Nothing is pushed until you confirm it in the thread.</p>`}
                    ${!r.rerunnableRuns.length && r.failed.length ? html`<p class="note">These checks aren't GitHub Actions
                        jobs, so they can't be re-run from here.</p>` : ''}
                    ${!r.canRerun ? html`<p class="note">You don't have write access to this repo, so GitHub won't let you
                        re-run its workflows${r.own ? '. As it\'s your PR, closing and reopening it runs CI again.' : '.'}</p>` : ''}
                ` : ''}
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            </div>
        `;
    }
}

customElements.define('ci-menu', CiMenu);

