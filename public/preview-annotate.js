(() => {
  function parseRunIdFromPath(pathname) {
    // expected: /_preview/<run-id>/site/...
    const parts = (pathname || '').split('/').filter(Boolean);
    if (parts.length < 3) return null;
    if (parts[0] !== '_preview') return null;
    const runId = parts[1];
    const module = parts[2];
    if (module !== 'site') return null;
    return runId;
  }

  function isPreviewPage() {
    return !!parseRunIdFromPath(window.location.pathname);
  }

  function cssPath(el) {
    if (!el || el.nodeType !== 1) return '';
    const parts = [];
    let cur = el;
    let depth = 0;
    while (cur && cur.nodeType === 1 && depth < 6) {
      let part = cur.tagName.toLowerCase();
      if (cur.id) {
        part += `#${cur.id}`;
        parts.unshift(part);
        break;
      }
      const classList = (cur.className || '')
        .toString()
        .split(/\s+/)
        .filter(Boolean)
        .slice(0, 2);
      if (classList.length) part += '.' + classList.join('.');

      const parent = cur.parentElement;
      if (parent) {
        const siblings = Array.from(parent.children).filter((c) => c.tagName === cur.tagName);
        if (siblings.length > 1) {
          const idx = siblings.indexOf(cur) + 1;
          part += `:nth-of-type(${idx})`;
        }
      }

      parts.unshift(part);
      cur = parent;
      depth += 1;
    }
    return parts.join(' > ');
  }

  function textSnippet(el) {
    const raw = (el && (el.innerText || el.textContent) ? (el.innerText || el.textContent) : '').toString();
    const compact = raw.replace(/\s+/g, ' ').trim();
    if (!compact) return '';
    return compact.length > 160 ? compact.slice(0, 157) + '…' : compact;
  }

  function uid() {
    return `ref_${Date.now()}_${Math.random().toString(16).slice(2)}`;
  }

  function el(tag, attrs, children) {
    const node = document.createElement(tag);
    if (attrs) {
      for (const [k, v] of Object.entries(attrs)) {
        if (k === 'class') node.className = v;
        else if (k === 'text') node.textContent = v;
        else if (k === 'html') node.innerHTML = v;
        else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
        else node.setAttribute(k, v);
      }
    }
    if (children) {
      for (const c of children) node.appendChild(c);
    }
    return node;
  }

  async function apiRequest(runId, method, path, body) {
    const url = `/_preview/${encodeURIComponent(runId)}/agent${path}`;
    const opts = {
      method,
      headers: {
        'Content-Type': 'application/json',
      },
      credentials: 'include',
    };
    if (body != null) opts.body = JSON.stringify(body);
    const res = await fetch(url, opts);
    const text = await res.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch (_e) {
      data = { raw: text };
    }
    if (!res.ok) {
      const msg = (data && (data.error || data.message)) || `HTTP ${res.status}`;
      throw new Error(msg);
    }
    return data;
  }

  function mount() {
    if (!isPreviewPage()) return;
    const runId = parseRunIdFromPath(window.location.pathname);
    if (!runId) return;
    if (document.getElementById('agent-refs-panel')) return;

    const state = {
      selecting: false,
      refs: [],
      status: 'idle',
      error: null,
    };

    const panel = el('div', { class: 'agent-refs-panel', id: 'agent-refs-panel' });
    const header = el('div', { class: 'agent-refs-header' });
    const title = el('div', { class: 'agent-refs-title', text: 'Agent refs' });
    const badge = el('div', { class: 'agent-refs-badge', text: `run: ${runId}` });

    const toggle = el('button', {
      class: 'agent-refs-btn agent-refs-btn-primary',
      text: 'Select: off',
      onclick: () => {
        state.selecting = !state.selecting;
        render();
      },
    });

    const save = el('button', {
      class: 'agent-refs-btn',
      text: 'Save',
      onclick: async () => {
        state.status = 'saving';
        state.error = null;
        render();
        try {
          await apiRequest(runId, 'POST', '/refs', { refs: state.refs });
          state.status = 'saved';
          render();
          setTimeout(() => {
            if (state.status === 'saved') {
              state.status = 'idle';
              render();
            }
          }, 1200);
        } catch (e) {
          state.status = 'idle';
          state.error = e && e.message ? e.message : 'Save failed';
          render();
        }
      },
    });

    const clear = el('button', {
      class: 'agent-refs-btn agent-refs-btn-danger',
      text: 'Clear',
      onclick: async () => {
        state.status = 'clearing';
        state.error = null;
        render();
        try {
          await apiRequest(runId, 'DELETE', '/refs', {});
          state.refs = [];
          state.status = 'idle';
          render();
        } catch (e) {
          state.status = 'idle';
          state.error = e && e.message ? e.message : 'Clear failed';
          render();
        }
      },
    });

    header.appendChild(title);
    header.appendChild(badge);
    header.appendChild(toggle);
    header.appendChild(save);
    header.appendChild(clear);

    const hint = el('div', { class: 'agent-refs-hint' });
    const list = el('div', { class: 'agent-refs-list' });
    const footer = el('div', { class: 'agent-refs-footer' });
    const status = el('div', { class: 'agent-refs-status' });

    panel.appendChild(header);
    panel.appendChild(hint);
    panel.appendChild(list);
    footer.appendChild(status);
    panel.appendChild(footer);

    function render() {
      toggle.textContent = state.selecting ? 'Select: on' : 'Select: off';
      toggle.classList.toggle('agent-refs-btn-primary', state.selecting);
      hint.textContent = state.selecting
        ? 'Click elements on the page to capture reference points. Add notes, then Save.'
        : 'Turn Select on to capture reference points.';

      status.textContent = state.error
        ? `Error: ${state.error}`
        : state.status === 'saving'
          ? 'Saving…'
          : state.status === 'clearing'
            ? 'Clearing…'
            : state.status === 'saved'
              ? 'Saved.'
              : `${state.refs.length} reference point(s)`;

      status.classList.toggle('agent-refs-status-error', !!state.error);

      list.innerHTML = '';
      if (!state.refs.length) {
        list.appendChild(el('div', { class: 'agent-refs-empty', text: 'No reference points yet.' }));
        return;
      }

      state.refs.forEach((r, idx) => {
        const item = el('div', { class: 'agent-refs-item' });
        const top = el('div', { class: 'agent-refs-item-top' });
        const n = el('div', { class: 'agent-refs-item-num', text: String(idx + 1) });
        const meta = el('div', { class: 'agent-refs-item-meta' });
        meta.appendChild(el('div', { class: 'agent-refs-item-url', text: r.url || '' }));
        meta.appendChild(el('div', { class: 'agent-refs-item-text', text: r.text || '' }));
        top.appendChild(n);
        top.appendChild(meta);

        const note = el('textarea', {
          class: 'agent-refs-note',
          rows: '2',
          placeholder: 'Add a note for the agent (what should change here?)',
        });
        note.value = r.note || '';
        note.addEventListener('input', (e) => {
          r.note = e.target.value;
        });

        const actions = el('div', { class: 'agent-refs-item-actions' });
        const del = el('button', {
          class: 'agent-refs-link',
          text: 'remove',
          onclick: () => {
            state.refs = state.refs.filter((x) => x.id !== r.id);
            render();
          },
        });
        const copy = el('button', {
          class: 'agent-refs-link',
          text: 'copy',
          onclick: async () => {
            const payload = JSON.stringify(r, null, 2);
            try {
              await navigator.clipboard.writeText(payload);
            } catch (_e) {
              // ignore
            }
          },
        });
        actions.appendChild(copy);
        actions.appendChild(del);

        item.appendChild(top);
        item.appendChild(note);
        item.appendChild(actions);
        list.appendChild(item);
      });
    }

    function onClickCapture(e) {
      if (!state.selecting) return;
      const target = e.target;
      if (!target || !(target instanceof Element)) return;
      if (panel.contains(target)) return;
      e.preventDefault();
      e.stopPropagation();

      const ref = {
        id: uid(),
        at: new Date().toISOString(),
        url: window.location.pathname + (window.location.search || ''),
        selector: cssPath(target),
        text: textSnippet(target),
        note: '',
      };
      state.refs.unshift(ref);
      if (state.refs.length > 30) state.refs = state.refs.slice(0, 30);
      render();
    }

    document.body.appendChild(panel);
    document.addEventListener('click', onClickCapture, true);

    apiRequest(runId, 'GET', '/refs', null)
      .then((data) => {
        if (data && Array.isArray(data.refs)) {
          state.refs = data.refs;
          render();
        }
      })
      .catch(() => {});

    render();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mount);
  } else {
    mount();
  }
})();

