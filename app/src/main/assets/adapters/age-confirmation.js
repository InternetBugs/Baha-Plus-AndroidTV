(function () {
  if (location.origin !== 'https://ani.gamer.com.tw' || window.__bahaCreateAgeConfirmation) return;
  window.__bahaCreateAgeConfirmation = function ({context, isCurrent}) {
    let submitted = null;
    const visible = node => {
      if (!node || node.disabled || node.getAttribute('aria-disabled') === 'true') return false;
      const rect = node.getBoundingClientRect(), style = getComputedStyle(node);
      return rect.width > 0 && rect.height > 0 && style.display !== 'none' &&
        style.visibility !== 'hidden' && Number(style.opacity) > 0;
    };
    function button() {
      if (!isCurrent() || window.__bahaMediaSuspended === true) return null;
      const c = context();
      if (!c.matches || c.phase !== 'age') { submitted = null; return null; }
      const prompt = c.root?.querySelector('.ncc-choosebar');
      if (!visible(prompt)) return null;
      const agree = prompt.querySelector('button#adult.choose-btn-agree');
      // Match the official agreement precisely; "不同意" also contains "同意".
      if (!visible(agree) || agree.textContent.trim() !== '同意' || agree === submitted) return null;
      return agree;
    }
    return {
      available: () => !!button(),
      confirm: () => {
        const agree = button();
        if (!agree) return false;
        submitted = agree;
        try { agree.click(); return true; }
        catch (_) { submitted = null; return false; }
      }
    };
  };
})(); true;
