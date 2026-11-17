"""Build a deterministic synthetic YOLO dataset from the bundled MIT templates."""
import argparse
import io
from pathlib import Path
import random
import re

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from resvg_py import svg_to_bytes

ROOT = Path(__file__).resolve().parent
SYMBOLS = ROOT / "templates"
FONT_PATHS = ["C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/tahoma.ttf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]


def render(path, stroke, font, alternate=False):
    svg = path.read_text(encoding="utf-8")
    svg = svg.replace('stroke-width="2"', f'stroke-width="{stroke:.2f}"')
    svg = re.sub('font-family="[^"]+"', f'font-family="{font}"', svg)
    name = path.stem
    if alternate and name.startswith("iron-"):
        # Another common printed outline, with the toe on the left.
        svg = re.sub(r'<path d="M12 38[^"]*" />', '<path d="M6 43 Q9 32 23 27 L54 27 L55 43 Z" />', svg)
        svg = re.sub(r'<path d="M16 24[^"]*" />', '<path d="M27 19 L51 19 L54 27" />', svg)
        svg = re.sub(r'cy="31"', 'cy="36"', svg)
    if alternate and name.startswith("wash-"):
        svg = re.sub(r'<path d="M12 (\d+) L12 \d+ Q12 \d+ \d+ (\d+) L44 \d+ Q52 \d+ 52 \d+ L52 \d+" />',
                     lambda m: f'<path d="M8 {int(m[1])-5} L15 {m[2]} H49 L56 {int(m[1])-5}" />', svg)
        svg = svg.replace('font-size="14"', 'font-size="17" font-weight="bold"')
    if alternate and name.startswith("dry-tumble-"):
        svg = svg.replace('rx="2"', 'rx="0"').replace('r="16"', 'r="20"')
    pixels = svg_to_bytes(svg_string=svg, width=160, height=160)
    im = Image.open(io.BytesIO(pixels)).convert("RGBA").getchannel("A")
    return im.crop(im.getbbox())


def generate(output, counts, seed_offset=0, varied=False):
    rows = [line.split("\t") for line in (ROOT / "classes.tsv").read_text(encoding="utf-8").splitlines()]
    names = [row[0] for row in rows]
    templates = {i: [render(SYMBOLS / (name + ".svg"), stroke, font, alternate)
                      for stroke in (1.6, 2.2, 2.9, 3.5)
                      for font in ("Arial", "DejaVu Sans") for alternate in ((False, True) if varied else (False,))]
                 for i, name in enumerate(names)}
    text_font = next((f for f in FONT_PATHS if Path(f).exists()), None)
    for split, count, seed in (("train", counts[0], 3414), ("val", counts[1], 8216), ("test", counts[2], 7219)):
        rng = random.Random(seed + seed_offset)
        noise = np.random.default_rng(seed + seed_offset)
        image_dir, label_dir = output / "images" / split, output / "labels" / split
        image_dir.mkdir(parents=True, exist_ok=True)
        label_dir.mkdir(parents=True, exist_ok=True)
        for index in range(count):
            size = 320
            light = rng.randint(185, 250)
            background = np.full((size, size, 3), (light, light, light), dtype=np.float32)
            background += noise.normal(0, rng.uniform(.5, 10 if varied else 6), (size, size, 1))
            background += rng.uniform(-16, 16) * np.sin(np.arange(size)[None, :, None] / rng.uniform(12, 80))
            background += rng.uniform(-12, 12) * np.sin(np.arange(size)[:, None, None] / rng.uniform(10, 55))
            image = Image.fromarray(np.clip(background, 0, 255).astype("uint8"))
            drawing = ImageDraw.Draw(image)
            if text_font:
                for _ in range(rng.randint(0, 4)):
                    y = rng.choice((rng.randint(10, 65), rng.randint(247, 294)))
                    phrase = rng.choice(("100% COTTON", "MADE IN CHINA", "Wash inside out", "Lining 20% polyester", "CARE INSTRUCTIONS"))
                    drawing.text((rng.randint(15, 105), y), phrase, font=ImageFont.truetype(text_font, rng.randint(9, 17)),
                                 fill=(70, 70, 70))
            number = rng.choice([1, 2, 3, 4, 5, 5, 6])
            chosen = rng.sample(range(len(names)), number) if rng.random() > .07 else []
            slots = (rng.randint(12, 26), 308 - rng.randint(2, 15))
            width = (slots[1] - slots[0]) / number
            line_y = rng.randint(90, 216)
            labels = []
            for slot, class_id in enumerate(chosen):
                template = rng.choice(templates[class_id])
                max_width = rng.uniform(.47, .89) * min(width, 100)
                height = max_width * template.height / template.width * rng.uniform(*((.67, 1.26) if varied else (.78, 1.14)))
                shape = template.resize((max(6, round(max_width)), max(6, round(min(height, 100)))), Image.Resampling.LANCZOS)
                angle = 25 if varied else 16
                shape = shape.rotate(rng.uniform(-angle, angle), Image.Resampling.BICUBIC, expand=True)
                if not shape.getbbox():
                    continue
                shape = shape.crop(shape.getbbox())
                x = round(slots[0] + width * (slot + .5) - shape.width / 2)
                y = round(line_y - shape.height / 2 + rng.uniform(-14, 14))
                ink = rng.randint(0, 62)
                image.paste((ink, ink, ink), (x, y, x + shape.width, y + shape.height), shape)
                labels.append(f"{class_id} {(x + shape.width/2)/size:.6f} {(y+shape.height/2)/size:.6f} "
                              f"{shape.width/size:.6f} {shape.height/size:.6f}")
            image = image.filter(ImageFilter.GaussianBlur(rng.uniform(0, .85)))
            image.save(image_dir / f"{index:05d}.jpg", quality=rng.randint(65, 96))
            (label_dir / f"{index:05d}.txt").write_text("\n".join(labels), encoding="ascii")
    (output / "data.yaml").write_text("path: " + output.absolute().as_posix()
        + "\ntrain: images/train\nval: images/val\ntest: images/test\nnames:\n"
        + "".join(f"  {i}: {name}\n" for i, name in enumerate(names)), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--train", type=int, default=896)
    parser.add_argument("--val", type=int, default=144)
    parser.add_argument("--test", type=int, default=144)
    parser.add_argument("--seed-offset", type=int, default=0)
    parser.add_argument("--varied", action="store_true")
    args = parser.parse_args()
    generate(args.output, (args.train, args.val, args.test), args.seed_offset, args.varied)
