// Render promo.html to video, frame by frame, on the page's own virtual clock.
//
//   node docs/promo/render.mjs                    1920×1080, 60 fps → docs/promo/out/promo-16x9.mp4
//   node docs/promo/render.mjs --v 9x16           1080×1920 vertical cut
//   node docs/promo/render.mjs --stills 2,9,14    PNG stills at those seconds, for checking
//   node docs/promo/render.mjs --fps 30 --from 10 --to 23
//   node docs/promo/render.mjs --blur 3            motion blur: 3 samples per frame, averaged (default 2; 1 turns it off)
//
// Needs puppeteer-core (PUPPETEER=<path to its esm entry> if it is not installed here), Chrome, and ffmpeg on PATH.
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const arg = (name, dflt) => { const i = process.argv.indexOf('--' + name); return i > 0 ? process.argv[i + 1] : dflt; };
const V = arg('v', '16x9'), FPS = +arg('fps', 60), stills = arg('stills'), BLUR = Math.max(1, +arg('blur', 2));
const CHROME = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const { default: puppeteer } = await import(process.env.PUPPETEER ? pathToFileURL(process.env.PUPPETEER).href : 'puppeteer-core');

const [W, H] = V === '9x16' ? [1080, 1920] : [1920, 1080];
const browser = await puppeteer.launch({
  executablePath: CHROME, headless: true,
  args: ['--allow-file-access-from-files', '--hide-scrollbars', '--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--force-color-profile=srgb', '--font-render-hinting=none'],
});
const page = await browser.newPage();
await page.setViewport({ width: W, height: H, deviceScaleFactor: 1 });
page.on('pageerror', e => console.error('page error:', e.message));
await page.goto(pathToFileURL(join(HERE, 'promo.html')).href + `?render=1&v=${V}`, { waitUntil: 'networkidle0' });
await page.evaluate(() => window.__ready);
const dur = await page.evaluate(() => window.__DURATION);
const outDir = join(HERE, 'out'); mkdirSync(outDir, { recursive: true });

// the clock only moves forward, so every frame (and still) is stepped up to in order
async function stepTo(T, last) { for (let t = last; t < T; t += 1 / 30) await page.evaluate(x => window.__frame(x), t); await page.evaluate(x => window.__frame(x), T); }

if (stills) {
  let last = 0;
  for (const s of stills.split(',').map(Number).sort((a, b) => a - b)) {
    await stepTo(s, last); last = s;
    const f = join(outDir, `still-${V}-${String(s).replace('.', '_')}.png`);
    await page.screenshot({ path: f, clip: { x: 0, y: 0, width: W, height: H } });
    console.log(f);
  }
  await browser.close(); process.exit(0);
}

const from = +arg('from', 0), to = +arg('to', dur);
const file = join(outDir, `promo-${V}${from || to !== dur ? `-${from}-${to}` : ''}.mp4`);
// every output frame averages BLUR samples spread across its own duration, like a film shutter
// the screenshots are JPEG: full-range BT.601. The master is limited-range BT.709, converted, not just relabelled.
const toRec709 = 'scale=flags=lanczos+accurate_rnd+full_chroma_int:in_range=pc:out_range=tv:in_color_matrix=bt601:out_color_matrix=bt709,format=yuv420p,setparams=range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709';
const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(FPS * BLUR), '-c:v', 'mjpeg', '-i', '-',
  '-vf', `${BLUR > 1 ? `tmix=frames=${BLUR},framestep=${BLUR},` : ''}${toRec709}`, '-r', String(FPS), '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
  '-colorspace', 'bt709', '-color_primaries', 'bt709', '-color_trc', 'bt709', '-color_range', 'tv', '-crf', '16', '-preset', 'slow', '-movflags', '+faststart', file], { stdio: ['pipe', 'inherit', 'inherit'] });
await stepTo(from, 0);
const n = Math.round((to - from) * FPS * BLUR), t0 = Date.now();
for (let i = 0; i < n; i++) {
  await page.evaluate(x => window.__frame(x), from + i / (FPS * BLUR));
  const buf = await page.screenshot({ type: 'jpeg', quality: 95, optimizeForSpeed: true, clip: { x: 0, y: 0, width: W, height: H } });
  if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
  if (i % (FPS * BLUR) === 0) console.log(`${(i / FPS / BLUR).toFixed(0)}s / ${(n / FPS / BLUR).toFixed(0)}s  (${((Date.now() - t0) / 1000).toFixed(0)}s elapsed)`);
}
ff.stdin.end();
await new Promise(r => ff.on('close', r));
await browser.close();
console.log(file);
