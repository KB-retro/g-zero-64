"""Builds the G-Zero 64 launcher art, icons and README images from the source art in android/art/.

    python3 android/tools/make_art.py

cover-source.png is the cover already cropped clear of its badge strip and rating box, which carry
third-party marks and must not come back; where the lettering overlapped the strip, the strip was
painted over with sky.
"""
import os
from collections import deque

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(__file__)
ART = os.path.join(HERE, "..", "art")
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
DOCS = os.path.join(HERE, "..", "..", "docs", "branding")

COVER_WIDTH = 880            # width of the cropped cover
LOGO_REGION = (0, 70, 880, 440)  # the G-ZERO 64 lettering on the cover, with sky around it
SCENE_BOX = (200, 400, 974, 1174)  # square of racers in the icon artwork, below its lettering
ICON_BOX = (40, 40, 1214, 1214)  # inside the black margin of icon-source.png


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return mask


def cover():
    src = Image.open(os.path.join(ART, "cover-source.png")).convert("RGB")
    return src.crop((0, 0, COVER_WIDTH, src.height))


def readme_images(art, icon):
    os.makedirs(DOCS, exist_ok=True)
    portrait = art.resize((587, 1024), Image.LANCZOS)
    portrait.save(os.path.join(DOCS, "gzero64-cover.jpg"), quality=88)
    Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png")).save(os.path.join(DOCS, "gzero64-icon.png"))
    for old in ("gzero64-banner.png",):
        path = os.path.join(DOCS, old)
        if os.path.exists(path):
            os.remove(path)


