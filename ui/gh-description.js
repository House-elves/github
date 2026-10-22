// An item's description as written on GitHub, folded until you open it: read only then.
import { LitElement, html, css, shared, markdown, markdownStyles, spinner } from '/elf-kit/1/kit.js';
import { act, errorMessage } from './api.js';

/** "Description", folded: the issue's or PR's own text, as its author wrote it on GitHub. */
class GhDescription extends LitElement {

    static properties = {
        itemKey: { type: String },
        url: { type: String },
        _about: { state: true },
        _error: { state: true },
    };

    static styles = [shared, markdownStyles, css`
        /* Long lines and code scroll inside it: the column it's in never grows. */
        :host { display: block; min-width: 0; max-width: 100%; }
        details { border: 1px solid var(--line); border-radius: var(--r-panel); background: var(--surface); min-width: 0; overflow: hidden; }
        .md { min-width: 0; }
        .md pre { max-width: 100%; overflow-x: auto; }
        .md img { max-width: 100%; }
        summary { cursor: pointer; padding: 10px 14px; font-size: 14px; font-weight: 600; color: var(--ink); }
        .body { padding: 0 16px 14px; min-width: 0; overflow-wrap: anywhere; font-size: 14.5px; max-height: 32rem; overflow: auto; }
        .muted { color: var(--muted); font-size: 14px; margin: 0; }
        .error { margin: 0; }
    `];

    updated(changed) {
        // Another item in the same place: start folded again.
        if (changed.has('itemKey') && changed.get('itemKey') !== undefined) {
            this._about = null;
            this._error = null;
            const d = this.renderRoot.querySelector('details');
            if (d) d.open = false;
        }
    }

    async _toggle(e) {
        if (!e.target.open || this._about) return;
        const key = this.itemKey;
        this._about = { loading: true };
        this._error = null;
        try {
            const a = await act('about', { itemKey: key });
            if (key === this.itemKey) this._about = a;
        } catch (err) {
            if (key === this.itemKey) {
                this._about = null;
                this._error = errorMessage(err);
            }
        }
    }

    render() {
        const a = this._about;
        return html`<details @toggle=${this._toggle}>
            <summary>Description</summary>
            <div class="body">
                ${a?.loading ? html`<p class="muted">${spinner} Reading it from GitHub…</p>`
                    : this._error ? html`<p class="error" role="alert">Couldn't read it: ${this._error}</p>`
                    : a && !a.body?.trim() ? html`<p class="muted">No description.</p>`
                    : a ? html`<div class="md">${markdown(a.body)}</div>` : ''}
                ${a && !a.loading && this.url ? html`<p class="muted"><a href=${this.url} target="_blank" rel="noopener">On GitHub</a></p>` : ''}
            </div>
        </details>`;
    }
}

customElements.define('gh-description', GhDescription);
