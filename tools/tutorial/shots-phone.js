/* Schermate dell'app Android (riproduzioni in phone.html) per il tutorial.
 * Uso: node shots-phone.js <cartella-uscita> */
'use strict';
const path = require('path');
const fs = require('fs');
const http = require('http');
const { chromium } = require('playwright');
const QRCode = require('qrcode');
const { tile } = require('./tiles');

const OUT = path.resolve(process.argv[2] || 'out/phone');
const ROOT = path.resolve(__dirname, '../..');
fs.mkdirSync(OUT, { recursive: true });
const SCREENS = ['avvertenze', 'start', 'nav', 'orient', 'layers', 'hidden', 'climb', 'reverse', 'offroute', 'ghost', 'dim', 'rides',
  'crea', 'creatype', 'placemark', 'placemenu', 'dati', 'percorsi', 'traccia', 'condividi', 'offline', 'link', 'scan', 'linkok', 'info', 'strava'];

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.ttf': 'font/ttf' };
const srv = http.createServer((req, res) => {
  const p = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
  if (!p.startsWith(ROOT) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(p)] || 'application/octet-stream' });
  fs.createReadStream(p).pipe(res);
});

srv.listen(0, async () => {
  const port = srv.address().port;
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined, args: ['--headless=new'] });
  const ctx = await browser.newContext({ viewport: { width: 420, height: 840 }, deviceScaleFactor: 2.5 });
  await ctx.route(/^https?:\/\/(?!localhost)/, (route) => {
    const m = route.request().url().match(/\/(\d+)\/(\d+)\/(\d+)\.png/);
    return m ? route.fulfill({ contentType: 'image/png', body: tile('topo', +m[1], +m[2], +m[3]) }) : route.fulfill({ status: 404, body: '' });
  });
  const qr = await QRCode.toDataURL('sirjoemtb://link?h=192.168.1.20&p=47891&t=TUTORIAL&n=PC-SERGIO', { scale: 6, margin: 2 });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.error('pageerror', e.message));
  for (const s of SCREENS) {
    await page.goto(`http://localhost:${port}/tools/tutorial/phone.html?s=${s}`);
    await page.waitForSelector('body[data-ready="1"]');
    await page.evaluate((q) => { const i = document.querySelector('.scan .qr'); if (i) i.src = q; }, qr);
    await page.waitForTimeout(300);
    await page.locator('#phone').screenshot({ path: path.join(OUT, `a-${s}.png`) });
    console.log('ok', s);
  }
  await browser.close();
  srv.close();
});
