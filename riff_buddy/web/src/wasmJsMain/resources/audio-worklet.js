// The audio thread's side. Each output node holds a ring of samples the page
// has rendered ahead, and plays from it; it reports back how far it has
// played, so the page knows when to render more and where the beat is. The
// input node passes the microphone's samples to the page in blocks.

class RiffBuddyOutput extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ring = new Float32Array(1 << 17);
    this.mask = this.ring.length - 1;
    this.read = 0; this.write = 0;
    this.playing = false; this.played = 0; this.underruns = 0; this.quanta = 0;
    this.port.onmessage = (e) => {
      const m = e.data;
      if (m.samples) {
        const s = m.samples;
        for (let i = 0; i < s.length; i++) this.ring[(this.write + i) & this.mask] = s[i];
        this.write += s.length;
      } else if (m.cmd === 'play') this.playing = true;
      else if (m.cmd === 'pause') this.playing = false;
      else if (m.cmd === 'flush') { this.read = this.write; }
    };
  }

  process(inputs, outputs) {
    const out = outputs[0][0];
    if (this.playing) {
      const have = this.write - this.read;
      const n = Math.min(have, out.length);
      for (let i = 0; i < n; i++) out[i] = this.ring[(this.read + i) & this.mask];
      this.read += n;
      this.played += n;
      // Counted once sound has begun: a quantum before the first samples
      // arrive is silence ahead of the start, not a dropout.
      if (n < out.length && this.played > n) this.underruns++;
    }
    for (let c = 1; c < outputs[0].length; c++) outputs[0][c].set(out);
    if ((++this.quanta & 3) === 0) {
      this.port.postMessage({ played: this.played, buffered: this.write - this.read, underruns: this.underruns });
    }
    return true;
  }
}
registerProcessor('riffbuddy-output', RiffBuddyOutput);

class RiffBuddyInput extends AudioWorkletProcessor {
  constructor() {
    super();
    this.block = new Float32Array(1024); this.at = 0; this.frame = 0;
  }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (ch) {
      for (let i = 0; i < ch.length; i++) {
        this.block[this.at++] = ch[i];
        if (this.at === this.block.length) {
          this.port.postMessage({ frame: this.frame, samples: this.block }, [this.block.buffer]);
          this.frame += this.block.length;
          this.block = new Float32Array(1024); this.at = 0;
        }
      }
    }
    return true;
  }
}
registerProcessor('riffbuddy-input', RiffBuddyInput);
