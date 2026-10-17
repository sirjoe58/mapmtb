/* SirJoe MTB Planner - mappe da file (processo principale).
 *
 * Mappe a riquadri servite alla finestra con l'indirizzo sjtile://<id>/<z>/<x>/<y>:
 *   - MBTiles (.mbtiles)                         - SQLiteDB di OsmAnd / Locus / RMaps (.sqlitedb)
 *   - PMTiles a immagini (.pmtiles)               - cartella di riquadri z/x/y (Mobile Atlas Creator, ecc.)
 * Immagini georeferenziate, mostrate come una sovrapposizione unica:
 *   - GeoTIFF (.tif/.tiff) in WGS84 o Web Mercator
 *   - KMZ / KML con GroundOverlay (anche le "Custom Maps" Garmin)
 *   - immagine JPG/PNG/GIF/WebP con world file (.jgw .pgw .wld …)
 *   - immagine con calibrazione OziExplorer (.map)
 */
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const MIME = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp', gif: 'image/gif', avif: 'image/avif' };
const IMG_EXT = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'tif', 'tiff'];

function sniff(buf) {
  if (!buf || buf.length < 4) return 'application/octet-stream';
  if (buf[0] === 0x89 && buf[1] === 0x50) return 'image/png';
  if (buf[0] === 0xff && buf[1] === 0xd8) return 'image/jpeg';
  if (buf.toString('ascii', 0, 4) === 'RIFF') return 'image/webp';
  if (buf.toString('ascii', 0, 3) === 'GIF') return 'image/gif';
  return 'application/octet-stream';
}

/* Web Mercator (metri) -> gradi */
const merc2lon = (x) => (x / 6378137) * (180 / Math.PI);
const merc2lat = (y) => (Math.atan(Math.exp(y / 6378137)) * 2 - Math.PI / 2) * (180 / Math.PI);

function unsupported(msg) {
  const e = new Error(msg);
  e.unsupported = true;
  return e;
}

/* ------------------------------------------------------------ riquadri */

class SqliteTiles {
  constructor(file) {
    const Database = require('better-sqlite3');
    this.db = new Database(file, { readonly: true, fileMustExist: true });
    const tables = this.db.prepare("SELECT name FROM sqlite_master WHERE type IN ('table','view')").all().map((r) => r.name.toLowerCase());
    if (!tables.includes('tiles')) throw unsupported('Il database non contiene riquadri di mappa');
    const cols = this.db.prepare('PRAGMA table_info(tiles)').all().map((c) => c.name.toLowerCase());
    this.info = { minzoom: 0, maxzoom: 19, bounds: null, name: path.basename(file).replace(/\.[^.]+$/, '') };
    if (cols.includes('zoom_level')) {
      // MBTiles: righe numerate dal basso (TMS)
      this.kind = 'mbtiles';
      this.q = this.db.prepare('SELECT tile_data AS d FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?');
      if (tables.includes('metadata')) {
        const meta = Object.fromEntries(this.db.prepare('SELECT name, value FROM metadata').all().map((r) => [r.name, r.value]));
        if (meta.name) this.info.name = meta.name;
        if (meta.minzoom) this.info.minzoom = +meta.minzoom;
        if (meta.maxzoom) this.info.maxzoom = +meta.maxzoom;
        if (meta.bounds) {
          const b = meta.bounds.split(',').map(Number);
          if (b.length === 4 && b.every(Number.isFinite)) this.info.bounds = [[b[1], b[0]], [b[3], b[2]]];
        }
        if (meta.format && /pbf|mvt/i.test(meta.format)) throw unsupported('MBTiles vettoriale (pbf): il Planner mostra solo mappe a immagini');
        if (meta.attribution) this.info.attribution = meta.attribution;
      }
      if (!this.db.prepare('SELECT 1 FROM tiles LIMIT 1').get()) throw unsupported('Il file MBTiles è vuoto');
      const z = this.db.prepare('SELECT MIN(zoom_level) a, MAX(zoom_level) b FROM tiles').get();
      if (z && z.b != null) { this.info.minzoom = z.a; this.info.maxzoom = z.b; }
      this.get = (zz, x, y) => this.q.get(zz, x, (1 << zz) - 1 - y);
    } else if (cols.includes('x') && cols.includes('y') && cols.includes('z')) {
      // SQLiteDB (RMaps / OsmAnd / Locus): z salvato come 17 - zoom, salvo diversa indicazione
      this.kind = 'sqlitedb';
      const img = cols.includes('image') ? 'image' : cols.includes('tile_data') ? 'tile_data' : null;
      if (!img) throw unsupported('Formato SQLiteDB sconosciuto');
      this.q = this.db.prepare(`SELECT ${img} AS d FROM tiles WHERE x=? AND y=? AND z=?`);
      let info = {};
      if (tables.includes('info')) {
        try { info = this.db.prepare('SELECT * FROM info LIMIT 1').get() || {}; } catch (e) { /* tabella diversa */ }
      }
      const lower = Object.fromEntries(Object.entries(info).map(([k, v]) => [k.toLowerCase(), v]));
      const simple = String(lower.tilenumbering || '').toLowerCase() === 'simple';
      const invY = String(lower.inverted_y || '0') === '1';
      const zr = this.db.prepare('SELECT MIN(z) a, MAX(z) b FROM tiles').get();
      if (zr && zr.b != null) {
        this.info.minzoom = simple ? zr.a : 17 - zr.b;
        this.info.maxzoom = simple ? zr.b : 17 - zr.a;
      }
      this.invY = invY;
      this.zStored = (zz) => (simple ? zz : 17 - zz);
      this.get = (zz, x, y) => this.q.get(x, invY ? (1 << zz) - 1 - y : y, this.zStored(zz));
    } else {
      throw unsupported('Database SQLite non riconosciuto come mappa');
    }
    // area coperta, se non dichiarata: dai riquadri allo zoom più basso
    if (!this.info.bounds) this.info.bounds = this._boundsFromTiles();
  }

