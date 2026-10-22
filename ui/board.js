// The GitHub elf's tab on Elf Desk, and its dashboard card: for now only how many items are on the board, from
// board/board.json (what the elf's refresh wrote). The desk's GitHub tab moves here next.
import { LitElement, html, css, shared, cardStyles, elf } from '/elf-kit/1/kit.js';

/** Every item on the board: the four lanes and the mvnpm lane. */
function count(board) {
    const lanes = (board?.lanes ?? []).reduce((n, l) => n + (l.items?.length ?? 0), 0);
    return lanes + (board?.mvnpm?.rows?.length ?? 0);
}

class BoardTab extends LitElement {

    static properties = {
        _board: { state: true },
        _error: { state: true },
    };

    static styles = [shared, cardStyles, css`
        .say { font-size: 15px; }
        .muted { color: var(--muted); font-size: 13.5px; }
    `];

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        super.disconnectedCallback();
        this._off?.();
    }

    async _load() {
        try {
            this._board = await elf.readJson('board', 'board.json');
            this._error = null;
        } catch (e) {
            this._error = e?.message ?? String(e);
        }
        elf.card?.({ attention: false });
    }

    render() {
        if (this._error) return html`<p class="say error">${this._error}</p>`;
        if (this._board === undefined) return html`<p class="muted">Reading the board…</p>`;
        if (!this._board) return html`<p class="say">No board yet. Refresh it to fetch GitHub.</p>`;
        const n = count(this._board);
        return html`<p class="say">${n} ${n === 1 ? 'item' : 'items'} on your board.</p>
            <p class="muted">Fetched ${new Date(this._board.fetchedAt).toLocaleString()}.</p>`;
    }
}

customElements.define('github-board', BoardTab);
document.body.append(document.createElement('github-board'));
