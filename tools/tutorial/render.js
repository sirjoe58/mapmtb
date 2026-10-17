/* Monta il videotutorial: per ogni scena disegna i fotogrammi (compose.html) e li passa a ffmpeg.
 * Se nella cartella voce/ ci sono i file NN.wav (voce italiana, generata da voice.sh), le scene durano
 * quanto il parlato e l'audio viene aggiunto; altrimenti durate stimate e video solo sottotitolato.
 * Uso: node render.js <cartella-schermate> <video.mp4> [cartella-voce] */
'use strict';
const path = require('path');
const fs = require('fs');
const http = require('http');
const { spawn } = require('child_process');
const { chromium } = require('playwright');
const scenes = require('./scenes');

const SHOTS = path.resolve(process.argv[2] || 'out');
const OUTFILE = path.resolve(process.argv[3] || 'out/SirJoe-MTB-Videotutorial.mp4');
const VOICE = process.argv[4] ? path.resolve(process.argv[4]) : null;
const FPS = +(process.env.FPS || 15);
const LEAD = 0.35; // pausa prima della voce

/** WAV PCM 16 bit: { rate, channels, samples: Int16Array } */
function readWav(f) {
  const b = fs.readFileSync(f);
  let o = 12, fmt = null, data = null;
  while (o < b.length - 8) {
    const id = b.toString('ascii', o, o + 4), len = b.readUInt32LE(o + 4);
    if (id === 'fmt ') fmt = { channels: b.readUInt16LE(o + 10), rate: b.readUInt32LE(o + 12), bits: b.readUInt16LE(o + 22) };
    if (id === 'data') data = b.subarray(o + 8, o + 8 + len);
    o += 8 + len + (len % 2);
  }
  if (!fmt || !data || fmt.bits !== 16) throw new Error('WAV non supportato: ' + f);
  return { rate: fmt.rate, channels: fmt.channels, samples: new Int16Array(data.buffer.slice(data.byteOffset, data.byteOffset + data.length)) };
}
function writeWav(f, rate, samples) {
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + samples.length * 2, 4); h.write('WAVEfmt ', 8);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(rate, 24);
  h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(samples.length * 2, 40);
  fs.writeFileSync(f, Buffer.concat([h, Buffer.from(samples.buffer)]));
}

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.png': 'image/png', '.ttf': 'font/ttf', '.json': 'application/json' };
const srv = http.createServer((req, res) => {
  const u = decodeURIComponent(req.url.split('?')[0]);
  const p = u.startsWith('/shots/') ? path.join(SHOTS, u.slice(7)) : path.join(__dirname, u);
  if (!fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(p)] || 'application/octet-stream' });
  fs.createReadStream(p).pipe(res);
});

srv.listen(0, async () => {
  // durate delle scene
  const voices = scenes.map((_, i) => {
    const f = VOICE && path.join(VOICE, String(i).padStart(2, '0') + '.wav');
    return f && fs.existsSync(f) ? readWav(f) : null;
  });
  const dur = scenes.map((sc, i) => {
    const v = voices[i];
    const base = v ? v.samples.length / v.channels / v.rate + LEAD + 0.7 : sc.say.length / 14 + 1.2;
    return Math.max(sc.title && !sc.list ? 5 : 4, base) + (sc.end ? 1.5 : 0);
  });
  const total = dur.reduce((a, b) => a + b, 0);
  console.log(`scene: ${scenes.length}, durata: ${Math.floor(total / 60)}:${String(Math.round(total % 60)).padStart(2, '0')}, voce: ${voices.filter(Boolean).length ? 'sì' : 'no'}`);

  // traccia audio unica
  let audioFile = null;
  if (voices.some(Boolean)) {
    const rate = voices.find(Boolean).rate;
    const all = new Int16Array(Math.ceil(total * rate) + rate);
    let t = 0;
    voices.forEach((v, i) => {
      if (v) {
        const start = Math.round((t + LEAD) * rate);
        for (let k = 0, j = 0; k < v.samples.length; k += v.channels, j++) all[start + j] = v.samples[k];
      }
      t += dur[i];
    });
    audioFile = OUTFILE.replace(/\.mp4$/, '.wav');
    writeWav(audioFile, rate, all);
  }

  fs.mkdirSync(path.dirname(OUTFILE), { recursive: true });
  const args = ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-i', '-'];
  if (audioFile) args.push('-i', audioFile);
  args.push('-vf', 'fps=30', '-c:v', 'libx264', '-preset', 'medium', '-crf', '21', '-pix_fmt', 'yuv420p', '-movflags', '+faststart');
  if (audioFile) args.push('-c:a', 'aac', '-b:a', '128k', '-shortest');
  args.push(OUTFILE);
  const ff = spawn(process.env.FFMPEG || 'ffmpeg', args, { stdio: ['pipe', 'inherit', 'inherit'] });
  const write = (buf) => new Promise((ok) => (ff.stdin.write(buf) ? ok() : ff.stdin.once('drain', ok)));

  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined, args: ['--headless=new'] });
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  page.on('pageerror', (e) => console.error('pageerror', e.message));
  await page.goto(`http://localhost:${srv.address().port}/compose.html`);
  const cdp = await page.context().newCDPSession(page);
  const targets = JSON.parse(fs.readFileSync(path.join(SHOTS, 'planner', 'targets.json'), 'utf8'));
  await page.evaluate((t) => window.setup(t), targets);
  const only = process.env.ONLY ? process.env.ONLY.split(',').map(Number) : null;
  for (let i = 0; i < scenes.length; i++) {
    if (only && !only.includes(i)) continue;
    const n = Math.round(dur[i] * FPS);
    await page.evaluate((sc) => window.draw(sc, 0.5, 5), scenes[i]);
    await page.waitForTimeout(250); // immagini caricate
    let lastSig = null, lastBuf = null;
    for (let f = 0; f < n; f++) {
      const sig = await page.evaluate(([sc, t, d]) => window.draw(sc, t, d), [scenes[i], f / FPS, dur[i]]);
      if (sig !== lastSig) {
        // fotogramma cambiato: nuova istantanea (altrimenti si ripete la precedente)
        const r = await cdp.send('Page.captureScreenshot', { format: 'jpeg', quality: 88, optimizeForSpeed: true });
        lastBuf = Buffer.from(r.data, 'base64');
        lastSig = sig;
      }
      await write(lastBuf);
    }
    process.stdout.write(`scena ${i + 1}/${scenes.length}\r`);
  }
  ff.stdin.end();
  await new Promise((ok) => ff.on('close', ok));
  await browser.close();
  srv.close();
  if (audioFile) fs.unlinkSync(audioFile);
  console.log('\nvideo pronto:', OUTFILE);
});
