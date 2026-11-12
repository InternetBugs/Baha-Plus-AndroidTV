(function () {
  'use strict';
  if (location.origin !== 'https://ani.gamer.com.tw') return;
  const post = value => bahaPostJSON(JSON.stringify(value));
  const id = value => typeof value === 'string' && /^\d{1,10}$/.test(value);
  const image = raw => {
    try {
      const url = new URL(raw);
      return url.protocol === 'https:' && url.hostname.endsWith('.bahamut.com.tw') &&
        !url.username && !url.password && (!url.port || url.port === '443');
    } catch (_) { return false; }
  };
  async function search(queries) {
    if (!Array.isArray(queries) || queries.length < 1 || queries.length > 2 ||
        queries.some(value => typeof value !== 'string' || !value.trim() || [...value].length > 100)) {
      post({type: 'search-error'}); return;
    }
    const records = new Map();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 12000);
    try {
      for (const query of new Set(queries)) {
        const endpoint = new URL('https://api.gamer.com.tw/anime/v1/search.php');
        endpoint.searchParams.set('kw', query);
        const response = await fetch(endpoint.href, {credentials: 'include', signal: controller.signal});
        const target = new URL(response.url);
        if (!response.ok || target.origin !== endpoint.origin || target.pathname !== endpoint.pathname) throw Error('Search unavailable');
        const body = await response.json(), list = body?.data?.animeList;
        if (!Array.isArray(list) || list.length > 5000) throw Error('Search format changed');
        for (const raw of list) {
          const sn = String(raw.animeSn), video = String(raw.videoSn);
          if (!id(sn) || typeof raw.title !== 'string' || !raw.title.trim() ||
              raw.title.length >= 500 || !image(raw.cover)) throw Error('Invalid search record');
          records.set(sn, {id: sn, title: raw.title.trim(), cover: raw.cover,
            latestVideoId: id(video) ? video : undefined,
            episodeLabel: Number.isInteger(raw.totalEpisode) && raw.totalEpisode > 0 ? String(raw.totalEpisode) + ' 集' : '',
            sections: []});
        }
      }
      post({type: 'search', items: [...records.values()]});
    } catch (_) { post({type: 'search-error'}); }
    finally { clearTimeout(timer); }
  }
  async function favorite(animeId, desired) {
    if (!id(animeId) || typeof desired !== 'boolean') { post({type: 'favorite-error'}); return; }
    const login = window.User?.Login;
    const owner = typeof login?.getUserid === 'function' ? login.getUserid() : null;
    const loggedIn = typeof login?.getUserid === 'function'
      ? typeof owner === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(owner) && owner.toLowerCase() !== 'guest'
      : !!document.querySelector('.btn-logout');
    if (!loggedIn) { post({type: 'account-guest'}); return; }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 12000);
    let holder;
    try {
      const response = await fetch('/animeRef.php?sn=' + animeId, {credentials: 'same-origin', signal: controller.signal});
      const target = new URL(response.url);
      if (!response.ok || target.origin !== location.origin ||
          !['/animeRef.php', '/animeVideo.php'].includes(target.pathname)) throw Error('Unavailable');
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
      const button = doc.querySelector('.anime_name button[onclick*="toggleGather"]');
      const match = button?.getAttribute('onclick')?.match(/toggleGather\(\d+,\s*(\d+)/);
      if (match?.[1] !== animeId || typeof window.toggleGather !== 'function') throw Error('Official control unavailable');
      holder = document.createElement('div');
      holder.hidden = true;
      holder.dataset.bahaFavorite = animeId;
      holder.append(document.importNode(button, true));
      document.body.append(holder);
      const control = holder.firstElementChild;
      const selectedClass = control.getAttribute('onclick').includes('btn-is-active') ? 'btn-is-active' : 'ordered';
      if (control.classList.contains(selectedClass) === desired) {
        post({type: 'favorite-complete', id: animeId, saved: desired}); return;
      }
      await new Promise((resolve, reject) => {
        const observer = new MutationObserver(() => {
          if (control.classList.contains(selectedClass) === desired && !control.classList.contains('actionLock')) {
            cleanup(); resolve();
          }
        });
        const timeout = setTimeout(() => { cleanup(); reject(Error('Timed out')); }, 15000);
        function cleanup() { clearTimeout(timeout); observer.disconnect(); }
        observer.observe(control, {attributes: true, attributeFilter: ['class']});
        control.click();
      });
      post({type: 'favorite-complete', id: animeId, saved: desired});
    } catch (_) { post({type: 'favorite-error', id: animeId}); }
    finally { clearTimeout(timer); holder?.remove(); }
  }
  window.__bahaTVCommands = {search, favorite};
})(); true;
