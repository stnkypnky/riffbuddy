// The page's side of sound: audio contexts, one per sample rate the app asks
// for; output streams, each a worklet node with its own ring; and the raw
// microphone. The app talks to streams by number.
window.rbAudio = {
  contexts: {}, streams: {}, inputs: {}, next: 1,

  async context(rate) {
    let c = this.contexts[rate];
    if (!c) {
      c = { ctx: new AudioContext({ latencyHint: 'interactive', sampleRate: rate }) };
      c.ready = c.ctx.audioWorklet.addModule('audio-worklet.js');
      // Browsers start audio suspended until the page is touched.
      const resume = () => { if (c.ctx.state !== 'running') c.ctx.resume(); };
      ['pointerdown', 'keydown', 'touchend'].forEach(t => document.addEventListener(t, resume, true));
      resume();
      this.contexts[rate] = c;
    }
    await c.ready;
    return c.ctx;
  },

  // A new output stream; it starts silent and paused. Returns its number at once.
  open(rate) {
    const id = this.next++;
    const s = { id, rate, node: null, gain: null, pending: [], played: 0, buffered: 0, underruns: 0, latency: 0, cmds: [], volume: 1 };
    this.streams[id] = s;
    this.context(rate).then(ctx => {
      if (!this.streams[id]) return;
      s.node = new AudioWorkletNode(ctx, 'riffbuddy-output', { outputChannelCount: [1] });
      s.gain = ctx.createGain();
      s.gain.gain.value = s.volume;
      s.node.connect(s.gain).connect(ctx.destination);
      s.node.port.onmessage = (e) => {
        s.played = e.data.played; s.buffered = e.data.buffered; s.underruns = e.data.underruns;
        s.reportedAt = performance.now();
        s.latency = (ctx.outputLatency || 0) + (ctx.baseLatency || 0);
      };
      s.pending.forEach(b => s.node.port.postMessage({ samples: b }, [b.buffer]));
      s.pending = [];
      s.cmds.forEach(c => s.node.port.postMessage({ cmd: c }));
      s.cmds = [];
    });
    return id;
  },

  // Samples arrive in three steps, so a block crosses over without one call per sample being a message.
  block: null,
  begin(n) { this.block = new Float32Array(n); },
  set(i, v) { this.block[i] = v; },
  send(id) {
    const s = this.streams[id], b = this.block;
    this.block = null;
    if (!s) return;
    if (s.node) s.node.port.postMessage({ samples: b }, [b.buffer]); else s.pending.push(b);
  },

  cmd(id, c) {
    const s = this.streams[id];
    if (!s) return;
    if (c === 'play') s.playing = true; else if (c === 'pause') s.playing = false;
    if (s.node) s.node.port.postMessage({ cmd: c }); else s.cmds.push(c);
  },
  // Sound playing or the microphone open: the app is in use even with the
  // screen off, so an update waits.
  busy() {
    return Object.values(this.streams).some(s => s.playing) || Object.keys(this.inputs).length > 0;
  },
  volume(id, v) { const s = this.streams[id]; if (s) { s.volume = v; if (s.gain) s.gain.gain.value = v; } },
  played(id) { const s = this.streams[id]; return s ? s.played : 0; },
  underruns(id) { const s = this.streams[id]; return s ? s.underruns : 0; },
  // When the last reported frame reaches the speaker, on the page's clock in ms; -1 if not known yet.
  heardAt(id) { const s = this.streams[id]; return s && s.reportedAt ? s.reportedAt + s.latency * 1000 : -1; },
  close(id) {
    const s = this.streams[id];
    delete this.streams[id];
    if (s && s.node) { s.node.port.postMessage({ cmd: 'flush' }); s.node.disconnect(); s.gain.disconnect(); }
  },

  // The raw microphone: no echo cancellation, noise suppression or automatic
  // gain, any of which would treat a held guitar note as noise to fade.
  // The audio inputs the browser offers, refreshed when one is plugged in or
  // pulled out. Names stay blank until the microphone has been allowed once,
  // so the list is read again after each grant.
  devices: [],
  devicesChanged: null,
  async refreshDevices() {
    try {
      const all = await navigator.mediaDevices.enumerateDevices();
      // "default" and "communications" are aliases of a real device listed beside them.
      this.devices = all.filter(d => d.kind === 'audioinput' && d.deviceId &&
        d.deviceId !== 'default' && d.deviceId !== 'communications');
    } catch (e) { this.devices = []; }
    if (this.devicesChanged) this.devicesChanged();
  },
  watchDevices() {
    if (this.watching || !navigator.mediaDevices) return;
    this.watching = true;
    navigator.mediaDevices.addEventListener('devicechange', () => this.refreshDevices());
    this.refreshDevices();
  },
  deviceCount() { return this.devices.length; },
  deviceId(i) { return this.devices[i].deviceId; },
  deviceLabel(i) { return this.devices[i].label || ''; },

  openInput(rate, deviceId) {
    const id = this.next++;
    const inp = { id, chunks: [], at: 0, available: 0, error: null, ready: false, frame: 0, frameAt: 0 };
    this.inputs[id] = inp;
    (async () => {
      try {
        inp.stream = await navigator.mediaDevices.getUserMedia({ audio: {
          echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1,
          ...(deviceId ? { deviceId: { exact: deviceId } } : {}),
        } });
        this.refreshDevices();
        const ctx = await this.context(rate);
        inp.source = ctx.createMediaStreamSource(inp.stream);
        inp.node = new AudioWorkletNode(ctx, 'riffbuddy-input', { numberOfInputs: 1, numberOfOutputs: 0 });
        inp.node.port.onmessage = (e) => {
          inp.chunks.push(e.data.samples); inp.available += e.data.samples.length;
          inp.frame = e.data.frame + e.data.samples.length; inp.frameAt = performance.now();
          // Never more than two seconds waiting: a reader that fell behind skips ahead.
          while (inp.available > rate * 2) { inp.available -= inp.chunks[0].length - inp.at; inp.chunks.shift(); inp.at = 0; }
        };
        inp.source.connect(inp.node);
        if (!this.inputs[id]) this.closeInput(id, inp);
        inp.ready = true;
      } catch (e) {
        inp.error = String((e && e.name) || e);
      }
    })();
    return id;
  },
  inputAvailable(id) { const i = this.inputs[id]; return i ? (i.error ? -1 : i.available) : -1; },
  // Moves [n] samples into the take buffer; the app then reads them one by one.
  take: null,
  takeInput(id, n) {
    const inp = this.inputs[id];
    this.take = new Float32Array(n);
    let got = 0;
    while (got < n && inp.chunks.length) {
      const c = inp.chunks[0];
      const k = Math.min(n - got, c.length - inp.at);
      this.take.set(c.subarray(inp.at, inp.at + k), got);
      got += k; inp.at += k; inp.available -= k;
      if (inp.at === c.length) { inp.chunks.shift(); inp.at = 0; }
    }
    return got;
  },
  takeAt(i) { return this.take[i]; },
  inputFrame(id) { const i = this.inputs[id]; return i ? i.frame : 0; },
  inputFrameAt(id) { const i = this.inputs[id]; return i ? i.frameAt : -1; },
  closeInput(id, given) {
    const inp = given || this.inputs[id];
    delete this.inputs[id];
    if (!inp) return;
    if (inp.source) inp.source.disconnect();
    if (inp.node) inp.node.disconnect();
    if (inp.stream) inp.stream.getTracks().forEach(t => t.stop());
  },
};
