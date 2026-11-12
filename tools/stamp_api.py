from PIL import Image, ImageDraw, ImageFont
import os

SRC = r"E:\lunyi\out\source.png"
OUT = r"E:\lunyi\out\wheelchair_api.png"

img = Image.open(SRC).convert("RGBA")
d = ImageDraw.Draw(img)
W, H = img.size

text = "API"
font_path = r"C:\Windows\Fonts\arialbd.ttf"
if not os.path.exists(font_path):
    font_path = r"C:\Windows\Fonts\segoeuib.ttf"

size = int(W * 0.16)
font = ImageFont.truetype(font_path, size)

box = d.textbbox((0, 0), text, font=font)
tw, th = box[2] - box[0], box[3] - box[1]

# 图片正中
x = (W - tw) // 2 - box[0]
y = (H - th) // 2 - box[1]

# 描边 + 亮色字
for dx in (-3, -2, -1, 0, 1, 2, 3):
    for dy in (-3, -2, -1, 0, 1, 2, 3):
        if dx == 0 and dy == 0:
            continue
        d.text((x + dx, y + dy), text, font=font, fill=(10, 12, 16, 235))
d.text((x, y), text, font=font, fill=(240, 246, 255, 255))

img.convert("RGB").save(OUT, quality=95)
print("saved", OUT, img.size)
