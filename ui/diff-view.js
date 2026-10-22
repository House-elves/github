// The desk's diff view (diff-view.js), as it is: a unified diff as GitHub's "Files changed" shows it.
import { LitElement, html, css, shared } from '/elf-kit/1/kit.js';

/** Files over this many diff lines start folded, and so does everything after this many lines in all. */
const BIG_FILE = 400, OPEN_BUDGET = 2500;

/**
 * A unified diff (git diff, or GitHub's .diff) as files with hunks: [{path, from, status, binary, add, del, hunks:
 * [{header, lines: [{t: add|del|ctx|note, n: old line, m: new line, s: text}]}]}].
 */
export function parseDiff(text) {
    const files = [];
    let f = null, h = null, oldN = 0, newN = 0;
    for (const line of (text ?? '').split('\n')) {
        if (line.startsWith('diff --git ')) {
            const m = /^diff --git a\/(.+?) b\/(.+)$/.exec(line);
            f = { path: m ? m[2] : line.slice(11), from: m ? m[1] : null, status: 'modified', binary: false, add: 0, del: 0, hunks: [] };
            files.push(f);
            h = null;
            continue;
        }
        if (!f) continue;
        const hunk = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$/.exec(line);
        if (hunk) {
            oldN = Number(hunk[1]);
            newN = Number(hunk[2]);
            h = { header: line, lines: [] };
            f.hunks.push(h);
            continue;
        }
        if (!h) {
            if (line.startsWith('new file mode')) f.status = 'added';
            else if (line.startsWith('deleted file mode')) f.status = 'deleted';
            else if (line.startsWith('rename from ')) { f.status = 'renamed'; f.from = line.slice(12); }
            else if (line.startsWith('rename to ')) f.path = line.slice(10);
            else if (line.startsWith('Binary files')) f.binary = true;
            continue;
        }
        const c = line[0];
        if (c === '+') { h.lines.push({ t: 'add', n: null, m: newN++, s: line.slice(1) }); f.add++; }
        else if (c === '-') { h.lines.push({ t: 'del', n: oldN++, m: null, s: line.slice(1) }); f.del++; }
        else if (c === '\\') h.lines.push({ t: 'note', s: line.slice(2) });
        // Context lines start with a space; an empty line is only the diff's final newline.
        else if (line.length) h.lines.push({ t: 'ctx', n: oldN++, m: newN++, s: line.slice(1) });
    }
    return files;
}

const STATUS = { added: 'Added', deleted: 'Deleted', renamed: 'Renamed' };

/** The change, file by file, the way GitHub's "Files changed" shows it: line numbers, additions and deletions. */
class DiffView extends LitElement {

    static properties = {
        diff: {},       // unified diff text
        cut: { type: Boolean },   // the diff was cut short
        url: {},        // where to see all of it (GitHub)
        _files: { state: true },
        _open: { state: true },
    };

    static styles = [shared, css`
        :host { display: grid; gap: 10px; min-width: 0; }
        .summary { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 14px; font-size: 13.5px; color: var(--muted); }
        .summary b { color: var(--ink); font-weight: 600; }
        .plus { color: var(--ok); font-weight: 600; font-variant-numeric: tabular-nums; }
        .minus { color: var(--bad); font-weight: 600; font-variant-numeric: tabular-nums; }
        .file { border: 1px solid var(--line); border-radius: var(--r-control); overflow: hidden; min-width: 0; background: var(--surface); }
        .head {
            all: unset; box-sizing: border-box; cursor: pointer; width: 100%;
            display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; padding: 8px 12px;
            background: var(--surface-2); font-size: 13px;
        }
        .head:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
        .file.open .head { border-bottom: 1px solid var(--line); }
        .chev { width: 10px; color: var(--muted); transition: transform 120ms; }
        .file.open .chev { transform: rotate(90deg); }
        .path { font: 12.5px var(--font-code); color: var(--ink); overflow-wrap: anywhere; min-width: 0; flex: 1 1 16rem; }
        .path .was { color: var(--muted); }
        .counts { display: inline-flex; gap: 8px; font: 12px var(--font-code); }
        .scroll { overflow-x: auto; }
        table { border-collapse: collapse; width: 100%; font: 12.5px/1.5 var(--font-code); tab-size: 4; }
        td { padding: 0 10px; white-space: pre; vertical-align: top; }
        td.n {
            width: 1%; min-width: 3ch; text-align: right; color: var(--muted); user-select: none; padding: 0 8px;
            font-variant-numeric: tabular-nums; opacity: 0.8;
        }
        td.sign { width: 1%; padding: 0 0 0 6px; user-select: none; color: var(--muted); }
        tr.add { background: var(--ok-soft); }
        tr.add td.sign { color: var(--ok); }
        tr.del { background: var(--bad-soft); }
        tr.del td.sign { color: var(--bad); }
        tr.hunk td { background: var(--accent-soft); color: var(--muted); padding: 3px 10px; }
        tr.note td { color: var(--muted); font-style: italic; }
        .empty { padding: 10px 12px; margin: 0; font-size: 13px; color: var(--muted); }
        .cut { margin: 0; font-size: 13px; color: var(--muted); }
        button.link { all: unset; cursor: pointer; color: var(--accent); }
        button.link:focus-visible { outline: 2px solid var(--accent); border-radius: 3px; }
    `];