  _boundsFromTiles() {
    try {
      const z = this.info.minzoom;
      let r;
      if (this.kind === 'mbtiles') {
        r = this.db.prepare('SELECT MIN(tile_column) x0, MAX(tile_column) x1, MIN(tile_row) y0, MAX(tile_row) y1 FROM tiles WHERE zoom_level=?').get(z);
        if (!r || r.x0 == null) return null;
        const n = 1 << z;
        [r.y0, r.y1] = [n - 1 - r.y1, n - 1 - r.y0];
      } else {
        r = this.db.prepare('SELECT MIN(x) x0, MAX(x) x1, MIN(y) y0, MAX(y) y1 FROM tiles WHERE z=?').get(this.zStored(z));
        if (!r || r.x0 == null) return null;
        if (this.invY) { const n = 1 << z; [r.y0, r.y1] = [n - 1 - r.y1, n - 1 - r.y0]; }
      }
      return tileBounds(z, r.x0, r.y0, r.x1, r.y1);
    } catch (e) { return null; }
  }

  async tile(z, x, y) {
    const r = this.get(z, x, y);
    return r && r.d ? Buffer.from(r.d) : null;
  }

  close() { try { this.db.close(); } catch (e) { /* già chiuso */ } }
}

function tileBounds(z, x0, y0, x1, y1) {
  const n = 2 ** z;
  const lon = (x) => (x / n) * 360 - 180;
  const lat = (y) => (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / n))) * 180) / Math.PI;
  return [[lat(y1 + 1), lon(x0)], [lat(y0), lon(x1 + 1)]];
}

/** PMTiles letto direttamente dal file, a pezzi */
class PmTiles {
  constructor(file) {
    const { PMTiles } = require('pmtiles');
    const fd = fs.openSync(file, 'r');
    this.fd = fd;
    const source = {
      getKey: () => file,
      getBytes: async (offset, length) => {
        const buf = Buffer.alloc(length);
        const n = fs.readSync(fd, buf, 0, length, offset);
        return { data: buf.buffer.slice(buf.byteOffset, buf.byteOffset + n) };
      }
    };
    this.p = new PMTiles(source);
    this.info = { name: path.basename(file).replace(/\.[^.]+$/, '') };
  }

