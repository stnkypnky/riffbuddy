// The page's side of the tab player: alphaTab's own JavaScript build, the
// same version the other editions run, drawing into an element the app places
// on screen and playing through its synthesizer. The app keeps the player's
// logic (loops, memory, which track); this answers its commands and reports
// alphaTab's events back as (name, JSON).
window.rbTabs = {
  players: {}, next: 1,

  create(el, horizontal, zoom, onEvent) {
    const id = this.next++;
    el.style.overflow = 'auto';
    el.style.background = '#F6F1E7';
    const inner = document.createElement('div');
    el.appendChild(inner);
    const api = new alphaTab.AlphaTabApi(inner, {
      core: { fontDirectory: 'alphatab/font/', logLevel: 'warning' },
      display: { scale: zoom, layoutMode: horizontal ? alphaTab.LayoutMode.Horizontal : alphaTab.LayoutMode.Page },
      player: { enablePlayer: true, enableCursor: true, enableUserInteraction: true,
        soundFont: 'alphatab/soundfont/sonivox.sf2', scrollElement: el },
    });
    const p = { id, api, el, score: null, shown: 0, tabOnly: true, hidden: false, emit: (n, o) => onEvent(n, JSON.stringify(o || {})) };
    this.players[id] = p;
    let lastMs = -1, lastTick = -1, lastEnd = -1;
    api.renderFinished.on(() => p.emit('drawn'));
    api.playerReady.on(() => p.emit('ready'));
    api.midiLoaded.on(() => p.emit('midi'));
    api.playerStateChanged.on(e => p.emit('state', { playing: e.state === alphaTab.synth.PlayerState.Playing }));
    api.playerPositionChanged.on(e => {
      // Tenths of a second and whole beats are all the screen uses.
      const ms = Math.floor(e.currentTime / 100) * 100, tick = Math.floor(e.currentTick / 960) * 960, end = Math.floor(e.endTime);
      if (ms === lastMs && tick === lastTick && end === lastEnd) return;
      lastMs = ms; lastTick = tick; lastEnd = end;
      p.emit('pos', { ms, tick, end });
    });
    api.beatMouseUp.on(beat => { if (beat) p.emit('bar', { bar: beat.voice.bar.masterBar.index + 1 }); });
    api.playbackRangeHighlightChanged.on(e => { if (p.hidden && e.startBeat) api.clearPlaybackRangeHighlight(); });
    api.error.on(e => p.emit('error', { message: String((e && e.message) || e) }));
    return id;
  },

  // Tab on fretted staves, notation above it when asked; a drum staff is left as it is.
  staves(p) {
    for (const t of p.score.tracks) for (const s of t.staves) {
      if (s.isPercussion || s.tuning.length === 0) continue;
      s.showTablature = true;
      s.showStandardNotation = !p.tabOnly;
    }
  },

  // A guitar if there is one, else anything fretted, else the first.
  firstTrack(score) {
    let fretted = -1;
    for (let i = 0; i < score.tracks.length; i++) {
      const t = score.tracks[i];
      const frets = t.staves.length > 0 && !t.staves[0].isPercussion && t.staves[0].tuning.length > 0;
      if (frets && t.playbackInfo.program >= 24 && t.playbackInfo.program <= 31) return i;
      if (frets && fretted < 0) fretted = i;
    }
    return fretted >= 0 ? fretted : 0;
  },

  open(id, bytes, isTex, generated, track) {
    const p = this.players[id];
    try {
      const score = isTex
        ? alphaTab.importer.ScoreLoader.loadAlphaTex(new TextDecoder().decode(bytes))
        : alphaTab.importer.ScoreLoader.loadScoreFromBytes(bytes);
      if (!generated) {
        new alphaTab.midi.MidiFileGenerator(score, new alphaTab.Settings(), new alphaTab.midi.AlphaSynthMidiFileHandler(new alphaTab.midi.MidiFile())).generate();
      }
      p.score = score;
      this.staves(p);
      p.shown = (track >= 0 && track < score.tracks.length) ? track : this.firstTrack(score);
      p.emit('loaded', { tracks: score.tracks.map((t, i) => (t.name && t.name.trim()) ? t.name : 'Track ' + (i + 1)), shown: p.shown });
      p.api.renderScore(score, [p.shown]);
    } catch (e) {
      p.emit('error', { message: String((e && e.message) || e) });
    }
  },

  bars(p) {
    const t = p.score && p.score.tracks[p.shown];
    return t && t.staves[0] ? t.staves[0].bars : [];
  },

  // Bars [start, end], 1-based, through alphaTab's own highlight-then-apply,
  // which knows about repeats and tempo changes.
  range(id, start, end, keepPosition, visible) {
    const p = this.players[id], api = p.api, bars = this.bars(p);
    const a = bars[start - 1], b = bars[end - 1];
    if (!a || !b) return;
    const first = a.voices[0].beats[0], last = b.voices[0].beats[b.voices[0].beats.length - 1];
    const tick = api.tickPosition;
    p.hidden = !visible;
    api.highlightPlaybackRange(first, last);
    api.applyPlaybackRangeFromHighlight();
    api.isLooping = true;
    if (!visible) api.clearPlaybackRangeHighlight();
    if (keepPosition) api.tickPosition = tick;
  },

  barCount(id) { return this.bars(this.players[id]).length; },

  clearRange(id) {
    const p = this.players[id];
    p.hidden = false;
    p.api.isLooping = false;
    p.api.playbackRange = null;
    p.api.clearPlaybackRangeHighlight();
  },

  cmd(id, name, value) {
    const p = this.players[id];
    if (!p) return;
    const api = p.api, t = p.score && p.score.tracks[Math.floor(value)];
    switch (name) {
      case 'playPause': api.playPause(); break;
      case 'stop': api.stop(); break;
      case 'speed': api.playbackSpeed = value; break;
      case 'countIn': api.countInVolume = value; break;
      case 'metronome': api.metronomeVolume = value; break;
      case 'time': api.timePosition = value; break;
      case 'show': if (t) { p.shown = Math.floor(value); api.renderScore(p.score, [p.shown]); } break;
      case 'zoom': api.settings.display.scale = value; api.updateSettings(); api.render(); break;
      case 'horizontal': api.settings.display.layoutMode = value ? alphaTab.LayoutMode.Horizontal : alphaTab.LayoutMode.Page; api.updateSettings(); api.render(); break;
      case 'tabOnly': p.tabOnly = !!value; if (p.score) { this.staves(p); api.renderScore(p.score, [p.shown]); } break;
    }
  },

  track(id, index, what, value) {
    const p = this.players[id], t = p && p.score && p.score.tracks[index];
    if (!t) return;
    if (what === 'mute') p.api.changeTrackMute([t], !!value);
    else if (what === 'solo') p.api.changeTrackSolo([t], !!value);
    else p.api.changeTrackVolume([t], value);
  },

  destroy(id) {
    const p = this.players[id];
    delete this.players[id];
    if (p) { try { p.api.destroy(); } catch (e) {} }
  },
};