def extract_logo(art):
    """Cuts the G-ZERO 64 lettering out of the cover with a transparent background.

    The lettering sits inside a thick black outline: flood the sky in from the edges, stop at the
    outline, keep what is left near the letters, then add a clean stroke and shadow of our own."""
    a = np.asarray(art.crop(LOGO_REGION)).astype(np.int32)
    h, w, _ = a.shape
    lum = a.mean(axis=2)
    r, g = a[..., 0], a[..., 1]

    def grow(mask, size):
        return np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(size))) > 0

    def shrink(mask, size):
        return np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.MinFilter(size))) > 0

    def flood(passable, starts, diagonal=False):
        seen = np.zeros((h, w), bool)
        q = deque()
        for y, x in starts:
            if passable[y, x] and not seen[y, x]:
                seen[y, x] = True
                q.append((y, x))
        steps = [(1, 0), (-1, 0), (0, 1), (0, -1)]
        if diagonal:
            steps += [(1, 1), (1, -1), (-1, 1), (-1, -1)]
        while q:
            y, x = q.popleft()
            for dy, dx in steps:
                ny, nx = y + dy, x + dx
                if 0 <= ny < h and 0 <= nx < w and passable[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    q.append((ny, nx))
        return seen

    wall = grow(lum < 40, 5)
    edges = [(y, x) for x in range(w) for y in (0, h - 1)] + [(y, x) for y in range(h) for x in (0, w - 1)]
    inside = ~flood(~wall, edges)
    # Only the piece connected to the letters, without thin bridges into dark sky.
    core = shrink(inside, 15)
    seeds = []
    for f in (0.12, 0.35, 0.5, 0.65, 0.78, 0.9):
        x = int(w * f)
        ys = np.nonzero(core[:, x])[0]
        if len(ys):
            seeds.append((int(ys[np.argmin(abs(ys - h * 0.45))]), x))
    inside &= grow(flood(core, seeds, diagonal=True), 17)
    # The outline is about a dozen pixels wide; farther out is sky (stars removed first).
    glyph = (lum > 85) | ((r > 100) & (r > g + 30))
    inside &= grow(shrink(glyph, 7), 35)
    # Drop specks: keep only big connected pieces.
    labels_done = np.zeros((h, w), bool)
    clean = np.zeros((h, w), bool)
    for y, x in zip(*np.nonzero(inside)):
        if labels_done[y, x]:
            continue
        piece = flood(inside & ~labels_done, [(y, x)], diagonal=True)
        labels_done |= piece
        if piece.sum() > 2000:
            clean |= piece
    alpha = Image.fromarray((clean * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1))
    letters = Image.fromarray(a.astype(np.uint8)).convert("RGBA")
    letters.putalpha(alpha)
    pad = 24
    out = Image.new("RGBA", (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    stroke = alpha.filter(ImageFilter.MaxFilter(9))
    shadow = Image.new("RGBA", out.size, (0, 0, 0, 0))
    shadow.paste((0, 0, 0, 170), (pad + 4, pad + 6), stroke.filter(ImageFilter.GaussianBlur(6)))
    out.alpha_composite(shadow)
    edge = Image.new("RGBA", out.size, (0, 0, 0, 0))
    edge.paste((8, 10, 24, 255), (pad, pad), stroke)
    out.alpha_composite(edge)
    out.alpha_composite(letters, (pad, pad))
    return out.crop(out.getbbox())


def launcher_art(art, logo):
    nodpi = os.path.join(RES, "drawable-nodpi")
    # Landscape backdrop: the racers and the city, 16:9.
    h = COVER_WIDTH * 9 // 16
    top = 560
    back = art.crop((0, top, COVER_WIDTH, top + h)).resize((1280, 720), Image.LANCZOS)
    back.save(os.path.join(nodpi, "launcher_backdrop.jpg"), quality=88)
    small = logo.copy()
    small.thumbnail((720, 300), Image.LANCZOS)
    small.save(os.path.join(nodpi, "launcher_logo.png"))
    # The in-game menu's About page logo (packed into gdiffuser.o2r), drawn at 1024x324.
    menu = Image.new("RGBA", (1024, 324), (0, 0, 0, 0))
    fit = logo.copy()
    fit.thumbnail((1024, 324), Image.LANCZOS)
    menu.alpha_composite(fit, ((1024 - fit.width) // 2, (324 - fit.height) // 2))
    menu.save(os.path.join(HERE, "..", "..", "assets", "branding", "gdiffuser-logo-menu.png"))


def icon_scene():
    """Square crop of the icon artwork below its lettering: the racers."""
    src = Image.open(os.path.join(ART, "icon-source.png")).convert("RGB").crop(ICON_BOX)
    return src


def icons(scene, logo):
    for folder, legacy in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        out = os.path.join(RES, "mipmap-" + folder)
        os.makedirs(out, exist_ok=True)
        canvas = legacy * 108 // 48
        # Adaptive background: the racers, full bleed and slightly darkened under the name.
        back = scene.crop(SCENE_BOX).resize((canvas, canvas), Image.LANCZOS).convert("RGBA")
        back.alpha_composite(Image.new("RGBA", back.size, (0, 0, 20, 70)))
        back.save(os.path.join(out, "ic_launcher_background.png"))
        # Adaptive foreground: the name, centred and small enough for a round mask (66% circle).
        fg = logo.copy()
        fg.thumbnail((int(canvas * 0.56), canvas), Image.LANCZOS)
        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        layer.alpha_composite(fg, ((canvas - fg.width) // 2, (canvas - fg.height) // 2))
        layer.save(os.path.join(out, "ic_launcher_foreground.png"))
        # Legacy icon: the visible 72dp of both layers in a rounded square.
        full = back.copy()
        full.alpha_composite(layer)
        inset = canvas * 18 // 108
        full = full.crop((inset, inset, canvas - inset, canvas - inset)).resize((legacy, legacy), Image.LANCZOS)
        full.putalpha(rounded_mask(full.size, legacy * 18 // 100))
        full.save(os.path.join(out, "ic_launcher.png"))


if __name__ == "__main__":
    art = cover()
    logo = extract_logo(art)
    launcher_art(art, logo)
    icons(icon_scene(), logo)
    readme_images(art, None)