  async init() {
    const h = await this.p.getHeader();
    // tipi: 1 = MVT (vettoriale), 2 png, 3 jpeg, 4 webp, 5 avif
    if (h.tileType === 1) throw unsupported('PMTiles vettoriale: il Planner mostra solo mappe a immagini');
    this.mime = { 2: 'image/png', 3: 'image/jpeg', 4: 'image/webp', 5: 'image/avif' }[h.tileType];
    Object.assign(this.info, {
      minzoom: h.minZoom, maxzoom: h.maxZoom,
      bounds: [[h.minLat, h.minLon], [h.maxLat, h.maxLon]]
    });
    try {
      const meta = await this.p.getMetadata();
      if (meta && meta.name) this.info.name = meta.name;
      if (meta && meta.attribution) this.info.attribution = meta.attribution;
    } catch (e) { /* senza metadati */ }
    return this;
  }

  async tile(z, x, y) {
    const r = await this.p.getZxy(z, x, y);
    return r && r.data ? Buffer.from(r.data) : null;
  }

  close() { try { fs.closeSync(this.fd); } catch (e) { /* già chiuso */ } }
}

/** Cartella con i riquadri z/x/y.(png|jpg|…), eventualmente in una sottocartella */
class FolderTiles {
  constructor(dir) {
    const isNum = (s) => /^\d+$/.test(s);
    let root = dir;
    // cerca la cartella che contiene le cartelle degli zoom (fino a 2 livelli sotto)
    for (let depth = 0; depth < 3; depth++) {
      const subs = fs.readdirSync(root, { withFileTypes: true }).filter((d) => d.isDirectory());
      if (subs.some((d) => isNum(d.name))) break;
      if (subs.length !== 1) throw unsupported('Nella cartella non ci sono riquadri organizzati per zoom (z/x/y)');
      root = path.join(root, subs[0].name);
    }
    const zooms = fs.readdirSync(root).filter(isNum).map(Number).sort((a, b) => a - b);
    if (!zooms.length) throw unsupported('Nella cartella non ci sono riquadri organizzati per zoom (z/x/y)');
    // estensione dei riquadri e coordinate allo zoom più basso
    const z0 = zooms[0];
    const xs = fs.readdirSync(path.join(root, String(z0))).filter(isNum).map(Number);
    let ext = null, ys = [];
    for (const x of xs) {
      for (const f of fs.readdirSync(path.join(root, String(z0), String(x)))) {
        const m = f.match(/^(\d+)\.([a-z0-9]+)(\.tile)?$/i);
        if (m) { ext = ext || m[2] + (m[3] || ''); ys.push(+m[1]); }
      }
    }
    if (!ext) throw unsupported('Non trovo immagini dei riquadri nella cartella');
    this.root = root;
    this.ext = ext;
    this.tms = false;
    this.info = {
      name: path.basename(dir), minzoom: zooms[0], maxzoom: zooms[zooms.length - 1],
      bounds: tileBounds(z0, Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys))
    };
  }

  async tile(z, x, y) {
    const f = path.join(this.root, String(z), String(x), `${y}.${this.ext}`);
    try { return await fs.promises.readFile(f); } catch (e) { return null; }
  }

  close() {}
}

/* ------------------------------------------------- immagini georeferenziate */

async function readGeoTiff(file) {
  const GeoTIFF = await import('geotiff');
  const tiff = await GeoTIFF.fromFile(file);
  const image = await tiff.getImage();
  const keys = image.getGeoKeys() || {};
  const epsg = keys.ProjectedCSTypeGeoKey || keys.GeographicTypeGeoKey;
  let bb;
  try { bb = image.getBoundingBox(); } catch (e) { throw unsupported('Il TIFF non è georeferenziato (manca la posizione)'); }
  let bounds;
  if (epsg === 4326 || epsg === 4258 || (!keys.ProjectedCSTypeGeoKey && Math.abs(bb[0]) <= 180 && Math.abs(bb[3]) <= 90)) {
    bounds = [[bb[1], bb[0]], [bb[3], bb[2]]];
  } else if (epsg === 3857 || epsg === 900913 || epsg === 3785) {
    bounds = [[merc2lat(bb[1]), merc2lon(bb[0])], [merc2lat(bb[3]), merc2lon(bb[2])]];
  } else {
    throw unsupported(`GeoTIFF in proiezione EPSG:${epsg || '?'}: convertilo in WGS84 (EPSG:4326) o Web Mercator (EPSG:3857), ad esempio con QGIS (Raster → Proiezioni → Riproietta)`);
  }
  const w = image.getWidth(), h = image.getHeight();
  const k = Math.min(1, 4096 / Math.max(w, h)); // abbastanza nitido, senza esaurire la memoria
  const width = Math.max(1, Math.round(w * k)), height = Math.max(1, Math.round(h * k));
  const rgb = await image.readRGB({ width, height, interleave: true, enableAlpha: true });
  // readRGB può restituire RGB (3 canali) o RGBA (4 canali)
  const ch = rgb.length / (width * height);
  const rgba = new Uint8ClampedArray(width * height * 4);
  for (let i = 0, j = 0; i < width * height; i++, j += ch) {
    rgba[i * 4] = rgb[j]; rgba[i * 4 + 1] = rgb[j + 1]; rgba[i * 4 + 2] = rgb[j + 2];
    rgba[i * 4 + 3] = ch === 4 ? rgb[j + 3] : 255;
  }
  return [{ kind: 'raw', width, height, rgba: Buffer.from(rgba.buffer), bounds, name: path.basename(file) }];
}

