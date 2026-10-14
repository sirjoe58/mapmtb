/* SirJoe MTB Planner - calcolo percorsi con il server pubblico BRouter (dati OpenStreetMap)
 * Stessi tipi di percorso dell'app Android. Una richiesta per tratto (tre per "il più lungo").
 */
'use strict';

const ROUTE_TYPES = {
  offroad: { label: 'Fuoristrada / MTB', profiles: ['mtb-zossebart', 'hiking-mountain'] },
  trekking: { label: 'Equilibrato', profiles: ['trekking'] },
  fast: { label: 'Il più veloce', profiles: ['fastbike', 'trekking'] },
  shortest: { label: 'Il più corto', profiles: ['shortest'] },
  long: { label: 'Il più lungo', profiles: ['trekking'], longest: true },
  straight: { label: 'Linea retta', profiles: [] }
};

const Net = {
  async text(url) {
    if (window.sirjoe && window.sirjoe.fetchText) {
      const r = await window.sirjoe.fetchText(url);
      if (!r.ok) throw new Error(r.text ? r.text.slice(0, 160) : 'errore ' + r.status);
      return r.text;
    }
    const res = await fetch(url);
    const t = await res.text();
    if (!res.ok) throw new Error(t.slice(0, 160) || 'errore ' + res.status);
    return t;
  }
};

const Routing = {
  cache: new Map(),

  async request(a, b, profile, alt) {
    const lonlats = `${a.lon.toFixed(6)},${a.lat.toFixed(6)}|${b.lon.toFixed(6)},${b.lat.toFixed(6)}`;
    const url = `https://brouter.de/brouter?lonlats=${encodeURIComponent(lonlats)}&profile=${profile}&alternativeidx=${alt}&format=gpx`;
    if (Routing.cache.has(url)) return Routing.cache.get(url);
    const gpx = await Net.text(url);
    const pts = Formats.parseXml(gpx, 'tratto').points.map((p) => ({ lat: p.lat, lon: p.lon, ele: p.ele }));
    if (pts.length < 2) throw new Error('percorso vuoto');
    Routing.cache.set(url, pts);
    return pts;
  },

  /** Tratto da a a b con il tipo scelto */
  async leg(a, b, typeId) {
    const type = ROUTE_TYPES[typeId] || ROUTE_TYPES.trekking;
    if (!type.profiles.length) return [{ lat: a.lat, lon: a.lon, ele: null }, { lat: b.lat, lon: b.lon, ele: null }];
    let lastErr = null;
    for (const p of type.profiles) {
      try {
        if (!type.longest) return await Routing.request(a, b, p, 0);
        let best = null, bestLen = -1;
        for (let alt = 0; alt <= 2; alt++) {
          try {
            const r = await Routing.request(a, b, p, alt);
            const len = Geo.cumulative(r).pop();
            if (len > bestLen) { best = r; bestLen = len; }
          } catch (e) { if (alt === 0) throw e; }
        }
        if (best) return best;
      } catch (e) { lastErr = e; }
    }
    throw new Error('BRouter: ' + (lastErr ? lastErr.message : 'nessun percorso'));
  },

  /** Ricerca di un luogo per nome (Nominatim, OpenStreetMap) */
  async search(q) {
    const url = 'https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&accept-language=it&q=' + encodeURIComponent(q);
    return JSON.parse(await Net.text(url));
  }
};
