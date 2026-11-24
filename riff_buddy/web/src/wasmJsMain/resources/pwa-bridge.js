// The page as an installable app: the service worker that keeps it working
// offline, the "new version" notice, asking the browser to keep the data once
// installed, and, on an iPhone or iPad, how to install it.
window.rbPwa = {
  installed() {
    return window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
  },

  start() {
    if ('serviceWorker' in navigator) {
      let agreed = false;
      navigator.serviceWorker.register('sw.js').then((reg) => {
        const offer = () => {
          if (reg.waiting && navigator.serviceWorker.controller) {
            this.banner('A new version of Riff Buddy is ready.', 'Reload', () => { agreed = true; reg.waiting.postMessage('update'); });
          }
        };
        offer();
        reg.addEventListener('updatefound', () => {
          const w = reg.installing;
          if (w) w.addEventListener('statechange', () => { if (w.state === 'installed') offer(); });
        });
        // Checked again whenever the app comes back to the front.
        document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') reg.update(); });
      }).catch((e) => console.warn('rbPwa: no offline support here', e));
      // The first install takes control without a reload; only an agreed update reloads.
      navigator.serviceWorker.addEventListener('controllerchange', () => { if (agreed) location.reload(); });
    }
    // Installed, the browser keeps the data even when space runs short. Safari
    // in particular clears a site's storage after weeks unvisited unless it is.
    if (this.installed() && navigator.storage && navigator.storage.persist) navigator.storage.persist();
    const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    let told = false;
    try { told = localStorage.getItem('riffbuddy.installTip') === '1'; } catch (e) {}
    if (ios && !this.installed() && !told) {
      this.banner('To keep your practice safe on this device, install Riff Buddy: tap Share, then Add to Home Screen.', 'Got it',
        () => { try { localStorage.setItem('riffbuddy.installTip', '1'); } catch (e) {} });
    }
  },

  banner(text, action, onAction) {
    const old = document.getElementById('rb-banner');
    if (old) old.remove();
    const bar = document.createElement('div');
    bar.id = 'rb-banner';
    bar.setAttribute('role', 'status');
    bar.style.cssText = 'position:fixed;left:12px;right:12px;bottom:calc(12px + env(safe-area-inset-bottom));z-index:10;' +
      'display:flex;gap:12px;align-items:center;padding:12px 16px;border-radius:12px;background:#40372E;color:#F7F0E4;' +
      'font:15px/1.4 system-ui,sans-serif;box-shadow:0 4px 16px rgba(0,0,0,.4);max-width:560px;margin:0 auto;';
    const msg = document.createElement('div');
    msg.style.flex = '1';
    msg.textContent = text;
    const btn = document.createElement('button');
    btn.textContent = action;
    btn.style.cssText = 'min-height:44px;padding:0 16px;border:0;border-radius:22px;background:#FFB43D;color:#241F1B;font:600 15px system-ui,sans-serif;';
    btn.onclick = () => { bar.remove(); onAction(); };
    bar.append(msg, btn);
    document.body.appendChild(bar);
  },
};
window.addEventListener('load', () => rbPwa.start());
