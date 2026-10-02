#!/usr/bin/env python3
"""
Generates Riff Buddy's two sample recordings for the looper.

Why they exist: the looper plays recordings the player already has (the spec), and on
a phone whose music is streamed there are none — so M9 could not be tried until
the recorder (M10) existed. These two are original, synthesised here, and carry
no licence question.

  a440-test-tone.wav    15 s of A440. The own acceptance check: play it at
                        50 % on one device and read it with the tuner on the
                        other. A working stretcher shows A; a naive slow-down
                        shows the A an octave below.
  pentatonic-riff.wav   The Pentatonic Study (the same piece as the tab sample)
                        as plucked guitar and bass, 96 BPM, eight bars — for
                        loops, the trainer, count-in and the metronome.

Plucked strings are Karplus-Strong: a burst of noise circulating in a delay
line one period long, averaged a little on each pass, which is a surprisingly
convincing string. Standard library only; rerun with `python3
tools/make_sample_audio.py` from apps/riff_buddy.
"""
import math
import random
import struct
import wave
from pathlib import Path

SR = 22_050
OUT = Path(__file__).resolve().parent.parent / "src/main/assets/samples"


def write(name, samples):
    peak = max(1e-9, max(abs(s) for s in samples))
    scale = 0.8 * 32767 / peak
    with wave.open(str(OUT / name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(s * scale)) for s in samples))
    print(f"{name}: {len(samples) / SR:.1f} s")


def test_tone():
    n = SR * 15
    fade = SR // 50
    out = []
    for i in range(n):
        env = min(1.0, i / fade, (n - 1 - i) / fade)
        out.append(math.sin(2 * math.pi * 440.0 * i / SR) * env)
    write("a440-test-tone.wav", out)


def pluck(freq, seconds, brightness=0.5, decay=0.996, seed=0):
    rng = random.Random(seed)
    period = max(2, int(round(SR / freq)))
    line = [rng.uniform(-1, 1) for _ in range(period)]
    # A darker pluck for the bass: smooth the initial burst.
    for _ in range(int((1 - brightness) * 4)):
        line = [(line[i] + line[i - 1]) / 2 for i in range(period)]
    out = []
    idx = 0
    for _ in range(int(SR * seconds)):
        a = line[idx]
        b = line[(idx + 1) % period]
        line[idx] = decay * 0.5 * (a + b)
        out.append(a)
        idx = (idx + 1) % period
    return out


# Standard tuning, string 1 (high E) to 6 (low E); bass is one octave under E-A-D-G.
GUITAR = {1: 329.63, 2: 246.94, 3: 196.00, 4: 146.83, 5: 110.00, 6: 82.41}
BASS = {1: 98.00, 2: 73.42, 3: 55.00, 4: 41.20}


def note(tuning, string, fret):
    return tuning[string] * 2 ** (fret / 12)


def riff():
    beat = 60 / 96
    eighth = beat / 2
    # (fret, string) eighth notes — the first eight bars of the study's guitar part.
    guitar = [
        [(0, 6), (3, 6), (0, 5), (2, 5), (0, 4), (2, 4), (0, 5), (2, 5)],
        [(0, 6), (3, 6), (0, 5), (2, 5), (0, 4), (2, 4), (3, 3), (2, 4)],
        [(0, 6), (3, 6), (0, 5), (2, 5), (0, 4), (2, 4), (0, 5), (2, 5)],
        [(0, 6), None, (3, 6), None, (0, 5), None, None, None],
        [(3, 3), (0, 3), (2, 4), (0, 4), (2, 4), (0, 4), (2, 5), (0, 5)],
        [(3, 3), (0, 3), (2, 4), (0, 4), (2, 5), (0, 5), (3, 6), (0, 6)],
        [(3, 3), (0, 3), (2, 4), (0, 4), (2, 4), (0, 4), (2, 5), (0, 5)],
        [(0, 6), None, None, None, (0, 6), None, None, None],
    ]
    bass = [
        [(0, 4), (0, 4), (3, 4), (0, 4)], [(0, 4), (0, 4), (3, 4), (5, 3)],
        [(0, 4), (0, 4), (3, 4), (0, 4)], [(0, 4), (3, 4), (0, 3), None],
        [(3, 4), (3, 4), (5, 4), (5, 4)], [(3, 4), (3, 4), (0, 3), (0, 4)],
        [(3, 4), (3, 4), (5, 4), (5, 4)], [(0, 4), None, (0, 4), None],
    ]
    total = int(SR * (len(guitar) * 4 * beat + 2.0))
    mix = [0.0] * total
    seed = 1

    def add(start_s, sound, gain):
        s = int(start_s * SR)
        for i, v in enumerate(sound):
            if s + i < total:
                mix[s + i] += v * gain

    for bar, notes in enumerate(guitar):
        for k, n in enumerate(notes):
            if n is None:
                continue
            seed += 1
            add((bar * 8 + k) * eighth, pluck(note(GUITAR, n[1], n[0]), 1.2, 0.8, 0.9965, seed), 0.55)
    for bar, notes in enumerate(bass):
        for k, n in enumerate(notes):
            if n is None:
                continue
            seed += 1
            add((bar * 4 + k) * beat, pluck(note(BASS, n[1], n[0]), 1.0, 0.2, 0.998, seed), 0.8)
    write("pentatonic-riff.wav", mix)


if __name__ == "__main__":
    test_tone()
    riff()
