from PIL import Image, ImageDraw

res = r"E:\AI project\其他插件\爽读爽听app\app\src\main\res"
bg = Image.open(res + r"\drawable-nodpi\ic_launcher_bg.png").convert("RGBA")
fg = Image.open(res + r"\drawable-nodpi\ic_launcher_foreground.png").convert("RGBA")
comp = Image.alpha_composite(bg, fg)

# 圆形遮罩预览（大多数启动器）
size = 432
mask = Image.new("L", (size * 4, size * 4), 0)
ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
mask = mask.resize((size, size), Image.LANCZOS)
circle = Image.new("RGBA", (size, size), (0, 0, 0, 0))
circle.paste(comp, (0, 0), mask)

# 圆角方形遮罩预览
m2 = Image.new("L", (size, size), 0)
ImageDraw.Draw(m2).rounded_rectangle((0, 0, size - 1, size - 1), radius=int(size * 0.28), fill=255)
squircle = Image.new("RGBA", (size, size), (0, 0, 0, 0))
squircle.paste(comp, (0, 0), m2)

out = Image.new("RGBA", (size * 2 + 40, size), (250, 249, 246, 255))
out.paste(circle, (0, 0), circle)
out.paste(squircle, (size + 40, 0), squircle)
out.save(r"E:\AI project\其他插件\爽读爽听app\tools\icon_preview.png")
print("ok")
