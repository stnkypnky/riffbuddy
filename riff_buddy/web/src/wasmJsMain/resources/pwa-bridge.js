// The page as an installable app: the service worker that keeps it working
// offline, updating itself to a new version, asking the browser to keep the
// data once installed, and, on an iPhone or iPad, how to install it.
window.rbPwa = {
  installed() {
    return window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
  },

  start() {
    if ('serviceWorker' in navigator) {
      // A new version updates itself, but only at a moment that interrupts
      // nothing: before the player has touched the app since it opened, or
      // while it's in the background with no sound playing and the
      // microphone closed. The data is saved first, then the page reloads.
      let touched = false, applying = false;
      ['pointerdown', 'keydown'].forEach(t => document.addEventListener(t, () => { touched = true; }, { capture: true, once: true }));
      const busy = () => !!((window.rbAudio && rbAudio.busy()) || (window.rbFiles && rbFiles.pending > 0));
      navigator.serviceWorker.register('sw.js', { updateViaCache: 'none' }).then((reg) => {
        const apply = async () => {
          if (applying || !reg.waiting || !navigator.serviceWorker.controller) return;
          if (busy() || !(document.visibilityState === 'hidden' || !touched)) return;
          applying = true;
          try { if (window.rbDb && rbDb.saveNow) await rbDb.saveNow(); } catch (e) {}
          // Busy again while saving: try at the next quiet moment instead.
          if (busy() || !reg.waiting) { applying = false; return; }
          reg.waiting.postMessage('update');
        };
        apply();
        reg.addEventListener('updatefound', () => {
          const w = reg.installing;
          if (w) w.addEventListener('statechange', () => { if (w.state === 'installed') apply(); });
        });
        document.addEventListener('visibilitychange', () => {
          if (document.visibilityState === 'visible') reg.update(); else apply();
        });
        // Left open for hours, it still looks for a new version, and a
        // metronome that ran on with the screen off updates once it stops.
        setInterval(() => { if (document.visibilityState === 'visible') reg.update(); }, 30 * 60 * 1000);
        setInterval(apply, 30 * 1000);
      }).catch((e) => console.warn('rbPwa: no offline support here', e));
      // The first install takes control without a reload; only an update reloads.
      navigator.serviceWorker.addEventListener('controllerchange', () => { if (applying) location.reload(); });
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
