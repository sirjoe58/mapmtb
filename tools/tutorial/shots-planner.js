/* Schermate del Planner per il tutorial: apre il programma (versione browser) con mappe
 * sintetiche e percorsi simulati, esegue i passaggi e salva immagini 1920x1080 + posizioni
 * dei pulsanti (per il puntatore animato). Uso: node shots-planner.js <cartella-uscita> */
'use strict';
const path = require('path');
const fs = require('fs');
const http = require('http');
const { chromium } = require('playwright');
const QRCode = require('qrcode');
const { tile, elevation } = require('./tiles');

const OUT = path.resolve(process.argv[2] || 'out/planner');
const ROOT = path.resolve(__dirname, '../../desktop');
const EARTH = 40075016.686;
const SCALE = 1.2; // 1600x900 -> 1920x1080
fs.mkdirSync(OUT, { recursive: true });

const merc = (lat, lon) => {
  const s = Math.sin((lat * Math.PI) / 180);
  return [((lon + 180) / 360) * EARTH, (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * EARTH];
};
const ele = (lat, lon) => { const [x, y] = merc(lat, lon); return elevation(x, y); };

/** tratto "calcolato": curva morbida tra a e b con le quote del terreno sintetico */
function fakeRoute(a, b) {
  const dLat = b.lat - a.lat, dLon = b.lon - a.lon;
  const len = Math.hypot(dLat * 111000, dLon * 78000);
  const n = Math.max(8, Math.round(len / 25));
  const pts = [];
  for (let i = 0; i <= n; i++) {
    const t = i / n;
    const w = Math.sin(t * Math.PI) * (0.18 * Math.sin(t * 9 + a.lat * 50) + 0.08 * Math.sin(t * 23));
    const lat = a.lat + dLat * t - dLon * w * 0.7, lon = a.lon + dLon * t + dLat * w * 1.4;
    pts.push(`<trkpt lat="${lat.toFixed(6)}" lon="${lon.toFixed(6)}"><ele>${ele(lat, lon).toFixed(1)}</ele></trkpt>`);
  }
  return `<?xml version="1.0"?><gpx version="1.1" creator="tutorial"><trk><trkseg>${pts.join('')}</trkseg></trk></gpx>`;
}

function serve() {
  return new Promise((ok) => {
    const srv = http.createServer((req, res) => {
      const p = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
      if (!p.startsWith(ROOT) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
      const ext = path.extname(p);
      res.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png' }[ext] || 'application/octet-stream' });
      fs.createReadStream(p).pipe(res);
    });
    srv.listen(0, () => ok(srv));
  });
}

(async () => {
  const srv = await serve();
  const base = `http://localhost:${srv.address().port}/app/index.html`;
  const exe = process.env.CHROME_PATH || undefined;
  const browser = await chromium.launch({ executablePath: exe, args: ['--headless=new'] });
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 }, deviceScaleFactor: SCALE, acceptDownloads: true, locale: 'it-IT' });
  // niente rete: mappe sintetiche e percorsi simulati
  await ctx.route(/^https?:\/\/(?!localhost)/, async (route) => {
    const url = route.request().url();
    let m = url.match(/\/(\d+)\/(\d+)\/(\d+)(\.png|\.jpg)?(\?.*)?$/);
    if (/arcgisonline|eox\.at/.test(url)) {
      m = url.match(/\/tile\/(\d+)\/(\d+)\/(\d+)/) || url.match(/\/g\/(\d+)\/(\d+)\/(\d+)/);
      if (m) return route.fulfill({ contentType: 'image/png', body: /Reference/.test(url) ? tile('empty') : tile('sat', +m[1], +m[3], +m[2]) });
    }
    if (/waymarkedtrails/.test(url)) return route.fulfill({ contentType: 'image/png', body: tile('empty') });
    if (/brouter/.test(url)) {
      const ll = decodeURIComponent(url.match(/lonlats=([^&]+)/)[1]).split('|').map((s) => s.split(',').map(Number));
      return route.fulfill({ contentType: 'application/gpx+xml', body: fakeRoute({ lon: ll[0][0], lat: ll[0][1] }, { lon: ll[1][0], lat: ll[1][1] }) });
    }
    if (/nominatim/.test(url)) {
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify([{ display_name: 'Monte Grappa, Veneto, Italia', lat: '45.870', lon: '11.800' }]) });
    }
    if (m) return route.fulfill({ contentType: 'image/png', body: tile('topo', +m[1], +m[2], +m[3]) });
    return route.fulfill({ status: 404, body: '' });
  });
  const qr = await QRCode.toDataURL('sirjoemtb://link?h=192.168.1.20&p=47891&t=TUTORIAL&n=PC-SERGIO', { scale: 8, margin: 4, errorCorrectionLevel: 'L' });
  await ctx.addInitScript((qrUrl) => {
    window.sirjoe = {
      version: async () => '1.4.1',
      link: {
        start: async () => ({ qr: qrUrl, port: 47891, addresses: ['192.168.1.20'], pcName: 'PC-SERGIO', pin: '482913' }),
        qr: async () => qrUrl, stop: async () => true, firewall: async () => ({ ok: true }), onEvent: () => {}
      }
    };
    localStorage.setItem('view', JSON.stringify({ lat: 45.872, lon: 11.79, z: 14 }));
  }, qr);

  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.error('pageerror', e.message));
  page.on('dialog', (d) => d.accept());
  await page.goto(base);
  await page.waitForTimeout(800);

  const meta = {};
  const rect = async (sel) => {
    const r = await page.locator(sel).first().boundingBox();
    return r ? [r.x * SCALE, r.y * SCALE, r.width * SCALE, r.height * SCALE].map(Math.round) : null;
  };
  const shot = async (name, targets = {}) => {
    await page.waitForTimeout(700);
    await page.screenshot({ path: path.join(OUT, name + '.png') });
    const t = {};
    for (const [k, v] of Object.entries(targets)) t[k] = typeof v === 'string' ? await rect(v) : v.map((n) => Math.round(n * SCALE));
    meta[name] = t;
    console.log('ok', name);
  };

  // 1. avvertenze al primo avvio
  await shot('p01-avvertenze', { accept: '#welcome button[value=ok]' });
  await page.click('#welcome button[value=ok]');
  await page.evaluate(() => { map.setView([45.872, 11.79], 14); });

  // 2. finestra vuota
  await shot('p02-finestra', { link: '#linkBtn', modes: '.seg', search: '#searchInput', base: '#baseMap', maps: '#mapsBtn', tabs: '#tabs, .tabs, aside nav' });

  // 3. percorso a punti
  await page.fill('#routeName', 'Giro del Grappa');
  const clicks = [[760, 640], [930, 470], [1130, 400], [1300, 250]];
  for (const [x, y] of clicks) { await page.mouse.click(x, y); await page.waitForTimeout(400); }
  await page.waitForTimeout(1200);
  await shot('p03-percorso', { p1: clicks[0], p2: clicks[1], p3: clicks[2], p4: clicks[3], type: '#profile', stats: '#tab-route .stats' });

  // 4. colori pendenza + altimetria
  await page.check('#slopeColors');
  await page.click('#profileTab');
  await page.waitForTimeout(600);
  await page.evaluate(() => { const r = $('elev').getBoundingClientRect(); $('elev').dispatchEvent(new MouseEvent('mousemove', { clientX: r.left + r.width * 0.55, clientY: r.top + 40, bubbles: true })); });
  await shot('p04-pendenza-altimetria', { slope: '#slopeColors', profileBtn: '#profileBtn', profile: '#profileBox', close: '#profileClose' });
  await page.click('#profileClose');

  // 5. salva
  await shot('p05-salva', { saveAs: 'text=Salva con nome…', lib: 'text=Salva in archivio…', format: '#exportFormat' });
  await page.click('text=Salva in archivio…');
  await page.fill('#ndName', 'Giro del Grappa');
  await shot('p06-archivio', { name: '#ndName', ok: '#nameDialog button.primary' });
  await page.click('#nameDialog button.primary');
  await page.waitForTimeout(500);

  // 6. disegno a mano libera
  await page.evaluate(() => clearRoute());
  await page.click('[data-mode=draw]');
  const cx = 1000, cy = 560, pts = [];
  for (let i = 0; i <= 60; i++) { const a = (i / 60) * Math.PI * 1.6 + 2.6; pts.push([cx + Math.cos(a) * 230 + Math.sin(i / 4) * 12, cy + Math.sin(a) * 140]); }
  await page.mouse.move(pts[0][0], pts[0][1]);
  await page.mouse.down();
  for (const [x, y] of pts) await page.mouse.move(x, y, { steps: 2 });
  await shot('p07-disegno-in-corso', { draw: '[data-mode=draw]', pen: pts[40] });
  await page.mouse.up();
  await page.waitForTimeout(1500);
  await shot('p08-disegno', { draw: '[data-mode=draw]', undo: '#drawUndo', clear: '#drawClear' });

  // 7. segnaposto
  await page.click('[data-mode=place]');
  await page.mouse.click(1180, 330);
  await page.waitForTimeout(500);
  if (await page.locator('#placeDialog[open]').count()) {
    await page.fill('#pdName', 'Fontana del Pian');
    await page.selectOption('#pdType', { index: 1 }).catch(() => {});
    await shot('p09-segnaposto', { dialog: '#placeDialog form', type: '#pdType', save: '#placeDialog button[value=save]' });
    await page.click('#placeDialog button[value=save]');
  }
  await page.click('.tab[data-tab=places]').catch(() => {});
  await shot('p10-segnaposto-elenco', { tab: '.tab[data-tab=places]' });
  await page.click('[data-mode=route]');

  // 8. mappe: satellite e finestra Mappe
  await page.click('.tab[data-tab=route]').catch(() => {});
  await page.selectOption('#baseMap', 'esri-sat');
  await page.waitForTimeout(1200);
  await shot('p11-satellite', { base: '#baseMap' });
  await page.click('#mapsBtn');
  await shot('p12-mappe', { file: '#mapsFile', folder: '#mapsFolder', url: '#msUrl', add: '#msAdd', sat: '#mapsSat' });
  await page.click('#mapsDialog button[value=close]');
  await page.selectOption('#baseMap', 'topo');

  // 9. uscite e Sala analisi: un'uscita registrata sul percorso salvato
  await page.evaluate(async () => {
    const lib = store.get('library', [])[0];
    const pts = []; lib.legs.forEach((l) => pts.push(...l));
    const out = []; let t = Date.parse('2026-10-12T09:05:00+02:00');
    for (let i = 0; i < pts.length; i++) {
      if (i) {
        const d = Geo.dist(pts[i - 1], pts[i]);
        const g = ((pts[i].ele || 0) - (pts[i - 1].ele || 0)) / Math.max(1, d);
        const v = Math.max(2.2, Math.min(13, 6.5 - g * 45 + Math.sin(i / 9) * 1.2)); // m/s
        t += (d / v) * 1000;
      }
      out.push({ lat: pts[i].lat + Math.sin(i / 3) * 0.00004, lon: pts[i].lon, ele: pts[i].ele, time: Math.round(t) });
    }
    await RideStore.add('Uscita 2026-10-12 09-05', Formats.gpx('Uscita 2026-10-12 09-05', out, []));
    await RideStore.add('Uscita 2026-10-05 15-30', Formats.gpx('Uscita 2026-10-05 15-30', out.map((p, i) => ({ ...p, lon: p.lon + 0.004, time: p.time - 7 * 864e5 })), []));
  }).catch((e) => console.error(e.message));
  await page.click('.tab[data-tab=rides]');
  await page.evaluate(() => Analysis.renderList());
  await shot('p13-uscite', { list: '#rideList', add: 'text=Aggiungi da file…' });
  await page.click('#rideList li');
  await page.waitForTimeout(1000);
  await shot('p14-analisi', { color: '#anColor', stats: '#anDist', play: '#anPlay', compare: '#anCompare', climbs: '#anClimbs', video: '#anVideo' });
  await page.selectOption('#anColor', { index: 1 }).catch(() => {});
  await page.evaluate(() => { const s = $('anSlider'); s.value = Math.round(s.max * 0.45); s.dispatchEvent(new Event('input')); });
  await shot('p15-replay', { slider: '#anSlider', now: '#anNow', play: '#anPlay' });
  const lib = await page.evaluate(() => (store.get('library', [])[0] || {}).id);
  if (lib) { await page.selectOption('#anCompare', { index: 1 }).catch(() => {}); }
  await page.locator('#anClimbs').scrollIntoViewIfNeeded().catch(() => {});
  await shot('p16-confronto-salite', { compare: '#anCompare', out: '#anCompareOut', climbs: '#anClimbs', video: '#anVideo' });
  await page.evaluate(() => Analysis.close && Analysis.close());

  // 10. le mie uscite sulla mappa
  await page.click('.tab[data-tab=route]').catch(() => {});
  await page.check('#ridesOverlay');
  await page.waitForTimeout(1200);
  await shot('p17-mie-uscite', { rides: '#ridesOverlay' });
  await page.uncheck('#ridesOverlay');

  // 11. SirJoe Link
  await page.click('#linkBtn');
  await page.waitForTimeout(800);
  await shot('p18-link', { link: '#linkBtn', qr: '#linkQr', manual: '#linkManual', fw: '#linkFw', ping: '#linkPing' });

  fs.writeFileSync(path.join(OUT, 'targets.json'), JSON.stringify(meta, null, 1));
  await browser.close();
  srv.close();
})().catch((e) => { console.error(e); process.exit(1); });
