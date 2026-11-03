// Installed before the Xboard theme. Keep its web login and preferences in this window only.
(() => {
  'use strict';
  const section = document.currentScript.dataset.section;
  const emptyDocs = document.currentScript.dataset.empty;
  const base = '/' + section;
  const login = '/login?verify=member-window&redirect=' + encodeURIComponent(base);
  function store() {
    const values = new Map();
    return {
      get length() { return values.size; }, key: i => [...values.keys()][i] || null,
      getItem: k => values.get(String(k)) ?? null,
      setItem: (k, v) => values.set(String(k), String(v)),
      removeItem: k => values.delete(String(k)), clear: () => values.clear()
    };
  }
  for (const name of ['localStorage', 'sessionStorage']) {
    Object.defineProperty(window, name, { value: store(), configurable: false });
  }
  const allowed = path => path === base ||
    (section === 'order' && /^\/order\/[a-zA-Z0-9_-]+$/.test(path)) ||
    (section === 'ticket' && /^\/ticket\/[0-9]+$/.test(path));
  const root = document.documentElement;
  // Xboard groups documents by category. An empty successful list is different
  // from a failed request; keep the site's search and article detail UI in place.
  const send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function(...args) {
    this.addEventListener('loadend', () => {
      if (!this.responseURL) return;
      const url = new URL(this.responseURL);
      if (url.origin !== location.origin) return;
      let response;
      try { response = typeof this.response === 'string' ? JSON.parse(this.response) : this.response; }
      catch (_) { return; }
      if (url.pathname.startsWith('/api/v1/user/') && (this.status === 401 ||
          (this.status === 403 && response?.message === '未登录或登陆已过期'))) {
        location.replace('/__member_session_expired');
      }
      if (section !== 'knowledge' || url.pathname !== '/api/v1/user/knowledge/fetch' || url.searchParams.has('id')) return;
      const existing = document.getElementById('member-empty-docs');
      if (existing) existing.remove();
      if (this.status !== 200 || !response?.data || typeof response.data !== 'object' ||
          !Object.values(response.data).every(items => Array.isArray(items) && !items.length)) return;
      requestAnimationFrame(() => {
        const search = document.querySelector('.n-input-group');
        if (!search || document.getElementById('member-empty-docs')) return;
        const note = document.createElement('p');
        note.id = 'member-empty-docs';
        note.textContent = emptyDocs;
        note.style.marginTop = '1rem';
        search.after(note);
      });
    }, { once: true });
    return send.apply(this, args);
  };
  const sync = () => {
    const path = location.hash.slice(1).split('?')[0];
    root.dataset.memberReady = allowed(path) ? 'yes' : 'no';
    root.dataset.memberPath = path === base ? 'list' : 'detail';
  };
  history.replaceState(null, '', '/#' + login);
  const style = document.createElement('style');
  style.textContent = `
    html:not([data-member-ready="yes"]) body { visibility:hidden!important }
    .n-layout-sider,.n-menu,.n-breadcrumb,header,nav,aside,
    a[href*="#/plan"],a[href*="#/dashboard"] { display:none!important }
  `;
  if (section === 'order') style.textContent += `
    [data-member-path="list"] .n-data-table th:last-child,
    [data-member-path="list"] .n-data-table td:last-child,
    [data-member-path="detail"] button { display:none!important }
    [data-member-path="detail"] .n-card:has(.n-card__content[style*="padding: 0"]) { display:none!important }
  `;
  document.head.appendChild(style);
  if (section === 'order') {
    // Xboard renders a payment card for an unpaid order even when its payment
    // method list is empty. The portal only exposes the order's read-only data.
    new MutationObserver(() => {
      if (location.hash.split('?')[0] === '#/order') return;
      for (const content of document.querySelectorAll('.n-card__content')) {
        if (!content.childElementCount && !content.textContent.trim()) {
          const card = content.closest('.n-card');
          if (card) card.style.display = 'none';
        }
      }
    }).observe(root, { childList: true, subtree: true });
  }
  window.memberGoBack = () => {
    if (document.querySelector('.n-modal, .n-dialog')) {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }));
      return true;
    }
    const router = document.getElementById('app')?.__vue_app__?.config.globalProperties.$router;
    if (!router || !location.hash.startsWith('#' + base + '/')) return false;
    router.replace(base);
    return true;
  };
  const addGuard = () => {
    const app = document.getElementById('app')?.__vue_app__;
    const router = app?.config.globalProperties.$router;
    if (!router) return false;
    router.beforeEach(to => {
      if (allowed(to.path)) return true;
      if (to.path === '/login' && to.query.verify === 'member-window') return true;
      if (to.path === '/login') location.replace('/__member_session_expired');
      return false;
    });
    router.afterEach(sync);
    return true;
  };
  let attempts = 0;
  const timer = setInterval(() => {
    sync();
    if (addGuard() || ++attempts > 300) clearInterval(timer);
  }, 50);
  window.addEventListener('hashchange', sync);
  document.addEventListener('click', e => {
    const link = e.target.closest?.('a[href]');
    if (!link) return;
    const url = new URL(link.href, location.href);
    if (url.origin === location.origin && !allowed(url.hash.slice(1).split('?')[0])) {
      e.preventDefault(); e.stopImmediatePropagation();
    }
  }, true);
})();
