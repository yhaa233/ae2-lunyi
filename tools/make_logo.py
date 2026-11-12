from PIL import Image, ImageDraw
import math

S = 128
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

BLACK = (26, 26, 30, 255)
TIRE = (38, 38, 44, 255)
WOOD_DARK = (122, 74, 36, 255)
WOOD = (188, 128, 70, 255)
WOOD_LIGHT = (214, 160, 104, 255)
STEEL = (176, 182, 190, 255)
ACCENT = (58, 214, 190, 255)


def wheel(cx, cy, r, width):
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=TIRE)
    inner = r - width
    d.ellipse([cx - inner, cy - inner, cx + inner, cy + inner], fill=(0, 0, 0, 0))
    # spokes
    for i in range(6):
        a = math.radians(i * 30)
        d.line(
            [cx - math.cos(a) * inner * 0.9, cy - math.sin(a) * inner * 0.9,
             cx + math.cos(a) * inner * 0.9, cy + math.sin(a) * inner * 0.9],
            fill=STEEL, width=2,
        )
    d.ellipse([cx - 4, cy - 4, cx + 4, cy + 4], fill=STEEL)


# 大轮（后轮）与小轮（前轮）
wheel(34, 92, 26, 8)
wheel(100, 104, 13, 6)

# 车架
frame = [(34, 92), (44, 62), (86, 62), (100, 104)]
d.line(frame[0:3], fill=STEEL, width=5, joint="curve")
d.line([(86, 62), (100, 104)], fill=STEEL, width=5)

# 座面
d.rounded_rectangle([42, 58, 92, 70], radius=4, fill=WOOD)
d.rounded_rectangle([42, 58, 92, 61], radius=3, fill=WOOD_LIGHT)

# 靠背
d.rounded_rectangle([86, 22, 100, 62], radius=5, fill=WOOD)
d.rounded_rectangle([86, 22, 90, 62], radius=4, fill=WOOD_LIGHT)
d.rounded_rectangle([86, 22, 100, 28], radius=4, fill=WOOD_LIGHT)

# 扶手
d.rounded_rectangle([74, 48, 98, 54], radius=3, fill=WOOD_DARK)

# 脚踏
d.line([(44, 70), (44, 96)], fill=STEEL, width=4)
d.rounded_rectangle([34, 96, 54, 102], radius=3, fill=WOOD_DARK)

# 速度光点（强调"加速"）
for i, x in enumerate((12, 20, 28)):
    d.rounded_rectangle([x, 30 + i * 6, x + 8 - i * 2, 33 + i * 6], radius=2, fill=ACCENT)

img.save(r"E:\lunyi\src\main\resources\logo.png")
print("saved", img.size)