function boundsFromCorners(pts) {
  const lats = pts.map((p) => p[0]), lons = pts.map((p) => p[1]);
  return [[Math.min(...lats), Math.min(...lons)], [Math.max(...lats), Math.max(...lons)]];
}

async function readKmz(file) {
  const JSZip = require('jszip');
  let kmlText, getFile;
  if (/\.kml$/i.test(file)) {
    kmlText = await fs.promises.readFile(file, 'utf8');
    getFile = async (href) => fs.promises.readFile(path.resolve(path.dirname(file), decodeURIComponent(href)));
  } else {
    const zip = await JSZip.loadAsync(await fs.promises.readFile(file));
    const kmlEntry = Object.values(zip.files).find((f) => /\.kml$/i.test(f.name) && !f.dir);
    if (!kmlEntry) throw unsupported('Nel KMZ non c\'è un file KML');
    kmlText = await kmlEntry.async('string');
    const base = path.posix.dirname(kmlEntry.name);
    getFile = async (href) => {
      const name = decodeURIComponent(href).replace(/^\.\//, '');
      const e = zip.file(name) || zip.file(path.posix.join(base, name));
      if (!e) throw new Error('immagine mancante: ' + href);
      return Buffer.from(await e.async('uint8array'));
    };
  }
  const tag = (s, t) => { const m = s.match(new RegExp(`<(?:\\w+:)?${t}[^>]*>([\\s\\S]*?)</(?:\\w+:)?${t}>`, 'i')); return m ? m[1].trim() : null; };
  const overlays = [];
  const re = /<(?:\w+:)?GroundOverlay[\s\S]*?<\/(?:\w+:)?GroundOverlay>/gi;
  let m;
  while ((m = re.exec(kmlText))) {
    const g = m[0];
    const href = tag(tag(g, 'Icon') || '', 'href');
    if (!href || /^https?:/i.test(href)) continue;
    let bounds = null;
    const box = tag(g, 'LatLonBox');
    if (box) {
      const n = +tag(box, 'north'), s = +tag(box, 'south'), e = +tag(box, 'east'), w = +tag(box, 'west');
      if ([n, s, e, w].every(Number.isFinite)) bounds = [[s, w], [n, e]];
    } else {
      const quad = tag(tag(g, 'LatLonQuad') || '', 'coordinates');
      if (quad) bounds = boundsFromCorners(quad.split(/\s+/).filter(Boolean).map((c) => { const [lon, lat] = c.split(',').map(Number); return [lat, lon]; }));
    }
    if (!bounds) continue;
    try {
      const data = await getFile(href);
      overlays.push({ kind: 'image', dataUrl: `data:${sniff(data)};base64,${data.toString('base64')}`, bounds, name: tag(g, 'name') || href });
    } catch (e) { /* immagine mancante: salta */ }
  }
  if (!overlays.length) throw unsupported('Il file non contiene immagini georeferenziate (GroundOverlay)');
  return overlays;
}

/** world file: 6 righe A D B E C F (dimensione pixel e centro del pixel in alto a sinistra) */
async function readWorldFile(img) {
  const base = img.replace(/\.[^.]+$/, '');
  const ext = path.extname(img).slice(1).toLowerCase();
  const cands = [ext[0] + ext[ext.length - 1] + 'w', ext + 'w', 'wld', 'jgw', 'pgw', 'gfw', 'tfw'].map((e) => base + '.' + e);
  let wf = null;
  for (const c of cands) {
    const up = base + path.extname(c).toUpperCase();
    if (!wf && fs.existsSync(c)) wf = c;
    else if (!wf && fs.existsSync(up)) wf = up;
  }
  if (!wf) return null;
  const n = fs.readFileSync(wf, 'utf8').split(/\r?\n/).map((s) => parseFloat(s.trim())).filter(Number.isFinite);
  if (n.length < 6) throw unsupported('World file non valido');
  const [A, D, B, E, C, F] = n;
  if (Math.abs(D) > 1e-9 || Math.abs(B) > 1e-9) throw unsupported('Immagine ruotata nel world file: non supportata');
  const data = await fs.promises.readFile(img);
  const { w, h } = imageSize(data);
  if (!w) throw unsupported('Dimensioni dell\'immagine non leggibili');
  let x0 = C - A / 2, y0 = F - E / 2, x1 = x0 + A * w, y1 = y0 + E * h;
  let bounds;
  if (Math.abs(x0) <= 180 && Math.abs(y0) <= 90) bounds = [[Math.min(y0, y1), x0], [Math.max(y0, y1), x1]];
  else bounds = [[merc2lat(Math.min(y0, y1)), merc2lon(x0)], [merc2lat(Math.max(y0, y1)), merc2lon(x1)]];
  return [{ kind: 'image', dataUrl: `data:${sniff(data)};base64,${data.toString('base64')}`, bounds, name: path.basename(img) }];
}

/** Calibrazione OziExplorer (.map): angoli MMPLL e nome dell'immagine */
async function readOzi(file) {
  const lines = fs.readFileSync(file, 'latin1').split(/\r?\n/);
  if (!/OziExplorer Map Data File/i.test(lines[0] || '')) return null;
  let imgName = (lines[2] || '').trim();
  const imgPath = [path.resolve(path.dirname(file), imgName), path.join(path.dirname(file), path.basename(imgName.replace(/\\/g, '/')))].find((p) => fs.existsSync(p));
  if (!imgPath) throw unsupported('Immagine della mappa OziExplorer non trovata: mettila nella stessa cartella del file .map');
  const corners = lines.filter((l) => /^MMPLL/i.test(l)).map((l) => { const p = l.split(','); return [parseFloat(p[3]), parseFloat(p[2])]; })
    .filter((p) => p.every(Number.isFinite));
  if (corners.length < 2) throw unsupported('Nel file OziExplorer mancano gli angoli (MMPLL)');
  const datum = (lines[4] || '').split(',')[0].trim();
  if (datum && !/WGS ?84/i.test(datum)) throw unsupported(`Calibrazione OziExplorer nel datum "${datum}": serve WGS 84`);
  if (/\.(tif|tiff)$/i.test(imgPath)) {
    throw unsupported('L\'immagine OziExplorer è un TIFF: convertila in JPG o PNG');
  }
  const data = await fs.promises.readFile(imgPath);
  return [{ kind: 'image', dataUrl: `data:${sniff(data)};base64,${data.toString('base64')}`, bounds: boundsFromCorners(corners), name: path.basename(imgPath) }];
}

/** Dimensioni di PNG, JPEG, GIF, WebP leggendo l'intestazione */
function imageSize(b) {
  if (b[0] === 0x89 && b[1] === 0x50) return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
  if (b.toString('ascii', 0, 3) === 'GIF') return { w: b.readUInt16LE(6), h: b.readUInt16LE(8) };
  if (b.toString('ascii', 0, 4) === 'RIFF' && b.toString('ascii', 8, 12) === 'WEBP') {
    const t = b.toString('ascii', 12, 16);
    if (t === 'VP8X') return { w: 1 + b.readUIntLE(24, 3), h: 1 + b.readUIntLE(27, 3) };
    if (t === 'VP8 ') return { w: b.readUInt16LE(26) & 0x3fff, h: b.readUInt16LE(28) & 0x3fff };
    if (t === 'VP8L') { const v = b.readUInt32LE(21); return { w: 1 + (v & 0x3fff), h: 1 + ((v >> 14) & 0x3fff) }; }
  }
  if (b[0] === 0xff && b[1] === 0xd8) {
    let i = 2;
    while (i < b.length) {
      if (b[i] !== 0xff) { i++; continue; }
      const m = b[i + 1];
      const len = b.readUInt16BE(i + 2);
      if (m >= 0xc0 && m <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(m)) return { h: b.readUInt16BE(i + 5), w: b.readUInt16BE(i + 7) };
      i += 2 + len;
    }
  }
  return { w: 0, h: 0 };
}

/* ---------------------------------------------------------- gestore */

class MapFiles {
  constructor() {
    this.tiles = new Map(); // id -> sorgente a riquadri
  }

  /**
   * Apre un file o una cartella di mappa.
   * Restituisce { type: 'tiles', id, name, minzoom, maxzoom, bounds } oppure
   * { type: 'images', name, overlays: [{ kind, dataUrl | rgba, width, height, bounds }] }.
   */
  async open(p) {
    const st = fs.statSync(p);
    const ext = path.extname(p).slice(1).toLowerCase();
    if (st.isDirectory()) return this._addTiles(p, new FolderTiles(p));

    const head = Buffer.alloc(64);
    const fd = fs.openSync(p, 'r');
    fs.readSync(fd, head, 0, 64, 0);
    fs.closeSync(fd);

    if (head.toString('ascii', 0, 15) === 'SQLite format 3') return this._addTiles(p, new SqliteTiles(p));
    if (head.toString('ascii', 0, 7) === 'PMTiles') return this._addTiles(p, await new PmTiles(p).init());
    if (head.toString('ascii', 0, 20).startsWith('mapsforge binary OSM')) {
      throw unsupported('Le mappe vettoriali Mapsforge (.map di OpenAndroMaps) si usano nell\'app sul telefono; nel Planner usa mappe a immagini (MBTiles, SQLiteDB, GeoTIFF…)');
    }
    if (ext === 'map') {
      const r = await readOzi(p);
      if (r) return { type: 'images', name: path.basename(p), overlays: r };
    }
    if (ext === 'tif' || ext === 'tiff') return { type: 'images', name: path.basename(p), overlays: await readGeoTiff(p) };
    if (ext === 'kmz' || ext === 'kml') return { type: 'images', name: path.basename(p), overlays: await readKmz(p) };
    if (IMG_EXT.includes(ext)) {
      const r = await readWorldFile(p);
      if (r) return { type: 'images', name: path.basename(p), overlays: r };
      throw unsupported('Immagine senza posizione: serve accanto il suo world file (.jgw, .pgw, .wld…) o una calibrazione OziExplorer (.map)');
    }
    if (['ecw', 'sid', 'jp2', 'img', 'gemf', 'otrk2', 'xml'].includes(ext)) {
      throw unsupported(`Formato .${ext} non supportato: convertilo in MBTiles o GeoTIFF (es. con QGIS o Mobile Atlas Creator)`);
    }
    throw unsupported('Formato di mappa non riconosciuto');
  }

  _addTiles(p, src) {
    // stesso file = stesso indirizzo, così i riquadri restano in cache tra un avvio e l'altro
    const id = crypto.createHash('sha1').update(path.resolve(p)).digest('hex').slice(0, 12);
    const old = this.tiles.get(id);
    if (old) old.close();
    this.tiles.set(id, src);
    const i = src.info;
    return { type: 'tiles', id, name: i.name, minzoom: i.minzoom ?? 0, maxzoom: i.maxzoom ?? 19, bounds: i.bounds || null, attribution: i.attribution || '' };
  }

  close(id) {
    const s = this.tiles.get(id);
    if (s) { s.close(); this.tiles.delete(id); }
  }

  /** Risposta per sjtile://<id>/<z>/<x>/<y> */
  async handle(url) {
    const m = url.match(/^sjtile:\/\/([0-9a-f]+)\/(\d+)\/(\d+)\/(\d+)/i);
    if (!m) return new Response('', { status: 400 });
    const src = this.tiles.get(m[1].toLowerCase());
    if (!src) return new Response('', { status: 404 });
    try {
      const data = await src.tile(+m[2], +m[3], +m[4]);
      if (!data) return new Response('', { status: 404 });
      return new Response(data, { headers: { 'Content-Type': src.mime || sniff(data), 'Cache-Control': 'max-age=3600' } });
    } catch (e) {
      return new Response('', { status: 500 });
    }
  }
}

module.exports = { MapFiles, imageSize, tileBounds };
