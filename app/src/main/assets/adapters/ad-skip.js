(function () {
  if (location.origin !== 'https://ani.gamer.com.tw' || window.__bahaCreateAdSkip) return;
  window.__bahaCreateAdSkip = function ({context, isCurrent}) {
    let submitted = null;
    function current() {
      if (!isCurrent() || window.__bahaMediaSuspended === true) return null;
      const c = context();
      if (!c.matches || c.phase !== 'advertisement') { submitted = null; return null; }
      const node = c.root?.querySelector('.vast-skip-button');
      if (!node) { submitted = null; return null; }
      const rect = node.getBoundingClientRect(), style = getComputedStyle(node);
      if (!(rect.width > 0 && rect.height > 0) || style.display === 'none' ||
          style.visibility === 'hidden' || !(Number(style.opacity) > 0)) {
        submitted = null; return null;
      }
      const label = node.textContent.replace(/\s+/g, ' ').trim().slice(0, 120);
      // The same element shows a countdown/subscription link before the
      // official player unlocks skipping. Only its ready label is actionable.
      const ready = /^(?:(?:點此|點擊|点击)?跳[過过][廣广]告|skipad)$/i.test(label.replace(/\s+/g, ''));
      if (!ready) submitted = null;
      const available = ready && !node.disabled && node.getAttribute('disabled') === null &&
        node.getAttribute('aria-disabled') !== 'true' && style.pointerEvents !== 'none' &&
        !node.classList.contains('disabled') && !node.classList.contains('vjs-disabled') &&
        node !== submitted;
      return {node, label, available};
    }
    return {
      status: () => {
        const value = current();
        const countdown = value?.label.match(/(?:廣告|广告)\s*(\d{1,4})\s*秒/);
        return value ? {visible: value.node !== submitted, available: value.available,
          remainingSeconds: countdown ? Number(countdown[1]) : null} :
          {visible: false, available: false, remainingSeconds: null};
      },
      skip: () => {
        const value = current();
        if (!value?.available) return false;
        submitted = value.node;
        try { value.node.click(); return true; }
        catch (_) { submitted = null; return false; }
      }
    };
  };
})(); true;
