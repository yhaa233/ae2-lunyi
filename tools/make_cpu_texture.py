from PIL import Image, ImageDraw, ImageFont
import os

SRC = os.path.join(os.environ["TEMP"], "ae2unit", "assets", "ae2", "textures", "block", "crafting", "unit.png")
OUT = r"E:\lunyi\src\main\resources\assets\ae2_lunyi\textures\block\wheelchair_cpu.png"

base = Image.open(SRC).convert("RGBA")
size = 64
img = base.resize((size, size), Image.NEAREST)
d = ImageDraw.Draw(img)

text = "API"

# 找一个可用的字体，保证 API 三个字母清晰
font = None
for candidate in (
    r"C:\Windows\Fonts\consolab.ttf",
    r"C:\Windows\Fonts\arialbd.ttf",
    r"C:\Windows\Fonts\segoeuib.ttf",
):
    if os.path.exists(candidate):
        for pt in (22, 20, 18, 16):
            f = ImageFont.truetype(candidate, pt)
            box = d.textbbox((0, 0), text, font=f)
            if box[2] - box[0] <= size - 8 and box[3] - box[1] <= size - 10:
                font = f
                break
        if font:
            break
if font is None:
    font = ImageFont.load_default()

box = d.textbbox((0, 0), text, font=font)
w, h = box[2] - box[0], box[3] - box[1]
x = (size - w) // 2 - box[0]
y = (size - h) // 2 - box[1]

# 先画深色描边，再画亮色字，保证在灰色底上可读
for dx in (-1, 0, 1):
    for dy in (-1, 0, 1):
        d.text((x + dx, y + dy), text, font=font, fill=(24, 26, 32, 255))
d.text((x, y), text, font=font, fill=(226, 236, 255, 255))

img.save(OUT)
print("saved", OUT, img.size)
