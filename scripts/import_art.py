"""
Import card images into the app.

1. Put your images in  art/instruments/  and  art/beats/
   named exactly like the list in docs/IMAGE_PROMPTS.md (e.g. grand.png, trap.jpg).
2. Run:   python scripts/import_art.py
3. They're cropped, resized and compressed to WebP in app/src/main/assets/img/.

Any missing image just falls back to the built-in illustration in the app.
"""
import os
import sys

try:
    from PIL import Image, ImageOps
except ImportError:
    sys.exit("Pillow is required:  pip install pillow")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JOBS = [
    ("art/instruments", "app/src/main/assets/img/inst", (800, 600)),   # 4:3
    ("art/beats", "app/src/main/assets/img/beat", (800, 500)),          # 16:10
]
EXTS = {".png", ".jpg", ".jpeg", ".webp"}

total = 0
for src, dst, size in JOBS:
    src, dst = os.path.join(ROOT, src), os.path.join(ROOT, dst)
    if not os.path.isdir(src):
        continue
    os.makedirs(dst, exist_ok=True)
    for name in sorted(os.listdir(src)):
        stem, ext = os.path.splitext(name)
        if ext.lower() not in EXTS:
            continue
        stem = stem.strip().lower().replace(" ", "")
        im = ImageOps.exif_transpose(Image.open(os.path.join(src, name))).convert("RGB")
        im = ImageOps.fit(im, size, Image.LANCZOS, centering=(0.5, 0.5))
        out = os.path.join(dst, stem + ".webp")
        im.save(out, "WEBP", quality=80, method=6)
        total += 1
        print(f"  {os.path.relpath(out, ROOT)}  ({os.path.getsize(out) // 1024} KB)")
print(f"Imported {total} images.")
