// The desk's conflict menu (conflict-menu.js), with its calls as the GitHub elf's actions. Asking Dependabot and
// updating the branch write to GitHub, so each waits for your click in the desk's bar.
import { LitElement, html, css, shared, spinner, elf } from '/elf-kit/1/kit.js';
import { onThreadPage, threadRoute } from './shared.js';
import { act, cancelled, errorMessage, go, startAgent } from './api.js';

/**
 * The "Conflicts" chip as a button. Dependabot PRs: ask Dependabot to rebase (it regenerates the PR and keeps
 * maintaining it). Other PRs whose branch can be pushed to: the agent resolves the conflicts, and the PR comes
 * back in the thread as one squashed commit to push, which you confirm.
 */
class ConflictMenu extends LitElement {

    static properties = {
        itemKey: { type: String },
        block: { type: Boolean },
        /** The PR is behind its base (no conflicts): offer GitHub's update by rebase (never a merge commit). */
        behind: { type: Boolean },
        _open: { state: true },
        _options: { state: true },
        _busy: { state: true },
        _error: { state: true },
        _asked: { state: true },
        _updated: { state: true },
    };

    static styles = [shared, css`
        :host { display: contents; }
        button.chip { border: 0; cursor: pointer; font-family: var(--font); }
        button.chip:hover { filter: brightness(0.96); }
        .panel-c {
            flex-basis: 100%; display: grid; gap: 10px; padding: 12px 14px; margin-top: 6px;
            border: 1.5px solid var(--bad); border-radius: var(--r-panel); background: var(--surface); font-size: 14px;
            color: var(--ink); text-align: left;
        }
        .panel-c.behind { border-color: var(--warn); }
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
        if (this._open && !this._options) {
            this._busy = 'load';
            try {
                this._options = await act('conflict-options', { itemKey: this.itemKey });
            } catch (e) {
                this._error = errorMessage(e);
            } finally {
                this._busy = null;
            }
        }
    }

    async _rebase() {
        this._busy = 'rebase';
        this._error = null;
        try {
            await act('ask-dependabot-to-rebase', { itemKey: this.itemKey });
            this._asked = true;
        } catch (e) {
            if (!cancelled(e)) this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    async _update(method) {
        this._busy = method;
        this._error = null;
        try {
            this._updated = await act('update-branch', { itemKey: this.itemKey, method });
            this.dispatchEvent(new CustomEvent('ci-changed', { bubbles: true, composed: true }));
        } catch (e) {
            if (!cancelled(e)) this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    async _resolve() {
        this._busy = 'resolve';
        this._error = null;
        try {
            const v = await startAgent('resolve-conflicts', { itemKey: this.itemKey });
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
        if (this._asked || this._updated === 'rebase-requested') return html`<span class="chip run">${spinner}Dependabot is rebasing</span>`;
        if (this._updated) return html`<span class="chip run">${spinner}${this._updated === 'rebased' ? 'Rebased' : 'Updated'}, CI starting</span>`;
        if (this.behind) {
            return html`
                <button class="chip warn" aria-expanded=${this._open ? 'true' : 'false'} @click=${this._toggle}
                        title="The repo requires the branch to be up to date before merging">Behind base ▾</button>
                ${this._open ? this._renderBehind() : ''}
            `;
        }
        return html`
            <button class="chip bad" aria-expanded=${this._open ? 'true' : 'false'} @click=${this._toggle}
                    title="Show how to resolve the conflicts">Conflicts ▾</button>
            ${this._open ? this._renderPanel() : ''}
        `;
    }

    _renderBehind() {
        const o = this._options;
        const spin = what => this._busy === what ? spinner : '';
        return html`
            <div class="panel-c behind ${this.block ? 'block' : ''}" role="group" aria-label="Behind base">
                ${this._busy === 'load' ? html`<span class="note">${spinner} Looking at the PR…</span>` : ''}
                ${o ? html`
                    <span>This PR is behind <b>${o.base}</b>, and the repo requires it to be up to date before merging.</span>
                    <div class="acts">
                        ${o.dependabot ? html`<button class="sm primary" ?disabled=${this._busy} @click=${() => this._update('REBASE')}>
                            ${spin('REBASE')}Ask Dependabot to rebase</button>`
                        : o.canPush ? html`
                            <button class="sm primary" ?disabled=${this._busy} @click=${() => this._update('REBASE')}>
                                ${spin('REBASE')}Rebase on ${o.base}</button>` : ''}
                    </div>
                    ${o.dependabot ? html`<p class="note">Posts "@dependabot rebase" as you, so Dependabot keeps maintaining the PR.</p>`
                        : o.canPush ? html`<p class="note">GitHub does the update, as you, only if nothing was pushed in the
                            meantime. Rebasing rewrites the PR's commits, so the author has to re-fetch. CI runs again.</p>`
                        : html`<p class="note">The author didn't allow edits from maintainers, so the branch can't be updated from
                            here. Ask them to update it.</p>`}
                ` : ''}
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            </div>
        `;
    }

    _renderPanel() {
        const o = this._options;
        const spin = what => this._busy === what ? spinner : '';
        return html`
            <div class="panel-c ${this.block ? 'block' : ''}" role="group" aria-label="Merge conflicts">
                ${this._busy === 'load' ? html`<span class="note">${spinner} Looking at the PR…</span>` : ''}
                ${o ? html`
                    <span>This PR conflicts with <b>${o.base}</b>.</span>
                    <div class="acts">
                        ${o.dependabot ? html`<button class="sm primary" ?disabled=${this._busy} @click=${this._rebase}>
                            ${spin('rebase')}Ask Dependabot to rebase</button>` : ''}
                        ${o.canPush ? html`<button class="sm ${o.dependabot ? '' : 'primary'}" ?disabled=${this._busy} @click=${this._resolve}>
                            ${spin('resolve')}Resolve conflicts</button>` : ''}
                    </div>
                    ${o.dependabot ? html`<p class="note">Posts "@dependabot rebase" on the PR as you. Dependabot rebases and
                        regenerates it. That's better than pushing a fix ourselves: Dependabot stops maintaining a PR once someone
                        else pushes to its branch.</p>` : ''}
                    ${o.canPush ? html`<p class="note">Resolve conflicts: the agent resolves the conflicts with ${o.base} in its own
                        copy, and the PR becomes one commit on ${o.base}. It replaces ${o.headRepo}:${o.headRef} only when you confirm.</p>`
                        : html`<p class="note">The author didn't allow edits from maintainers, so a resolution can't be pushed to
                        ${o.headRepo}:${o.headRef} from here.</p>`}
                ` : ''}
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            </div>
        `;
    }
}

customElements.define('conflict-menu', ConflictMenu);
