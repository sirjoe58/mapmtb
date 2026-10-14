/* SirJoe MTB Planner - geometria e formati delle tracce
 * Lettura: GPX (tracce, percorsi e segnaposto), KML, GeoJSON, TCX, FIT, CSV
 * Scrittura: GPX (percorso + segnaposto), FIT (course), KML, TCX, GeoJSON, CSV
 * I tipi di segnaposto sono gli stessi dell'app Android (campo <type> del GPX).
 */
'use strict';

const PLACE_TYPES = [
  { id: 'generic', label: 'Generico', color: '#1565C0' },
  { id: 'water', label: 'Fontana / acqua', color: '#00ACC1' },
  { id: 'food', label: 'Rifugio / ristoro', color: '#6D4C41' },
  { id: 'view', label: 'Panorama', color: '#2E7D32' },
  { id: 'bike', label: 'Officina / bici', color: '#FF6D00' },
  { id: 'parking', label: 'Parcheggio / partenza', color: '#546E7A' },
  { id: 'danger', label: 'Pericolo / attenzione', color: '#C62828' }
];
const placeType = (id) => PLACE_TYPES.find((t) => t.id === id) || PLACE_TYPES[0];

const Geo = {
  R: 6371000,
  dist(a, b) {
    const r = Math.PI / 180;
    const dLat = (b.lat - a.lat) * r;
    const dLon = (b.lon - a.lon) * r;
    const s = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dLon / 2) ** 2;
    return 2 * Geo.R * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
  },
  cumulative(pts) {
    const out = [0];
    for (let i = 1; i < pts.length; i++) out.push(out[i - 1] + Geo.dist(pts[i - 1], pts[i]));
    return out;
  },
  /** distanza, salita, discesa (soglia 3 m per filtrare il rumore) */
  summary(pts) {
    const cum = Geo.cumulative(pts);
    let up = 0, down = 0, ref = null, hasEle = false;
    for (const p of pts) {
      if (p.ele == null) continue;
      hasEle = true;
      if (ref == null) { ref = p.ele; continue; }
      if (p.ele - ref >= 3) { up += p.ele - ref; ref = p.ele; } else if (ref - p.ele >= 3) { down += ref - p.ele; ref = p.ele; }
    }
    return { dist: cum[cum.length - 1] || 0, up, down, hasEle, cum };
  },

  /**
   * Salite del percorso: tratti di almeno 300 m con pendenza media >= 5% e almeno 25 m di dislivello.
   * Stesso metodo dell'app Android. Restituisce [{ start, end, length, gain, avg, max }] in metri e frazioni.
   */
  climbs(pts) {
    const p = pts.filter((q) => q.ele != null);
    if (p.length < 2) return [];
    const cum = Geo.cumulative(p);
    const total = cum[cum.length - 1];
    const eleAt = (d) => {
      if (d <= 0) return p[0].ele;
      if (d >= total) return p[p.length - 1].ele;
      let lo = 0, hi = cum.length - 1;
      while (hi - lo > 1) { const m = (lo + hi) >> 1; if (cum[m] < d) lo = m; else hi = m; }
      const f = (d - cum[lo]) / ((cum[hi] - cum[lo]) || 1);
      return p[lo].ele + f * (p[hi].ele - p[lo].ele);
    };
    const out = [];
    const finish = (c) => {
      c.length = c.end - c.start;
      c.gain = eleAt(c.end) - eleAt(c.start);
      c.avg = c.gain / c.length;
      if (c.length >= 300 && c.gain >= 25 && c.avg >= 0.05) out.push(c);
    };
    let cur = null, low = 0;
    for (let s = 0; s + 100 <= total; s += 50) {
      const g = (eleAt(s + 100) - eleAt(s)) / 100;
      if (g >= 0.05) {
        if (!cur) cur = { start: s, max: g };
        cur.end = s + 100; cur.max = Math.max(cur.max, g); low = 0;
      } else if (cur) {
        low++;
        if (g < 0.02 || low >= 3) { finish(cur); cur = null; low = 0; }
      }
    }
    if (cur) finish(cur);
    return out;
  },

  /** pendenza (frazione) per ogni punto, su una finestra di circa 60 m */
  grades(pts) {
    const cum = Geo.cumulative(pts);
    return pts.map((q, i) => {
      if (q.ele == null) return 0;
      let a = i, b = i;
      while (a > 0 && cum[i] - cum[a] < 30) a--;
      while (b < pts.length - 1 && cum[b] - cum[i] < 30) b++;
      const d = cum[b] - cum[a];
      if (d < 10 || pts[a].ele == null || pts[b].ele == null) return 0;
      return (pts[b].ele - pts[a].ele) / d;
    });
  }
};

