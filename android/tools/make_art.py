"""Draws the G-Zero 64 launcher art and icons (python3 android/tools/make_art.py)."""
import math
import os
import random
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
FONT = "/usr/share/fonts/opentype/inter/Inter-ExtraBold.otf"


def gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    return img


def italic(img, shear=0.22):
    w, h = img.size
    extra = int(h * shear)
    return img.transform((w + extra, h), Image.AFFINE, (1, shear, -extra, 0, 1, 0), Image.BICUBIC)


def title(text, height, fill_top=(255, 222, 110), fill_bottom=(240, 120, 20)):
    font = ImageFont.truetype(FONT, height)
    box = font.getbbox(text)
    w, h = box[2] - box[0] + 40, box[3] - box[1] + 40
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).text((20 - box[0], 20 - box[1]), text, font=font, fill=255)
    fill = gradient((w, h), fill_top, fill_bottom).convert("RGBA")
    fill.putalpha(mask)
    outline = mask.filter(ImageFilter.MaxFilter(9))
    layer = Image.new("RGBA", (w, h), (25, 10, 40, 0))
    layer.putalpha(outline)
    glow = Image.new("RGBA", (w, h), (255, 140, 40, 0))
    glow.putalpha(outline.filter(ImageFilter.GaussianBlur(10)).point(lambda v: v // 2))
    out = Image.alpha_composite(Image.alpha_composite(glow, layer), fill)
    return italic(out)


def backdrop():
    w, h = 1920, 1080
    img = gradient((w, h), (10, 8, 30), (34, 12, 52)).convert("RGBA")
    d = ImageDraw.Draw(img)
    horizon = int(h * 0.48)
    vx = w // 2
    # Track: perspective rails converging at the vanishing point.
    for i in range(-14, 15):
        x = vx + i * 260
        d.line([(vx + i * 6, horizon), (x, h)], fill=(90, 70, 190, 120), width=2)
    for k in range(1, 18):
        y = horizon + int((h - horizon) * (k / 18) ** 2.2)
        d.line([(0, y), (w, y)], fill=(90, 70, 190, 90), width=2)
    # Guard rails glowing orange and cyan.
    rails = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    r = ImageDraw.Draw(rails)
    r.line([(vx - 30, horizon), (-200, h)], fill=(255, 150, 40, 255), width=10)
    r.line([(vx + 30, horizon), (w + 200, h)], fill=(70, 220, 255, 255), width=10)
    img = Image.alpha_composite(img, rails.filter(ImageFilter.GaussianBlur(14)))
    img = Image.alpha_composite(img, rails)
    # Speed streaks.
    random.seed(64)
    streaks = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    s = ImageDraw.Draw(streaks)
    for _ in range(140):
        a = random.uniform(0, 2 * math.pi)
        r0 = random.uniform(80, 500)
        r1 = r0 + random.uniform(150, 700)
        c = random.choice([(255, 200, 90), (120, 220, 255), (255, 255, 255)])
        s.line([(vx + math.cos(a) * r0, horizon + math.sin(a) * r0 * 0.6),
                (vx + math.cos(a) * r1, horizon + math.sin(a) * r1 * 0.6)],
               fill=c + (random.randint(40, 120),), width=random.randint(1, 3))
    img = Image.alpha_composite(img, streaks.filter(ImageFilter.GaussianBlur(1)))
    sun = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(sun).ellipse([vx - 160, horizon - 160, vx + 160, horizon + 160], fill=(255, 120, 60, 90))
    img = Image.alpha_composite(img, sun.filter(ImageFilter.GaussianBlur(60)))
    img.convert("RGB").save(os.path.join(RES, "drawable-nodpi", "launcher_backdrop.jpg"), quality=88)


def logo():
    t = title("G-ZERO 64", 150)
    t.thumbnail((500, 157), Image.LANCZOS)
    canvas = Image.new("RGBA", (500, 157), (0, 0, 0, 0))
    canvas.alpha_composite(t, ((500 - t.width) // 2, (157 - t.height) // 2))
    canvas.save(os.path.join(RES, "drawable-nodpi", "launcher_logo.png"))


def icon_foreground(size):
    """Big G on top; ZERO under it on the left and 64 on the right."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Speed stripes behind the letter, inside the adaptive-icon safe zone (66%).
    for i, c in enumerate([(255, 150, 40), (255, 210, 90), (70, 220, 255)]):
        y = int(size * (0.30 + i * 0.06))
        d.polygon([(size * 0.17, y), (size * 0.50, y), (size * 0.47, y + size * 0.035), (size * 0.14, y + size * 0.035)],
                  fill=c + (220,))
    g = title("G", int(size * 0.40))
    g.thumbnail((int(size * 0.44), int(size * 0.40)), Image.LANCZOS)
    img.alpha_composite(g, (int(size * 0.31), int(size * 0.17)))
    zero = title("ZERO", int(size * 0.16))
    zero.thumbnail((int(size * 0.38), int(size * 0.15)), Image.LANCZOS)
    img.alpha_composite(zero, (int(size * 0.17), int(size * 0.585)))
    n = title("64", int(size * 0.18), (190, 240, 255), (60, 170, 255))
    n.thumbnail((int(size * 0.24), int(size * 0.18)), Image.LANCZOS)
    img.alpha_composite(n, (int(size * 0.17) + zero.width - int(size * 0.035), int(size * 0.565)))
    return img


def ship(width, body, accent, bank=0.0):
    """A generic futuristic hover racer seen from behind: low wedge hull, glowing thrusters, trails."""
    w, h = width, int(width * 0.42)
    pad = int(width * 0.3)
    img = Image.new("RGBA", (w + 2 * pad, h + 2 * pad + int(width * 0.4)), (0, 0, 0, 0))

    def P(x, y):
        return (pad + x * w, pad + y * h)

    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).ellipse([P(0.08, 1.02), P(0.92, 1.18)], fill=(0, 0, 0, 140))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(width * 0.035)))
    # Exhaust trails stretching back toward the viewer.
    trails = Image.new("RGBA", img.size, (0, 0, 0, 0))
    td = ImageDraw.Draw(trails)
    for cx in (0.36, 0.64):
        td.polygon([P(cx - 0.05, 0.62), P(cx + 0.05, 0.62), P(cx + 0.03 + (cx - 0.5) * 0.4, 1.9),
                    P(cx - 0.03 + (cx - 0.5) * 0.4, 1.9)], fill=(110, 210, 255, 110))
    img.alpha_composite(trails.filter(ImageFilter.GaussianBlur(width * 0.025)))
    d = ImageDraw.Draw(img)
    dark = tuple(int(c * 0.5) for c in body)
    light = tuple(min(255, int(c * 1.2) + 30) for c in body)
    # Long swept wings.
    d.polygon([P(-0.08, 0.80), P(0.24, 0.46), P(0.30, 0.74)], fill=dark)
    d.polygon([P(1.08, 0.80), P(0.76, 0.46), P(0.70, 0.74)], fill=dark)
    d.polygon([P(-0.08, 0.80), P(0.24, 0.46), P(0.10, 0.73)], fill=accent)
    d.polygon([P(1.08, 0.80), P(0.76, 0.46), P(0.90, 0.73)], fill=accent)
    # Flat wedge hull.
    d.polygon([P(0.14, 0.78), P(0.86, 0.78), P(0.74, 0.44), P(0.26, 0.44)], fill=body)
    d.polygon([P(0.14, 0.78), P(0.86, 0.78), P(0.82, 0.86), P(0.18, 0.86)], fill=dark)
    d.polygon([P(0.25, 0.48), P(0.75, 0.48), P(0.77, 0.54), P(0.23, 0.54)], fill=accent)
    d.polygon([P(0.34, 0.44), P(0.66, 0.44), P(0.58, 0.26), P(0.42, 0.26)], fill=light)
    # Low canopy.
    d.ellipse([P(0.43, 0.20), P(0.57, 0.38)], fill=(40, 90, 160))
    d.ellipse([P(0.455, 0.22), P(0.51, 0.29)], fill=(170, 220, 255))
    # Thrusters.
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for cx in (0.36, 0.64):
        gd.ellipse([P(cx - 0.11, 0.52), P(cx + 0.11, 0.86)], fill=(90, 200, 255, 210))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(width * 0.045)))
    d = ImageDraw.Draw(img)
    for cx in (0.36, 0.64):
        d.ellipse([P(cx - 0.07, 0.59), P(cx + 0.07, 0.77)], fill=(30, 30, 40))
        d.ellipse([P(cx - 0.055, 0.61), P(cx + 0.055, 0.75)], fill=(120, 220, 255))
        d.ellipse([P(cx - 0.025, 0.65), P(cx + 0.025, 0.71)], fill=(240, 255, 255))
    if bank:
        img = img.rotate(bank, resample=Image.BICUBIC)
    return img


def race(canvas, scale=1.0):
    """Two racers on the track, the nearer one on the left."""
    w, h = canvas.size
    near = ship(int(w * 0.21 * scale), (205, 40, 60), (255, 200, 60), bank=6)
    far = ship(int(w * 0.13 * scale), (40, 110, 220), (240, 240, 255), bank=-4)
    canvas.alpha_composite(far, (int(w * 0.57 - far.width / 2), int(h * 0.64 - far.height / 2)))
    canvas.alpha_composite(near, (int(w * 0.40 - near.width / 2), int(h * 0.86 - near.height / 2)))


def icons():
    for folder, legacy in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        out = os.path.join(RES, "mipmap-" + folder)
        os.makedirs(out, exist_ok=True)
        fg = icon_foreground(legacy * 108 // 48)
        fg.save(os.path.join(out, "ic_launcher_foreground.png"))
        full = gradient(fg.size, (24, 16, 56), (12, 8, 30)).convert("RGBA")
        full.alpha_composite(fg)
        crop = int(fg.width * 18 / 108)
        full = full.crop((crop, crop, fg.width - crop, fg.height - crop)).resize((legacy, legacy), Image.LANCZOS)
        mask = Image.new("L", (legacy, legacy), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, legacy - 1, legacy - 1], radius=legacy // 5, fill=255)
        full.putalpha(mask)
        full.save(os.path.join(out, "ic_launcher.png"))


def banner():
    """README banner for GitHub: backdrop, logo and two racers, without the Patrulla Retro mark."""
    out = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "branding")
    os.makedirs(out, exist_ok=True)
    back = Image.open(os.path.join(RES, "drawable-nodpi", "launcher_backdrop.jpg")).convert("RGBA")
    back = back.crop((0, 60, 1920, 1080)).resize((1280, 680), Image.LANCZOS)
    t = title("G-ZERO 64", 230)
    t.thumbnail((940, 280), Image.LANCZOS)
    back.alpha_composite(t, ((1280 - t.width) // 2, 50))
    race(back)
    back.convert("RGB").save(os.path.join(out, "gzero64-banner.png"))
    icon = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png"))
    icon.save(os.path.join(out, "gzero64-icon.png"))


if __name__ == "__main__":
    backdrop()
    logo()
    icons()
    banner()
