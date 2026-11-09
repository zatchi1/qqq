#!/usr/bin/env python3
"""
Rebuilds the cinematic assets from the original uploads in design/ :
  design/reference/2_floating_character.png -> res/drawable-nodpi/float_char.png, float_marks.png
  design/reference/3_cut_character.png      -> res/drawable-nodpi/cut_top.png, cut_bottom.png
  design/audio/*.mp3                        -> res/raw/*.wav (16-bit mono) + short key clicks cut from TRRR
Needs: python3, numpy, scipy, Pillow, ffmpeg.   Run from the repo root:  python3 tools/make_assets.py
"""
import os, subprocess, wave
import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage as ndi

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app/src/main/res')
os.makedirs(RES + '/drawable-nodpi', exist_ok=True)
os.makedirs(RES + '/raw', exist_ok=True)


def save_rgba(im_rgb, mask, box, path, pad=6, blur=0.8):
    """Crops im_rgb to the mask's bbox (+pad), writes an RGBA PNG with a softly feathered alpha."""
    ys, xs = np.where(mask)
    x0, x1 = max(0, xs.min() - pad), min(mask.shape[1], xs.max() + 1 + pad)
    y0, y1 = max(0, ys.min() - pad), min(mask.shape[0], ys.max() + 1 + pad)
    a = Image.fromarray((mask[y0:y1, x0:x1] * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(blur))
    rgb = Image.fromarray(im_rgb[y0:y1, x0:x1].astype(np.uint8))
    out = rgb.convert('RGBA')
    out.putalpha(a)
    out.save(path, optimize=True)
    print('wrote', os.path.relpath(path, ROOT), out.size, 'origin in source image:', (x0, y0))
    return x0, y0, x1 - x0, y1 - y0


def comps(nd, min_area):
    lab, n = ndi.label(nd)
    sz = ndi.sum(np.ones_like(lab), lab, index=np.arange(1, n + 1))
    out = []
    for i in range(1, n + 1):
        if sz[i - 1] < min_area:
            continue
        ys, xs = np.where(lab == i)
        out.append((i, int(sz[i - 1]), xs.min(), xs.max(), ys.min(), ys.max()))
    return lab, out


# ---------------------------------------------------------------- 2: floating character
src = np.array(Image.open(ROOT + '/design/reference/2_floating_character.png').convert('RGB')).astype(int)
lab, cs = comps(src.max(axis=2) >= 20, 300)
body = np.zeros(lab.shape, bool)
marks = np.zeros(lab.shape, bool)
for i, area, x0, x1, y0, y1 in cs:
    if 270 <= x0 and x1 <= 660 and 340 <= y0 and y1 <= 770:
        body |= lab == i
    elif 680 <= x0 and x1 <= 800 and 370 <= y0 and y1 <= 500:
        marks |= lab == i


def finish(m, grow=5):
    m = ndi.binary_closing(m, iterations=3)
    m = ndi.binary_dilation(m, iterations=grow)
    return ndi.binary_fill_holes(m)


save_rgba(src, finish(body), None, RES + '/drawable-nodpi/float_char.png')
save_rgba(src, finish(marks, 2), None, RES + '/drawable-nodpi/float_marks.png')

# ---------------------------------------------------------------- 3: cut character
im = np.array(Image.open(ROOT + '/design/reference/3_cut_character.png').convert('RGB')).astype(int)
H, W, _ = im.shape
r, g, b = im[..., 0], im[..., 1], im[..., 2]
green = (g > 140) & (r < 150) & (b < 130) & (g > r + 40)
cyan = (b > 150) & (g > 150) & (r < 150)
core = green | cyan
nd = im.max(axis=2) >= 45
lab, n = ndi.label(nd)
cnt = ndi.sum(core, lab, index=np.arange(1, n + 1))
mask = np.isin(lab, [i + 1 for i, c in enumerate(cnt) if c > 300])
horn = (b > 60) & (r < 110) & (g < 100) & (b > r + 15) & (b > g + 15)
hl, hn = ndi.label(ndi.binary_dilation(horn, iterations=2))
near = ndi.binary_dilation(mask, iterations=14)
for i in range(1, hn + 1):
    comp = hl == i
    if comp.sum() > 800 and near[comp].any():
        mask |= comp
mask = ndi.binary_fill_holes(ndi.binary_closing(mask, iterations=3))
ring = ndi.binary_dilation(mask, iterations=9) & ~mask
mask |= ring & (im.max(axis=2) < 70)
body_mask = ndi.binary_fill_holes(ndi.binary_closing(mask, iterations=2))


def opened_largest(comp, it=7):
    comp = ndi.binary_opening(comp, iterations=it)
    l2, n2 = ndi.label(comp)
    sz = ndi.sum(comp, l2, index=np.arange(1, n2 + 1))
    return l2 == (1 + int(np.argmax(sz)))


def comp_at(x, y):
    return lab[y, x]


# red cut faces + bone: enclosed non-dark components next to each green body
sizes = ndi.sum(np.ones_like(lab), lab, index=np.arange(1, n + 1))
near = ndi.binary_dilation(body_mask, iterations=26)
faces = []
for i in range(1, n + 1):
    s = sizes[i - 1]
    if 5000 < s < 90000 and not body_mask[lab == i].mean() > 0.5 and (near & (lab == i)).sum() / s > 0.3:
        faces.append(i)
bone_id = lab[800, 660]            # white bone shaft
lt, _ = ndi.label(body_mask)
top_body = lt == lt[230 + 420, 60 + 200]
bot_body = lt == lt[230 + 790, 60 + 620]


def assemble(parts):
    m = np.zeros((H, W), bool)
    for p in parts:
        m |= p
    m = ndi.binary_closing(m, iterations=4)
    ring = ndi.binary_dilation(m, iterations=9) & ~m
    m |= ring & (im.max(axis=2) < 80)
    return ndi.binary_fill_holes(ndi.binary_closing(m, iterations=2))


face_parts = [opened_largest(lab == i) for i in faces]
near_top = ndi.binary_dilation(top_body, iterations=40)
top_faces = [f for f in face_parts if near_top[f].mean() > 0.3]
bot_faces = [f for f in face_parts if near_top[f].mean() <= 0.3]
print('faces found:', len(top_faces), 'top,', len(bot_faces), 'bottom')
top = assemble([top_body] + top_faces)
bot = assemble([bot_body] + bot_faces + [lab == bone_id])
t = save_rgba(im, top, None, RES + '/drawable-nodpi/cut_top.png')
bt = save_rgba(im, bot, None, RES + '/drawable-nodpi/cut_bottom.png')
print('CUT_TOP  x,y,w,h =', t, ' CUT_BOTTOM x,y,w,h =', bt, ' source size =', (W, H))

# ---------------------------------------------------------------- audio
RAW = RES + '/raw'
for src_name, out_name in (('1_trrr', 'sfx_trrr'), ('2_kill', 'sfx_kill'), ('3_106', 'sfx_106')):
    subprocess.check_call(['ffmpeg', '-y', '-v', 'error', '-i', f'{ROOT}/design/audio/{src_name}.mp3',
                           '-ac', '1', '-ar', '44100', '-c:a', 'pcm_s16le', '-bitexact', f'{RAW}/{out_name}.wav'])


def load_wav(path):
    w = wave.open(path)
    a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(float) / 32768
    return a, w.getframerate()


def write_wav(path, a, sr=44100):
    w = wave.open(path, 'wb')
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
    w.writeframes((np.clip(a, -1, 1) * 32767).astype(np.int16).tobytes())
    w.close()


tr, sr = load_wav(RAW + '/sfx_trrr.wav')
hop = int(sr * 0.004)
env = np.array([np.sqrt((tr[i:i + hop] ** 2).mean()) for i in range(0, len(tr) - hop, hop)])
on = env > 0.05
ons = [i * 0.004 for i in range(1, len(on)) if on[i] and not on[i - 1]]
offs = [i * 0.004 for i in range(1, len(on)) if not on[i] and on[i - 1]]
print('TRRR bursts (on):', ', '.join('%.3f' % x for x in ons))
print('TRRR bursts (off):', ', '.join('%.3f' % x for x in offs))


def burst(onset, offset, pitch, gain=0.85):
    a = tr[int((onset - 0.003) * sr):int((offset + 0.004) * sr)].copy()
    n = len(a)
    a *= np.minimum(1, np.arange(n) / (0.003 * sr))                  # 3 ms fade in
    a *= np.minimum(1, np.arange(n)[::-1] / (0.010 * sr))            # 10 ms fade out
    idx = np.arange(0, n - 1, pitch)                                 # pitch by resampling
    a = np.interp(idx, np.arange(n), a)
    return a / max(1e-6, abs(a).max()) * gain


picks = [(0.188, 0.228), (0.316, 0.364), (0.936, 0.984), (1.288, 1.336)]
for k, (o, f) in enumerate(picks):                        # digits and '.': light, high
    write_wav(f'{RAW}/key_digit{k}.wav', burst(o, f, [1.0, 1.1, 0.93, 1.18][k]))
for k, (o, f) in enumerate(picks[:2]):                    # operators / functions: lower
    write_wav(f'{RAW}/key_op{k}.wav', burst(o, f, [0.78, 0.7][k], 0.9))
two = np.concatenate([burst(0.188, 0.228, 1.0), np.zeros(int(0.03 * sr)), burst(0.316, 0.364, 1.2)])
write_wav(f'{RAW}/key_equals.wav', two)                   # '=' : two quick bursts
print('done')
