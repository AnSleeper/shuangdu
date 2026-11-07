import os
from PIL import Image, ImageEnhance, ImageDraw, ImageFilter

SRC = r"E:\AI project\其他插件\爽读爽听app\程序图标素材\saki被窝玩手机.jpg"
RES = r"E:\AI project\其他插件\爽读爽听app\app\src\main\res"

img = Image.open(SRC).convert("RGB")
w, h = img.size
side = min(w, h)
left = (w - side) // 2
top = (h - side) // 2
sq = img.crop((left, top, left + side, top + side))

CANVAS = 432
photo = sq.resize((CANVAS, CANVAS), Image.LANCZOS)

# 背景：稍微放大 + 模糊 + 压暗，和前景照片边缘自然衔接
bg_src = sq.resize((int(side * 1.16), int(side * 1.16)), Image.LANCZOS)
bg_src = bg_src.crop(((bg_src.width - CANVAS) // 2, (bg_src.height - CANVAS) // 2,
                      (bg_src.width - CANVAS) // 2 + CANVAS, (bg_src.height - CANVAS) // 2 + CANVAS))
bg = bg_src.filter(ImageFilter.GaussianBlur(16))
bg = ImageEnhance.Brightness(bg).enhance(0.62)
bg = ImageEnhance.Color(bg).enhance(1.15)
bg.save(os.path.join(RES, "drawable-nodpi", "ic_launcher_bg.png"), optimize=True)

# 前景：照片缩到 92%，居中，留出遮罩余量
inner = int(CANVAS * 0.92)
fg = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
photo_small = photo.resize((inner, inner), Image.LANCZOS)
mask = Image.new("L", (inner, inner), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, inner - 1, inner - 1), radius=int(inner * 0.14), fill=255)
fg.paste(photo_small, ((CANVAS - inner) // 2, (CANVAS - inner) // 2), mask)
fg.save(os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png"), optimize=True)

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

for name, size in DENSITIES.items():
    folder = os.path.join(RES, "mipmap-" + name)
    os.makedirs(folder, exist_ok=True)

    # 方形图标（圆角）
    sq_icon = photo.resize((size, size), Image.LANCZOS)
    r = int(size * 0.22)
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size - 1, size - 1), radius=r, fill=255)
    rounded = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rounded.paste(sq_icon, (0, 0), m)
    rounded.save(os.path.join(folder, "ic_launcher.png"), optimize=True)

    # 圆形图标
    circ_icon = photo.resize((size, size), Image.LANCZOS)
    m2 = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(m2).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    m2 = m2.resize((size, size), Image.LANCZOS)
    circle = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    circle.paste(circ_icon, (0, 0), m2)
    circle.save(os.path.join(folder, "ic_launcher_round.png"), optimize=True)

print("icons done")
