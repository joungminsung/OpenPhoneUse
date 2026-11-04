"""Copy promo.src.html to promo.html, the page render.mjs loads, and print its size.

promo.src.html is the source of the film. promo.html is a generated copy and is git-ignored.

    python docs/promo/build.py
"""
from pathlib import Path

HERE = Path(__file__).resolve().parent

data = (HERE / 'promo.src.html').read_bytes()
(HERE / 'promo.html').write_bytes(data)
print('promo.html', len(data) // 1024, 'KB')