// Reading a tab file for the library, with alphaTab's own importers: what is
// in it, and whether it will play. Answers in JSON, so one call crosses over.
window.rbTabFiles = {
  summary(bytes, isTex) {
    let score;
    try {
      score = isTex
        ? alphaTab.importer.ScoreLoader.loadAlphaTex(new TextDecoder().decode(bytes))
        : alphaTab.importer.ScoreLoader.loadScoreFromBytes(bytes);
    } catch (e) {
      return JSON.stringify({ error: String((e && e.message) || e) });
    }
    try {
      const midi = new alphaTab.midi.MidiFile();
      new alphaTab.midi.MidiFileGenerator(score, new alphaTab.Settings(), new alphaTab.midi.AlphaSynthMidiFileHandler(midi)).generate();
    } catch (e) {
      return JSON.stringify({ error: String((e && e.message) || e), unplayable: true });
    }
    let notes = 0;
    for (const track of score.tracks) for (const staff of track.staves) for (const bar of staff.bars)
      for (const voice of bar.voices) for (const beat of voice.beats) notes += beat.notes.length;
    return JSON.stringify({
      title: score.title || '',
      artist: score.artist || '',
      tracks: score.tracks.map(t => (t.name && t.name.trim()) ? t.name : 'Track ' + (t.index + 1)),
      bars: score.masterBars.length,
      tempo: score.tempo,
      notes,
    });
  },
};