    willUpdate(changed) {
        if (changed.has('diff')) {
            this._files = parseDiff(this.diff);
            // Small changes open; big files and whatever comes after the budget start folded.
            let budget = OPEN_BUDGET;
            this._open = new Set();
            this._files.forEach((f, i) => {
                const size = f.add + f.del;
                if (size <= BIG_FILE && size <= budget) {
                    this._open.add(i);
                    budget -= size;
                }
            });
        }
    }

    _toggle(i) {
        const open = new Set(this._open);
        if (open.has(i)) open.delete(i); else open.add(i);
        this._open = open;
    }

    _all(open) {
        this._open = open ? new Set(this._files.map((_, i) => i)) : new Set();
    }

    _file(f, i) {
        const open = this._open.has(i);
        return html`
            <div class="file ${open ? 'open' : ''}">
                <button class="head" aria-expanded=${open ? 'true' : 'false'} @click=${() => this._toggle(i)}>
                    <svg class="chev" viewBox="0 0 10 10" aria-hidden="true"><path d="M3 1.5 7 5 3 8.5" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>
                    <span class="path">${f.status === 'renamed' && f.from !== f.path ? html`<span class="was">${f.from} → </span>` : ''}${f.path}</span>
                    ${STATUS[f.status] ? html`<span class="chip">${STATUS[f.status]}</span>` : ''}
                    <span class="counts"><span class="plus">+${f.add}</span><span class="minus">−${f.del}</span></span>
                </button>
                ${open ? this._body(f) : ''}
            </div>`;
    }

    _body(f) {
        if (f.binary) return html`<p class="empty">Binary file, not shown.</p>`;
        if (!f.hunks.length) return html`<p class="empty">${f.status === 'renamed' ? 'Renamed without changes.' : 'No changes to show.'}</p>`;
        return html`
            <div class="scroll">
                <table>
                    ${f.hunks.map(h => html`
                        <tr class="hunk"><td colspan="4">${h.header}</td></tr>
                        ${h.lines.map(l => l.t === 'note'
                            ? html`<tr class="note"><td class="n"></td><td class="n"></td><td colspan="2">${l.s}</td></tr>`
                            : html`<tr class=${l.t}><td class="n">${l.n ?? ''}</td><td class="n">${l.m ?? ''}</td><td class="sign">${l.t === 'add' ? '+' : l.t === 'del' ? '−' : ' '}</td><td>${l.s}</td></tr>`)}`)}
                </table>
            </div>`;
    }

    render() {
        const files = this._files ?? [];
        if (!files.length) return html`<p class="cut">No changes to show.</p>`;
        const add = files.reduce((a, f) => a + f.add, 0), del = files.reduce((a, f) => a + f.del, 0);
        return html`
            <div class="summary">
                <span><b>${files.length}</b> ${files.length === 1 ? 'file' : 'files'} changed</span>
                <span class="plus">+${add}</span><span class="minus">−${del}</span>
                ${files.length > 1 ? html`
                    <button class="link" @click=${() => this._all(true)}>Expand all</button>
                    <button class="link" @click=${() => this._all(false)}>Collapse all</button>` : ''}
            </div>
            ${this.cut ? html`<p class="cut">This change is too big to show in full here; the rest is cut off.${this.url
                ? html` <a href=${this.url} target="_blank" rel="noopener">See all of it on GitHub</a>.` : ''}</p>` : ''}
            ${files.map((f, i) => this._file(f, i))}
        `;
    }
}

customElements.define('diff-view', DiffView);
