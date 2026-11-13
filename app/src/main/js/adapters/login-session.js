(function() {
  if (location.origin !== 'https://ani.gamer.com.tw') return;
  window.__bahaLoginSession?.dispose();
  const ticket = window.__bahaDocumentNonce;
  let disposed = false, timer;
  const flow = {dispose() {
    disposed = true;
    clearInterval(timer);
    window.removeEventListener('pagehide', flow.dispose);
    window.removeEventListener('message', finished);
  }};
  window.__bahaLoginSession = flow;
  function check() {
    if (disposed || ticket !== window.__bahaDocumentNonce) return flow.dispose();
    const login = window.User?.Login;
    const id = typeof login?.getUserid === 'function' ? login.getUserid() : null;
    if (typeof id === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(id) && id.toLowerCase() !== 'guest') {
      // Signal only. The native HTTP account request verifies the actual owner.
      bahaPostJSON(JSON.stringify({type:'login-complete'}));
      flow.dispose();
    }
  }
  function finished(event) {
    if (event.origin === 'https://user.gamer.com.tw' && event.data === 'LoginFinish' &&
        Array.from(document.querySelectorAll('iframe')).some(frame => frame.contentWindow === event.source)) check();
  }
  window.addEventListener('message', finished);
  window.addEventListener('pagehide', flow.dispose);
  timer = setInterval(check, 500);
  check();
})(); true;
