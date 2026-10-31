"""Synthesises the bundled soundboard presets (original, royalty-free)."""
import sys, wave
import numpy as np

SR = 22050
rng = np.random.default_rng(7)
out_dir = sys.argv[1]

def t(sec): return np.arange(int(sec * SR)) / SR
def env(n, a=0.005, r=0.05):
    e = np.ones(n); na, nr = int(a*SR), int(r*SR)
    e[:na] = np.linspace(0, 1, na); e[n-nr:] *= np.linspace(1, 0, nr); return e
def lowpass(x, cutoff):
    a = np.broadcast_to(np.exp(-2*np.pi*np.asarray(cutoff, float)/SR), x.shape)
    y = np.zeros_like(x); s = 0.0
    for i, v in enumerate(x): s = (1-a[i])*v + a[i]*s; y[i] = s
    return y
def saw(f, tt): return 2*((tt*f) % 1) - 1
def place(buf, clip, at):
    i = int(at*SR); buf[i:i+len(clip)] += clip[:len(buf)-i]
def save(name, x):
    # Loudness-match all pads to one RMS, then soft-limit peaks, so tapping pads in a row
    # never jumps in volume.
    x = x / (np.sqrt(np.mean(x**2)) + 1e-9) * 0.16
    x = 0.89 * np.tanh(x / 0.89)
    x[:64] *= np.linspace(0, 1, 64); x[-256:] *= np.linspace(1, 0, 256)
    with wave.open(f"{out_dir}/{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype("<i2").tobytes())

def tom(f0, dur=0.35):
    tt = t(dur); f = f0 * (1 + 0.6*np.exp(-tt*30))
    return np.sin(2*np.pi*np.cumsum(f)/SR) * np.exp(-tt*9)

# Ba-dum-tss
b = np.zeros(int(1.5*SR))
place(b, tom(180), 0.0); place(b, tom(130), 0.17)
tt = t(1.0); cym = np.diff(rng.standard_normal(len(tt)+1)) * np.exp(-tt*4) * 0.35
place(b, cym, 0.42); save("pad_rimshot", b)

# Air horn: short, short, long
def blast(d):
    tt = t(d); x = sum(saw(f, tt) for f in (466.2, 587.3, 698.5))
    return lowpass(x, 2500) * env(len(tt), 0.01, 0.04)
b = np.zeros(int(1.7*SR))
place(b, blast(0.22), 0.0); place(b, blast(0.22), 0.3); place(b, blast(0.95), 0.6)
save("pad_airhorn", b)

# Boing
tt = t(0.9); f = 220 + 160*np.sin(2*np.pi*11*tt)*np.exp(-tt*3.5) + 120*tt
save("pad_boing", np.sin(2*np.pi*np.cumsum(f)/SR) * np.exp(-tt*2.8))

# Wrong-answer buzzer
tt = t(0.9); x = np.sign(np.sin(2*np.pi*110*tt)) + np.sign(np.sin(2*np.pi*116.5*tt))
save("pad_buzzer", lowpass(x, 1800) * env(len(tt), 0.01, 0.08))

# Sad trombone: wah wah wah waaah
b = np.zeros(int(2.6*SR)); at = 0.0
for f0, d in ((233.1, 0.42), (220.0, 0.42), (207.7, 0.42), (196.0, 1.2)):
    tt = t(d); vib = 1 + (0.012*np.sin(2*np.pi*6*tt) if d > 1 else 0)
    x = saw(1, np.cumsum(np.full(len(tt), f0) * vib)/SR)
    x = lowpass(x, 900 + 900*np.sin(np.pi*tt/d)) * env(len(tt), 0.04, 0.12)
    place(b, x, at); at += d + 0.03
save("pad_sad_trombone", b)

# Applause: a crowd of short filtered noise bursts
b = np.zeros(int(2.4*SR))
for _ in range(420):
    at = rng.uniform(0, 2.2); clap = rng.standard_normal(int(0.012*SR)) * np.exp(-np.arange(int(0.012*SR))/40)
    place(b, np.diff(clap, prepend=0) * rng.uniform(0.3, 1.0), at)
b *= np.minimum(1, np.linspace(0, 6, len(b))) * np.minimum(1, np.linspace(5, 0, len(b)))
save("pad_applause", b)

# Crickets (awkward silence)
b = np.zeros(int(2.4*SR))
for at in np.arange(0.05, 2.2, 0.55):
    for k in range(3):
        tt = t(0.035); place(b, np.sin(2*np.pi*4300*tt) * np.sin(np.pi*tt/0.035), at + k*0.06)
save("pad_crickets", b)
