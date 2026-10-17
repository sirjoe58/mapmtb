/* Riquadri di mappa sintetici per le riprese del tutorial (colline, curve di livello, sentieri).
 * Servono solo a mostrare i programmi senza dipendere dai server di mappe: non sono luoghi reali. */
'use strict';
const { PNG } = require('pngjs');

const EARTH = 40075016.686;
function hash(ix, iy, s) {
  let h = (ix * 374761393 + iy * 668265263 + s * 2147483647) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}
function noise(x, y, s) {
  const ix = Math.floor(x), iy = Math.floor(y), fx = x - ix, fy = y - iy;
  const u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy);
  const a = hash(ix, iy, s), b = hash(ix + 1, iy, s), c = hash(ix, iy + 1, s), d = hash(ix + 1, iy + 1, s);
  return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
}
/** quota (m) in un punto in metri Web Mercator */
function elevation(mx, my) {
  let e = 0, amp = 700, wl = 9000;
  for (let o = 0; o < 5; o++) {
    e += amp * noise(mx / wl, my / wl, o + 1);
    amp *= 0.45; wl *= 0.42;
  }
  return 150 + e;
}

function colorFor(e, shade, kind) {
  if (kind === 'sat') {
    // "satellite": boschi scuri, prati, rocce in alto
    const g = Math.max(0, Math.min(1, (e - 300) / 700));
    const base = [[48, 78, 40], [70, 96, 52], [110, 112, 82], [150, 142, 120]];
    const i = Math.min(2, Math.floor(g * 3)), f = g * 3 - i;
    const c = base[i].map((v, k) => v + (base[i + 1][k] - v) * f);
    return c.map((v) => v * (0.62 + 0.55 * shade));
  }
  // topografica: verde chiaro in basso, beige e grigio in alto
  const stops = [[0, [196, 222, 170]], [400, [214, 228, 176]], [700, [232, 222, 182]], [1000, [222, 205, 175]], [1300, [235, 232, 228]]];
  let c = stops[stops.length - 1][1];
  for (let i = 0; i < stops.length - 1; i++) {
    if (e < stops[i + 1][0]) {
      const f = Math.max(0, (e - stops[i][0]) / (stops[i + 1][0] - stops[i][0]));
      c = stops[i][1].map((v, k) => v + (stops[i + 1][1][k] - v) * f);
      break;
    }
  }
  return c.map((v) => v * (0.8 + 0.32 * shade));
}

function tile(kind, z, x, y) {
  const png = new PNG({ width: 256, height: 256 });
  if (kind === 'empty') return PNG.sync.write(png); // trasparente
  const n = 256 * 2 ** z;
  const mpp = EARTH / n; // metri per pixel
  const E = new Float32Array(258 * 258);
  for (let py = -1; py < 257; py++) {
    for (let px = -1; px < 257; px++) {
      const mx = ((x * 256 + px) / n) * EARTH, my = ((y * 256 + py) / n) * EARTH;
      E[(py + 1) * 258 + px + 1] = elevation(mx, my);
    }
  }
  const step = z >= 15 ? 20 : z >= 13 ? 50 : 100;
  for (let py = 0; py < 256; py++) {
    for (let px = 0; px < 256; px++) {
      const i = (py + 1) * 258 + px + 1;
      const e = E[i];
      const dx = (E[i + 1] - E[i - 1]) / (2 * mpp), dy = (E[i + 258] - E[i - 258]) / (2 * mpp);
      const shade = Math.max(0, Math.min(1, 0.6 + (-dx * 0.7 - dy * 0.7) * 3));
      let c = colorFor(e, shade, kind);
      if (kind !== 'sat') {
        // curve di livello
        const a = Math.floor(e / step), b = Math.floor(E[i + 1] / step), d = Math.floor(E[i + 258] / step);
        if (a !== b || a !== d) {
          const major = (Math.max(a, b, d) * step) % (step * 5) === 0;
          c = c.map((v) => v * (major ? 0.62 : 0.8));
        }
      }
      // sentieri e stradine: linee lungo valori costanti di un rumore lento
      const mx = ((x * 256 + px) / n) * EARTH, my = ((y * 256 + py) / n) * EARTH;
      const r = noise(mx / 2600, my / 2600, 9) * 6;
      const rr = Math.abs(r - Math.round(r));
      const width = 0.012 * Math.max(1, 15 - z) + 0.006;
      if (rr < width) c = kind === 'sat' ? [200, 190, 160] : (Math.round(r) % 2 ? [170, 60, 50] : [255, 255, 255]);
      const o = (py * 256 + px) * 4;
      png.data[o] = Math.min(255, c[0]); png.data[o + 1] = Math.min(255, c[1]); png.data[o + 2] = Math.min(255, c[2]); png.data[o + 3] = 255;
    }
  }
  return PNG.sync.write(png);
}

module.exports = { tile, elevation };