/** Colori per pendenza: discesa blu, piano verde, salita dal giallo al rosso scuro */
const SLOPE_CLASSES = [
  { max: -0.08, color: '#1565C0', label: '< -8%' },
  { max: -0.03, color: '#42A5F5', label: '-8/-3%' },
  { max: 0.03, color: '#43A047', label: '±3%' },
  { max: 0.06, color: '#FDD835', label: '3-6%' },
  { max: 0.10, color: '#FB8C00', label: '6-10%' },
  { max: 0.15, color: '#E53935', label: '10-15%' },
  { max: Infinity, color: '#6A1B9A', label: '> 15%' }
];
const slopeColor = (g) => SLOPE_CLASSES.find((c) => g < c.max).color;

const Fmt = {
  km: (m) => (m / 1000).toLocaleString('it-IT', { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
  m: (v) => Math.round(v).toLocaleString('it-IT'),
  dur(h) {
    const mins = Math.round(h * 60);
    return Math.floor(mins / 60) + ':' + String(mins % 60).padStart(2, '0');
  },
  /** stima per MTB: 14 km/h in piano + 1 ora ogni 600 m di salita */
  estimate: (distM, upM) => distM / 1000 / 14 + upM / 600
};

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const n7 = (v) => v.toFixed(7);

/* ------------------------------------------------------------- lettura */

const Formats = {
  /** Restituisce { name, points:[{lat,lon,ele,time}], waypoints:[{name,note,lat,lon,ele,type}] } */
  parse(buffer, fileName) {
    const lower = fileName.toLowerCase();
    const base = fileName.replace(/\.[^.]+$/, '') || 'Traccia';
    const bytes = new Uint8Array(buffer);
    if (lower.endsWith('.fit') || Fit.isFit(bytes)) return Fit.decode(bytes, base);
    let text = new TextDecoder('utf-8').decode(bytes).replace(/^﻿/, '').trim();
    if (lower.endsWith('.geojson') || lower.endsWith('.json') || text.startsWith('{')) return Formats.parseGeoJson(text, base);
    if (lower.endsWith('.csv')) return Formats.parseCsv(text, base);
    return Formats.parseXml(text, base);
  },

  parseXml(text, base) {
    const doc = new DOMParser().parseFromString(text, 'application/xml');
    if (doc.querySelector('parsererror')) throw new Error('File XML non valido');
    const all = (tag) => Array.from(doc.getElementsByTagNameNS('*', tag));
    const childText = (el, tag) => {
      const c = Array.from(el.children).find((x) => x.localName === tag);
      return c ? c.textContent.trim() : null;
    };
    const num = (v) => (v == null || v === '' ? null : Number(v));
    const time = (v) => (v ? Date.parse(v) || null : null);
    let name = null;
    const points = [];
    const waypoints = [];

    // GPX
    for (const el of all('trkpt').concat(all('trkpt').length ? [] : all('rtept'))) {
      points.push({ lat: num(el.getAttribute('lat')), lon: num(el.getAttribute('lon')), ele: num(childText(el, 'ele')), time: time(childText(el, 'time')) });
    }
    for (const el of all('wpt')) {
      waypoints.push({
        name: childText(el, 'name') || 'Segnaposto',
        note: childText(el, 'desc') || childText(el, 'cmt') || '',
        lat: num(el.getAttribute('lat')), lon: num(el.getAttribute('lon')),
        ele: num(childText(el, 'ele')),
        type: placeType(childText(el, 'type')).id
      });
    }
    const trk = all('trk')[0] || all('rte')[0] || all('metadata')[0];
    if (trk) name = childText(trk, 'name');

    // TCX
    if (!points.length) {
      for (const el of all('Trackpoint')) {
        const pos = Array.from(el.children).find((x) => x.localName === 'Position');
        if (!pos) continue;
        points.push({
          lat: num(childText(pos, 'LatitudeDegrees')), lon: num(childText(pos, 'LongitudeDegrees')),
          ele: num(childText(el, 'AltitudeMeters')), time: time(childText(el, 'Time'))
        });
      }
      const c = all('Course')[0] || all('Activity')[0];
      if (c && !name) name = childText(c, 'Name');
    }

    // KML (LineString, gx:Track, Placemark con Point)
    if (!points.length) {
      const coords = all('gx:coord').length ? all('gx:coord') : all('coord');
      for (const c of coords) {
        const [lo, la, e] = c.textContent.trim().split(/\s+/).map(Number);
        points.push({ lat: la, lon: lo, ele: isNaN(e) ? null : e, time: null });
      }
      for (const ls of all('LineString')) {
        const c = Array.from(ls.children).find((x) => x.localName === 'coordinates');
        if (!c) continue;
        for (const t of c.textContent.trim().split(/\s+/)) {
          const [lo, la, e] = t.split(',').map(Number);
          if (!isNaN(la) && !isNaN(lo)) points.push({ lat: la, lon: lo, ele: isNaN(e) ? null : e, time: null });
        }
      }
      for (const pm of all('Placemark')) {
        const pt = pm.getElementsByTagNameNS('*', 'Point')[0];
        if (!pt) continue;
        const c = pt.getElementsByTagNameNS('*', 'coordinates')[0];
        if (!c) continue;
        const [lo, la, e] = c.textContent.trim().split(',').map(Number);
        waypoints.push({ name: childText(pm, 'name') || 'Segnaposto', note: childText(pm, 'description') || '', lat: la, lon: lo, ele: isNaN(e) ? null : e, type: 'generic' });
      }
      const d = all('Document')[0];
      if (d && !name) name = childText(d, 'name');
    }

    const good = points.filter((p) => isFinite(p.lat) && isFinite(p.lon));
    if (!good.length && !waypoints.length) throw new Error('Nessun punto trovato nel file');
    return { name: name || base, points: good, waypoints };
  },

  parseGeoJson(text, base) {
    const root = JSON.parse(text);
    const points = [];
    const waypoints = [];
    let name = root.properties && root.properties.name;
    const addLine = (coords, times) => coords.forEach((c, i) => points.push({ lat: c[1], lon: c[0], ele: c.length > 2 ? c[2] : null, time: times && times[i] ? Date.parse(times[i]) : null }));
    const geom = (g, props) => {
      if (!g) return;
      props = props || {};
      if (g.type === 'LineString') addLine(g.coordinates, props.coordTimes);
      else if (g.type === 'MultiLineString') g.coordinates.forEach((l, i) => addLine(l, props.coordTimes && props.coordTimes[i]));
      else if (g.type === 'Point') waypoints.push({ name: props.name || 'Segnaposto', note: props.note || props.description || '', lat: g.coordinates[1], lon: g.coordinates[0], ele: g.coordinates[2] ?? null, type: placeType(props.type).id });
      if (!name && props.name && g.type !== 'Point') name = props.name;
    };
    if (root.type === 'FeatureCollection') root.features.forEach((f) => geom(f.geometry, f.properties));
    else if (root.type === 'Feature') geom(root.geometry, root.properties);
    else geom(root, {});
    if (!points.length && !waypoints.length) throw new Error('Nessuna linea o punto nel GeoJSON');
    return { name: name || base, points, waypoints };
  },

  parseCsv(text, base) {
    const lines = text.split(/\r?\n/).filter((l) => l.trim());
    const sep = (lines[0].match(/;/g) || []).length > (lines[0].match(/,/g) || []).length ? ';' : ',';
    const head = lines[0].toLowerCase().split(sep).map((s) => s.trim());
    const hasHead = head.some((h) => h.includes('lat'));
    const idx = (k, d) => { const i = head.findIndex((h) => k.some((x) => h.includes(x))); return hasHead ? i : d; };
    const iLat = idx(['lat'], 0), iLon = idx(['lon', 'lng'], 1), iEle = idx(['ele', 'alt'], 2), iTime = idx(['time', 'ora'], 3);
    const points = [];
    for (const l of hasHead ? lines.slice(1) : lines) {
      const c = l.split(sep).map((s) => s.trim());
      const lat = Number(c[iLat]), lon = Number(c[iLon]);
      if (!isFinite(lat) || !isFinite(lon) || c[iLat] === '') continue;
      points.push({ lat, lon, ele: iEle >= 0 && c[iEle] !== '' && c[iEle] != null ? Number(c[iEle]) : null, time: iTime >= 0 && c[iTime] ? Date.parse(c[iTime]) || null : null });
    }
    if (!points.length) throw new Error('Nessun punto valido nel CSV');
    return { name: base, points, waypoints: [] };
  },

  /* ----------------------------------------------------------- scrittura */

  /** formato -> { data: string|Uint8Array, mime, ext } */
  write(format, name, points, waypoints) {
    switch (format) {
      case 'gpx': return { data: Formats.gpx(name, points, waypoints), mime: 'application/gpx+xml', ext: 'gpx' };
      case 'fit': return { data: Fit.encodeCourse(name, points), mime: 'application/octet-stream', ext: 'fit' };
      case 'kml': return { data: Formats.kml(name, points, waypoints), mime: 'application/vnd.google-earth.kml+xml', ext: 'kml' };
      case 'tcx': return { data: Formats.tcx(name, points), mime: 'application/vnd.garmin.tcx+xml', ext: 'tcx' };
      case 'geojson': return { data: Formats.geojson(name, points, waypoints), mime: 'application/geo+json', ext: 'geojson' };
      case 'csv': return { data: Formats.csv(points), mime: 'text/csv', ext: 'csv' };
    }
    throw new Error('Formato sconosciuto');
  },

  gpx(name, points, waypoints) {
    let s = '<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="SirJoe MTB Planner" xmlns="http://www.topografix.com/GPX/1/1">\n';
    s += `  <metadata><name>${esc(name)}</name></metadata>\n`;
    for (const w of waypoints || []) {
      s += `  <wpt lat="${n7(w.lat)}" lon="${n7(w.lon)}">`;
      if (w.ele != null) s += `<ele>${w.ele.toFixed(1)}</ele>`;
      s += `<name>${esc(w.name)}</name>`;
      if (w.note) s += `<desc>${esc(w.note)}</desc>`;
      s += `<type>${w.type}</type></wpt>\n`;
    }
    if (points.length) {
      s += `  <trk><name>${esc(name)}</name><type>mountain_biking</type><trkseg>\n`;
      for (const p of points) {
        s += `    <trkpt lat="${n7(p.lat)}" lon="${n7(p.lon)}">`;
        if (p.ele != null) s += `<ele>${p.ele.toFixed(1)}</ele>`;
        if (p.time) s += `<time>${new Date(p.time).toISOString()}</time>`;
        s += '</trkpt>\n';
      }
      s += '  </trkseg></trk>\n';
    }
    return s + '</gpx>\n';
  },

  kml(name, points, waypoints) {
    let s = '<?xml version="1.0" encoding="UTF-8"?>\n<kml xmlns="http://www.opengis.net/kml/2.2">\n<Document>\n';
    s += `  <name>${esc(name)}</name>\n  <Style id="linea"><LineStyle><color>ff006dff</color><width>4</width></LineStyle></Style>\n`;
    for (const w of waypoints || []) {
      s += `  <Placemark><name>${esc(w.name)}</name>${w.note ? `<description>${esc(w.note)}</description>` : ''}<Point><coordinates>${n7(w.lon)},${n7(w.lat)}</coordinates></Point></Placemark>\n`;
    }
    if (points.length) {
      s += `  <Placemark><name>${esc(name)}</name><styleUrl>#linea</styleUrl><LineString><tessellate>1</tessellate><coordinates>\n`;
      for (const p of points) s += `    ${n7(p.lon)},${n7(p.lat)}${p.ele != null ? ',' + p.ele.toFixed(1) : ''}\n`;
      s += '  </coordinates></LineString></Placemark>\n';
    }
    return s + '</Document>\n</kml>\n';
  },

  tcx(name, points) {
    const cum = Geo.cumulative(points);
    const t0 = Date.now();
    let s = '<?xml version="1.0" encoding="UTF-8"?>\n<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">\n';
    s += `  <Courses><Course><Name>${esc(name.slice(0, 15))}</Name><Track>\n`;
    points.forEach((p, i) => {
      const t = p.time || t0 + Math.round(cum[i] / 4.44) * 1000;
      s += `    <Trackpoint><Time>${new Date(t).toISOString()}</Time><Position><LatitudeDegrees>${n7(p.lat)}</LatitudeDegrees><LongitudeDegrees>${n7(p.lon)}</LongitudeDegrees></Position>`;
      if (p.ele != null) s += `<AltitudeMeters>${p.ele.toFixed(1)}</AltitudeMeters>`;
      s += `<DistanceMeters>${cum[i].toFixed(1)}</DistanceMeters></Trackpoint>\n`;
    });
    return s + '  </Track></Course></Courses>\n</TrainingCenterDatabase>\n';
  },

  geojson(name, points, waypoints) {
    const features = [];
    if (points.length) {
      features.push({ type: 'Feature', properties: { name }, geometry: { type: 'LineString', coordinates: points.map((p) => (p.ele != null ? [p.lon, p.lat, p.ele] : [p.lon, p.lat])) } });
    }
    for (const w of waypoints || []) {
      features.push({ type: 'Feature', properties: { name: w.name, note: w.note, type: w.type }, geometry: { type: 'Point', coordinates: [w.lon, w.lat] } });
    }
    return JSON.stringify({ type: 'FeatureCollection', features }, null, 2);
  },

  csv(points) {
    return 'lat,lon,ele,time\n' + points.map((p) => `${n7(p.lat)},${n7(p.lon)},${p.ele != null ? p.ele.toFixed(1) : ''},${p.time ? new Date(p.time).toISOString() : ''}`).join('\n') + '\n';
  }
};

/* ---------------------------------------------------------------- FIT */

const Fit = {
  EPOCH: 631065600,
  SEMI: 2147483648 / 180,
  CRC: [0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401, 0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400],

  isFit: (b) => b.length > 12 && b[8] === 0x2e && b[9] === 0x46 && b[10] === 0x49 && b[11] === 0x54,

  crc(b, from = 0, to = b.length) {
    let crc = 0;
    for (let i = from; i < to; i++) {
      const byte = b[i];
      let tmp = Fit.CRC[crc & 0xf]; crc = (crc >> 4) & 0x0fff; crc = crc ^ tmp ^ Fit.CRC[byte & 0xf];
      tmp = Fit.CRC[crc & 0xf]; crc = (crc >> 4) & 0x0fff; crc = crc ^ tmp ^ Fit.CRC[(byte >> 4) & 0xf];
    }
    return crc;
  },

  /** File FIT "course" da seguire sui ciclocomputer */
  encodeCourse(name, pts) {
    if (pts.length < 2) throw new Error('Percorso troppo corto');
    const cum = Geo.cumulative(pts);
    const t0 = pts[0].time || Date.now();
    const times = pts.map((p, i) => (pts.every((q) => q.time) ? p.time : t0 + Math.round((cum[i] / 4.44) * 1000)));
    for (let i = 1; i < times.length; i++) if (times[i] <= times[i - 1]) times[i] = times[i - 1] + 1000;
    const out = [];
    const u8 = (v) => out.push(v & 0xff);
    const u16 = (v) => { u8(v); u8(v >> 8); };
    const u32 = (v) => { v = v >>> 0; u8(v); u8(v >>> 8); u8(v >>> 16); u8(v >>> 24); };
    const ft = (ms) => Math.floor(ms / 1000) - Fit.EPOCH;
    const semi = (d) => Math.round(d * Fit.SEMI) | 0;
    const def = (local, global, fields) => {
      u8(0x40 | local); u8(0); u8(0); u16(global); u8(fields.length);
      for (const [n, s, t] of fields) { u8(n); u8(s); u8(t); }
    };
    // file_id
    def(0, 0, [[0, 1, 0x00], [1, 2, 0x84], [2, 2, 0x84], [4, 4, 0x86]]);
    u8(0); u8(6); u16(255); u16(0); u32(ft(t0));
    // course
    def(1, 31, [[4, 1, 0x00], [5, 16, 0x07]]);
    u8(1); u8(2);
    const nameBytes = new TextEncoder().encode(name.slice(0, 15)).slice(0, 15);
    for (let i = 0; i < 16; i++) u8(i < nameBytes.length ? nameBytes[i] : 0);
    // lap
    const el = times[times.length - 1] - times[0];
    def(2, 19, [[253, 4, 0x86], [2, 4, 0x86], [3, 4, 0x85], [4, 4, 0x85], [5, 4, 0x85], [6, 4, 0x85], [7, 4, 0x86], [8, 4, 0x86], [9, 4, 0x86]]);
    u8(2); u32(ft(times[times.length - 1])); u32(ft(times[0]));
    u32(semi(pts[0].lat)); u32(semi(pts[0].lon)); u32(semi(pts[pts.length - 1].lat)); u32(semi(pts[pts.length - 1].lon));
    u32(el); u32(el); u32(Math.round(cum[cum.length - 1] * 100));
    // evento partenza
    def(3, 21, [[253, 4, 0x86], [0, 1, 0x00], [1, 1, 0x00]]);
    u8(3); u32(ft(times[0])); u8(0); u8(0);
    // punti
    def(4, 20, [[253, 4, 0x86], [0, 4, 0x85], [1, 4, 0x85], [2, 2, 0x84], [5, 4, 0x86]]);
    pts.forEach((p, i) => {
      u8(4); u32(ft(times[i])); u32(semi(p.lat)); u32(semi(p.lon));
      u16(p.ele == null ? 0xffff : Math.max(0, Math.min(0xfffe, Math.floor((p.ele + 500) * 5))));
      u32(Math.round(cum[i] * 100));
    });
    // evento fine
    u8(3); u32(ft(times[times.length - 1])); u8(0); u8(9);

    const data = Uint8Array.from(out);
    const header = new Uint8Array(14);
    header[0] = 14; header[1] = 0x10; header[2] = 2132 & 0xff; header[3] = 2132 >> 8;
    for (let k = 0; k < 4; k++) header[4 + k] = (data.length >>> (8 * k)) & 0xff;
    header.set([0x2e, 0x46, 0x49, 0x54], 8);
    const hc = Fit.crc(header, 0, 12);
    header[12] = hc & 0xff; header[13] = hc >> 8;
    const all = new Uint8Array(14 + data.length + 2);
    all.set(header, 0); all.set(data, 14);
    const fc = Fit.crc(all, 0, 14 + data.length);
    all[all.length - 2] = fc & 0xff; all[all.length - 1] = fc >> 8;
    return all;
  },

  decode(b, base) {
    if (!Fit.isFit(b)) throw new Error('Non è un file FIT valido');
    const rd = (pos, size, big) => {
      let v = 0;
      for (let k = 0; k < size; k++) v = big ? v * 256 + b[pos + k] : v + b[pos + k] * 2 ** (8 * k);
      return v;
    };
    const hs = b[0];
    const end = Math.min(hs + rd(4, 4, false), b.length);
    const defs = [];
    const points = [];
    let name = null, lastTs = 0, pos = hs;
    while (pos < end) {
      const h = b[pos++];
      let local, forced = null;
      if (h & 0x80) {
        local = (h >> 5) & 3;
        const off = h & 0x1f;
        let ts = (lastTs & ~0x1f) + off;
        if (off < (lastTs & 0x1f)) ts += 0x20;
        forced = ts; lastTs = ts;
      } else if (h & 0x40) {
        const dev = h & 0x20;
        const l = h & 0x0f;
        pos++;
        const big = b[pos++] === 1;
        const global = rd(pos, 2, big); pos += 2;
        const nf = b[pos++];
        const fields = [];
        for (let i = 0; i < nf; i++) { fields.push({ num: b[pos], size: b[pos + 1], type: b[pos + 2] }); pos += 3; }
        let devSize = 0;
        if (dev) { const nd = b[pos++]; for (let i = 0; i < nd; i++) { devSize += b[pos + 1]; pos += 3; } }
        defs[l] = { global, big, fields, devSize };
        continue;
      } else local = h & 0x0f;
      const d = defs[local];
      if (!d) throw new Error('File FIT danneggiato');
      let lat = null, lon = null, ele = null, ts = forced;
      for (const f of d.fields) {
        if ([1, 2, 4].includes(f.size)) {
          const raw = rd(pos, f.size, d.big);
          if (f.num === 253 && f.size === 4 && raw !== 0xffffffff) { ts = raw; lastTs = raw; }
          else if (d.global === 20 && f.num === 0 && raw !== 0x7fffffff) lat = raw | 0;
          else if (d.global === 20 && f.num === 1 && raw !== 0x7fffffff) lon = raw | 0;
          else if (d.global === 20 && f.num === 2 && f.size === 2 && raw !== 0xffff && ele == null) ele = raw / 5 - 500;
          else if (d.global === 20 && f.num === 78 && f.size === 4 && raw !== 0xffffffff) ele = raw / 5 - 500;
        } else if (d.global === 31 && f.num === 5) {
          const s = new TextDecoder().decode(b.slice(pos, pos + f.size)).split('\0')[0].trim();
          if (s) name = s;
        }
        pos += f.size;
      }
      pos += d.devSize;
      if (d.global === 20 && lat != null && lon != null) {
        points.push({ lat: lat / Fit.SEMI, lon: lon / Fit.SEMI, ele, time: ts != null ? (ts + Fit.EPOCH) * 1000 : null });
      }
    }
    if (!points.length) throw new Error('Nessun punto GPS nel file FIT');
    return { name: name || base, points, waypoints: [] };
  }
};

if (typeof module !== 'undefined') module.exports = { Formats, Fit, Geo, PLACE_TYPES };
