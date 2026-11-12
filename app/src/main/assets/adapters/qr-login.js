(function() {
  if (location.origin !== 'https://ani.gamer.com.tw') return;
  window.__bahaQrLogin?.dispose();
  const ticket = window.__bahaDocumentNonce;
  let disposed = false, paused = window.__bahaMediaSuspended === true;
  let timer, controller, running = false, generation = 0, session, failures = 0;
  const flow = {
    dispose() {
      disposed = true;
      generation++;
      clearTimeout(timer);
      controller?.abort();
      session = undefined;
      window.removeEventListener('pagehide', flow.dispose);
    },
    pause() {
      paused = true;
      generation++;
      clearTimeout(timer);
      controller?.abort();
      running = false;
    },
    resume() {
      if (!paused || disposed) return;
      paused = false;
      void tick();
    }
  };
  window.__bahaQrLogin = flow;
  window.addEventListener('pagehide', flow.dispose);
  function live() {
    return !disposed && window.__bahaQrLogin === flow && window.__bahaDocumentNonce === ticket;
  }
  function post(value) { if (live()) bahaPostJSON(JSON.stringify(value)); }
  function finish(type) {
    post({type:type});
    flow.dispose();
  }
  function validLink(raw) {
    if (typeof raw !== 'string' || raw.length > 2048) return false;
    try {
      const url = new URL(raw);
      const token = url.searchParams.get('token');
      return url.origin === 'https://user.gamer.com.tw' && !url.username &&
        !url['pass' + 'word'] && !url.hash && url.pathname === '/link_login.php' &&
        Array.from(url.searchParams).length === 1 &&
        typeof token === 'string' && token.length >= 32 && token.length <= 1024;
    } catch (_) { return false; }
  }
  async function request(name, fields, current) {
    const endpoint = 'https://api.gamer.com.tw/user/v1/' + name + '.php';
    // Use the same double-submit cookie/header mechanism as the official site.
    const csrf = new window.Bahamut.Csrf();
    csrf.setCookie();
    const token = String(csrf);
    if (!/^[a-f0-9]{16}$/.test(token)) throw Error('Invalid CSRF state');
    const abort = new AbortController();
    controller = abort;
    const timeout = setTimeout(() => abort.abort(), 12000);
    try {
      const response = await fetch(endpoint, {
        method:'POST', credentials:'include', cache:'no-store', redirect:'error',
        headers:{'X-Bahamut-Csrf-Token':token, 'Content-Type':'application/x-www-form-urlencoded'},
        body:new URLSearchParams(fields).toString(), signal:abort.signal
      });
      if (!response.ok || response.url !== endpoint) throw Error('Official login unavailable');
      const body = await response.json();
      if (!current()) return;
      if (!body || typeof body !== 'object' || Array.isArray(body)) throw Error('Invalid login response');
      return body;
    } finally {
      clearTimeout(timeout);
      if (controller === abort) controller = undefined;
    }
  }
  async function tick() {
    if (!live() || paused || running) return;
    if (session && session.expiredAt * 1000 <= Date.now()) { finish('qr-login-expired'); return; }
    running = true;
    const expected = ++generation;
    const current = () => live() && !paused && generation === expected;
    try {
      if (!session) {
        const body = await request('link_login_request', {}, current);
        if (!current()) return;
        if (body.error) { finish('qr-login-error'); return; }
        const data = body.data, now = Date.now() / 1000;
        if (!data || !/^[a-f0-9]{64}$/i.test(data.sessionId || '') ||
            !Number.isSafeInteger(data.expiredAt) || data.expiredAt <= now ||
            data.expiredAt > now + 600 || !validLink(data.link)) {
          finish('qr-login-error'); return;
        }
        session = {sessionId:data.sessionId, expiredAt:data.expiredAt};
        post({type:'qr-login-ready', link:data.link, expiresAt:data.expiredAt});
      } else {
        const body = await request('link_login', {sessionId:session.sessionId}, current);
        if (!current()) return;
        if (session.expiredAt * 1000 <= Date.now()) { finish('qr-login-expired'); return; }
        if (body.error) {
          if (body.error.status !== 'NOT_YET') { finish('qr-login-error'); return; }
        } else {
          // Cookie installation is handled by the official credentialed response.
          // The native host reloads and verifies the official account identity.
          if (body.data == null) { finish('qr-login-error'); return; }
          finish('qr-login-complete');
          return;
        }
      }
      failures = 0;
    } catch (_) {
      if (!current()) return;
      if (++failures >= 3) { finish('qr-login-error'); return; }
      post({type:'qr-login-retrying'});
    } finally {
      if (current()) {
        running = false;
        timer = setTimeout(() => { void tick(); }, failures ? 3000 : 2000);
      }
    }
  }
  void tick();
})(); true;
