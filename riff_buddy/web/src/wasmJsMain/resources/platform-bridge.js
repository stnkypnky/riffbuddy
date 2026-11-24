// The page's side of the app's platform: the file picker, saving and
// sharing, links, Back, keeping the screen on, permissions and notices.
window.rbPage = {
  // Opens the file picker. Calls back with (name, bytes), or (null, null) if nothing was picked.
  pick(accept, onPicked) {
    const input = document.createElement('input');
    input.type = 'file';
    if (accept) input.accept = accept;
    input.style.display = 'none';
    let done = false;
    input.onchange = async () => {
      done = true;
      const f = input.files && input.files[0];
      input.remove();
      if (!f) return onPicked(null, null);
      onPicked(f.name, new Uint8Array(await f.arrayBuffer()));
    };
    // A picker closed without a choice fires no change; focus returning says so.
    window.addEventListener('focus', () => setTimeout(() => { if (!done) { input.remove(); onPicked(null, null); } }, 1000), { once: true });
    document.body.appendChild(input);
    input.click();
  },

  // Saves bytes as a download, the one way every browser can.
  save(name, type, bytes) {
    const url = URL.createObjectURL(new Blob([bytes], { type: type || 'application/octet-stream' }));
    const a = document.createElement('a');
    a.href = url; a.download = name; a.style.display = 'none';
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  },

  // The system share sheet where there is one; otherwise a download. Answers how it went.
  async share(name, type, bytes, title) {
    const file = new File([bytes], name, { type });
    if (navigator.canShare && navigator.canShare({ files: [file] })) {
      try { await navigator.share({ files: [file], title }); return 'shared'; }
      catch (e) { if (e.name === 'AbortError') return 'canceled'; }
    }
    this.save(name, type, bytes);
    return 'saved';
  },

  open(url) { window.open(url, '_blank', 'noopener'); },

  // Back: one history entry stands for "inside the app", put back each time
  // it is used, so the phone's Back gesture or the browser's button comes here.
  onBack: null,
  armBack(handler) {
    this.onBack = handler;
    if (!history.state || !history.state.riffbuddy) history.pushState({ riffbuddy: 1 }, '');
    window.addEventListener('popstate', () => {
      if (this.onBack && this.onBack()) history.pushState({ riffbuddy: 1 }, '');
      else history.back();
    });
  },

  wake: null,
  async keepOn(on) {
    try {
      if (on && !this.wake && navigator.wakeLock) this.wake = await navigator.wakeLock.request('screen');
      if (!on && this.wake) { await this.wake.release(); this.wake = null; }
    } catch (e) { this.wake = null; }
  },

  micGranted: false,
  async askMic() {
    try {
      const s = await navigator.mediaDevices.getUserMedia({ audio: true });
      s.getTracks().forEach(t => t.stop());
      this.micGranted = true;
    } catch (e) { this.micGranted = false; }
    return this.micGranted;
  },
  async checkMic() {
    try {
      const p = await navigator.permissions.query({ name: 'microphone' });
      this.micGranted = p.state === 'granted';
      p.onchange = () => { this.micGranted = p.state === 'granted'; };
    } catch (e) {}
  },
  notifyGranted() { return 'Notification' in window && Notification.permission === 'granted'; },
  async askNotify() { return 'Notification' in window && (await Notification.requestPermission()) === 'granted'; },
  notify(title, text) {
    if (this.notifyGranted()) new Notification(title, { body: text, icon: 'icons/icon-192.png' });
  },

  // Files dropped onto the page: each calls back as a pick does.
  acceptDrops(onDropped) {
    document.addEventListener('dragover', e => e.preventDefault());
    document.addEventListener('drop', async e => {
      e.preventDefault();
      for (const f of e.dataTransfer.files) onDropped(f.name, new Uint8Array(await f.arrayBuffer()));
    });
  },

  // Decodes a compressed recording into mono samples at [rate]; resolves to a Float32Array.
  async decode(bytes, rate) {
    const ctx = new OfflineAudioContext(1, 1, rate);
    const buf = await ctx.decodeAudioData(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
    const out = new Float32Array(buf.length);
    for (let c = 0; c < buf.numberOfChannels; c++) {
      const ch = buf.getChannelData(c);
      for (let i = 0; i < out.length; i++) out[i] += ch[i] / buf.numberOfChannels;
    }
    return out;
  },
};
