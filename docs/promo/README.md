# Promo film

A 46-second film for OpenPhoneUse. It is one HTML page, `promo.src.html`, rendered to video frame by frame. Every frame is a pure function of a virtual clock, so the render does not depend on how fast the machine is, and the same page can be previewed live in a browser.

| File | What it is |
|---|---|
| `promo.src.html` | The film. Edit this one. |
| `build.py` | Copies `promo.src.html` to `promo.html` (git-ignored), the page `render.mjs` loads. |
| `render.mjs` | Steps the page frame by frame with puppeteer-core and pipes the frames to ffmpeg. |
| `out/` | Renders and stills (git-ignored). |

The film uses the app screenshots in `../design/assets/`, so keep that folder where it is.

## Preview

Open `promo.src.html` (or `promo.html`) in Chrome. `?v=9x16` is the vertical cut and `?t=12` starts at 12 seconds. The page loads Geist and Instrument Serif from Google Fonts, so it needs a network connection.

## Render

```bash
python docs/promo/build.py                      # promo.src.html -> promo.html
node docs/promo/render.mjs                      # 1920x1080, 60 fps -> out/promo-16x9.mp4
node docs/promo/render.mjs --v 9x16             # 1080x1920 vertical cut -> out/promo-9x16.mp4
node docs/promo/render.mjs --blur 2             # motion blur: 2 samples per frame (default 2, 1 turns it off)
node docs/promo/render.mjs --stills 2,9,14      # PNG stills at those seconds, for checking
node docs/promo/render.mjs --fps 30 --from 10 --to 23
```

What it needs:

- **Node** (a recent version; the script uses top-level `await`) and **puppeteer-core**. If it is not installed next to the script, point the `PUPPETEER` environment variable at its ESM entry file.
- **Chrome.** The script looks for `C:/Program Files/Google/Chrome/Application/chrome.exe`; set `CHROME` to another path on macOS or Linux.
- **ffmpeg** on `PATH`.
- Network access for the web fonts.

A full 16:9 render at 60 fps with blur takes a while because every output frame is several screenshots. Use `--stills` or `--from` / `--to` while editing. The masters are H.264, yuv420p, limited range, BT.709.

## Timing

The cut is on a 120 BPM grid: a beat is 0.5 s, a bar is 2 s, and the drop is at 0:08. A 120 BPM track lines up without editing.

## Adding music

The render has no sound. Mux a track with ffmpeg; the fade starts 1.2 s before the end:

```bash
ffmpeg -i docs/promo/out/promo-16x9.mp4 -i music.mp3 -c:v copy -c:a aac -b:a 256k -shortest \
  -af "afade=t=out:st=44.8:d=1.2" promo-final.mp4
```
