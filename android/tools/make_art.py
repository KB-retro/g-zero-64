"""Builds the G-Zero 64 launcher art, icons and README images from the source art in android/art/.

    python3 android/tools/make_art.py

cover-source.png is the cover already cropped clear of its badge strip and rating box, which carry
third-party marks and must not come back.
"""
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(__file__)
ART = os.path.join(HERE, "..", "art")
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
DOCS = os.path.join(HERE, "..", "..", "docs", "branding")

COVER_WIDTH = 852            # width of the cropped cover
LOGO_BOX = (15, 95, 852, 425)  # the G-ZERO 64 lettering on the cover
ICON_BOX = (40, 40, 1214, 1214)  # inside the black margin of icon-source.png


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return mask


def faded_edges(img, border):
    """Alpha ramps to zero over `border` pixels on every side."""
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rectangle([border, border, w - border, h - border], fill=255)
    img = img.convert("RGBA")
    img.putalpha(mask.filter(ImageFilter.GaussianBlur(border / 2)))
    return img


def cover():
    src = Image.open(os.path.join(ART, "cover-source.png")).convert("RGB")
    return src.crop((0, 0, COVER_WIDTH, src.height))


def readme_images(art, icon):
    os.makedirs(DOCS, exist_ok=True)
    portrait = art.resize((568, 1024), Image.LANCZOS)
    portrait.save(os.path.join(DOCS, "gzero64-cover.jpg"), quality=88)
    icon.resize((192, 192), Image.LANCZOS).save(os.path.join(DOCS, "gzero64-icon.png"))
    for old in ("gzero64-banner.png",):
        path = os.path.join(DOCS, old)
        if os.path.exists(path):
            os.remove(path)


def launcher_art(art):
    nodpi = os.path.join(RES, "drawable-nodpi")
    # Landscape backdrop: the racers and the city, 16:9.
    h = COVER_WIDTH * 9 // 16
    top = 560
    back = art.crop((0, top, COVER_WIDTH, top + h)).resize((1280, 720), Image.LANCZOS)
    back.save(os.path.join(nodpi, "launcher_backdrop.jpg"), quality=88)
    logo = faded_edges(art.crop(LOGO_BOX), 18)
    logo.thumbnail((720, 300), Image.LANCZOS)
    logo.save(os.path.join(nodpi, "launcher_logo.png"))


def icon_art():
    src = Image.open(os.path.join(ART, "icon-source.png")).convert("RGB").crop(ICON_BOX)
    return src


def icons(icon):
    for folder, legacy in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        out = os.path.join(RES, "mipmap-" + folder)
        os.makedirs(out, exist_ok=True)
        # Legacy icon: the artwork in its own rounded square.
        full = icon.resize((legacy, legacy), Image.LANCZOS).convert("RGBA")
        full.putalpha(rounded_mask(full.size, legacy * 18 // 100))
        full.save(os.path.join(out, "ic_launcher.png"))
        # Adaptive foreground: 108dp canvas, artwork inside the area every mask shape keeps.
        canvas = legacy * 108 // 48
        inner = int(canvas * 0.70)
        fg = icon.resize((inner, inner), Image.LANCZOS).convert("RGBA")
        fg.putalpha(rounded_mask(fg.size, inner * 16 // 100))
        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        layer.alpha_composite(fg, ((canvas - inner) // 2, (canvas - inner) // 2))
        layer.save(os.path.join(out, "ic_launcher_foreground.png"))


if __name__ == "__main__":
    art = cover()
    icon = icon_art()
    launcher_art(art)
    icons(icon)
    readme_images(art, icon)
