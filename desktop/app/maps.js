/* SirJoe MTB Planner - altre mappe: satellitari, topografiche, fonti online e file di mappa.
 *
 * Fonti online: XYZ / TMS ({z}/{x}/{y}, {-y}), WMS, WMTS (modello con {TileMatrix}…), Bing ({q} quadkey).
 * File (aperti dal processo principale): MBTiles, SQLiteDB (OsmAnd/Locus/RMaps), PMTiles,
 * cartelle di riquadri, GeoTIFF, KMZ/KML GroundOverlay, immagini con world file, OziExplorer.
 */
'use strict';

const ESRI_ATTR = 'Immagini © Esri — Esri, Maxar, Earthstar Geographics e GIS User Community';
/** Mappe pronte: si scelgono dal menu delle mappe */
const MAP_PRESETS = [
  { id: 'esri-sat', name: 'Satellite (Esri World Imagery)', kind: 'xyz', maxZoom: 19,
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', attribution: ESRI_ATTR, satellite: true },
  { id: 'eox-s2', name: 'Satellite Sentinel-2 cloudless (EOX)', kind: 'xyz', maxZoom: 15,
    url: 'https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{z}/{y}/{x}.jpg',
    attribution: 'Sentinel-2 cloudless 2020 © <a href="https://s2maps.eu">EOX IT Services</a> (CC BY-NC-SA 4.0), dati Copernicus', satellite: true },
  { id: 'esri-topo', name: 'Topografica Esri', kind: 'xyz', maxZoom: 19,
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}', attribution: 'Mappa © Esri e fornitori' },
  { id: 'cyclosm', name: 'CyclOSM (bici)', kind: 'xyz', maxZoom: 20, subdomains: 'abc',
    url: 'https://{s}.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png',
    attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>, stile <a href="https://www.cyclosm.org">CyclOSM</a>' }
];
const LABELS_URL = 'https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}';

/** Leaflet con {q}: indirizzi Bing a quadkey */
const QuadkeyLayer = L.TileLayer.extend({
  getTileUrl(c) {
    let q = '';
    for (let i = c.z; i > 0; i--) {
      let d = 0;
      const m = 1 << (i - 1);
      if (c.x & m) d++;
      if (c.y & m) d += 2;
      q += d;
    }
    return L.Util.template(this._url, L.extend({ q, quadkey: q, s: this._getSubdomain(c), z: c.z, x: c.x, y: c.y }, this.options));
  }
});

const Maps = {
  sources: store.get('mapSources', []),     // le mie mappe (online e file)
  layers: {},                                // id -> layer Leaflet
  overlayIds: new Set(store.get('mapOverlays', [])),
  labels: L.tileLayer(LABELS_URL, { maxZoom: 19, attribution: ESRI_ATTR }),

  save() {
    store.set('mapSources', this.sources.map(({ dataOverlays, ...s }) => s));
    store.set('mapOverlays', [...this.overlayIds]);
  },

  find(id) { return MAP_PRESETS.find((p) => p.id === id) || this.sources.find((s) => s.id === id); },

  /** Layer Leaflet per una fonte (creato una volta) */
  layer(src) {
    if (this.layers[src.id]) return this.layers[src.id];
    let l = null;
    const common = { attribution: src.attribution || '', maxZoom: 21, maxNativeZoom: src.maxZoom || 19, minNativeZoom: src.minZoom || 0 };
    if (src.kind === 'xyz' || src.kind === 'tms') {
      let url = src.url.replace(/\{TileMatrix\}/gi, '{z}').replace(/\{TileCol\}/gi, '{x}').replace(/\{TileRow\}/gi, '{y}')
        .replace(/\{zoom\}/gi, '{z}').replace(/\$\{(z|x|y)\}/g, '{$1}');
      const tms = src.kind === 'tms' || url.includes('{-y}');
      url = url.replace('{-y}', '{y}');
      l = L.tileLayer(url, { ...common, tms, subdomains: src.subdomains || 'abc' });
    } else if (src.kind === 'bing') {
      l = new QuadkeyLayer(src.url, { ...common, subdomains: src.subdomains || '0123' });
    } else if (src.kind === 'wms') {
      l = L.tileLayer.wms(src.url, {
        ...common, layers: src.layers, format: src.format || 'image/png', transparent: true,
        version: src.version || '1.3.0', styles: ''
      });
    } else if (src.kind === 'file-tiles' && src.tileId) {
      l = L.tileLayer(`sjtile://${src.tileId}/{z}/{x}/{y}`, {
        ...common, minNativeZoom: src.minZoom || 0, maxNativeZoom: src.maxZoom || 19,
        bounds: src.bounds ? L.latLngBounds(src.bounds).pad(0.02) : undefined, attribution: src.attribution || src.name
      });
    } else if (src.kind === 'file-images' && src.dataOverlays) {
      l = L.layerGroup(src.dataOverlays.map((o) => L.imageOverlay(o.url, o.bounds, { opacity: 1, attribution: src.name })));
    }
    if (l) this.layers[src.id] = l;
    return l;
  },

  /** Mappa di base scelta dal menu (anche una delle mie) */
  baseLayer(id) {
    const src = this.find(id);
    return src ? this.layer(src) : null;
  },

  isSatellite(id) { const s = this.find(id); return !!(s && s.satellite); },

  /** Modello di indirizzo dei riquadri per il video (solo fonti XYZ) */
  template(id) {
    if (id === 'osm') return { url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', maxZoom: 19, attribution: '© OpenStreetMap contributors' };
    const s = this.find(id);
    if (s && s.kind === 'xyz' && !/\{(q|quadkey|-y)\}/.test(s.url)) return { url: s.url, maxZoom: Math.min(s.maxZoom || 19, 18), subdomains: s.subdomains || 'abc', attribution: s.attribution ? s.attribution.replace(/<[^>]+>/g, '') : s.name };
    if (s && s.kind === 'file-tiles' && s.tileId) return { url: `sjtile://${s.tileId}/{z}/{x}/{y}`, maxZoom: s.maxZoom || 19, attribution: s.name };
    return { url: 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png', maxZoom: 17, subdomains: 'abc', attribution: '© OpenStreetMap contributors, OpenTopoMap (CC-BY-SA)' };
  },

  /* -------------------------------------------------- menu delle mappe */
  fillSelect() {
    const sel = $('baseMap');
    const cur = store.get('base', 'topo');
    const opt = (v, t) => `<option value="${v}">${t.replace(/</g, '&lt;')}</option>`;
    let html = opt('topo', 'OpenTopoMap') + opt('osm', 'OpenStreetMap');
    html += '<optgroup label="Satellite e altre">' + MAP_PRESETS.map((p) => opt(p.id, p.name)).join('') + '</optgroup>';
    const mine = this.sources.filter((s) => s.kind !== 'file-images' && (s.kind !== 'file-tiles' || s.tileId));
    if (mine.length) html += '<optgroup label="Le mie mappe">' + mine.map((s) => opt(s.id, s.name)).join('') + '</optgroup>';
    html += opt('__more', '➕ Altre mappe…');
    sel.innerHTML = html;
    sel.value = [...sel.options].some((o) => o.value === cur) ? cur : 'topo';
  },

  applyBase(id) {
    setBase(id);
    this.labelsCheck();
  },

  labelsCheck() {
    const on = this.isSatellite(store.get('base', 'topo')) && store.get('satLabels', true);
    if (on && !map.hasLayer(this.labels)) this.labels.addTo(map);
    if (!on && map.hasLayer(this.labels)) map.removeLayer(this.labels);
    if (on) this.labels.bringToFront && this.labels.bringToFront();
  },

  applyOverlays() {
    for (const s of this.sources) {
      const l = this.layers[s.id];
      const want = this.overlayIds.has(s.id) && store.get('base', 'topo') !== s.id;
      if (!want) { if (l && map.hasLayer(l) && currentBase !== l) map.removeLayer(l); continue; }
      const layer = this.layer(s);
      if (!layer) continue;
      if (!map.hasLayer(layer)) layer.addTo(map);
      const op = s.opacity ?? 0.7;
      if (layer.setOpacity) layer.setOpacity(op);
      else layer.eachLayer((x) => x.setOpacity && x.setOpacity(op));
    }
  },

  /* ------------------------------------------------------- finestra */
  show() {
    this.renderList();
    $('mapsSat').checked = store.get('satLabels', true);
    $('mapsDialog').showModal();
  },

  renderList() {
    const ul = $('mapsList');
    if (!this.sources.length) {
      ul.innerHTML = '<li class="empty">Nessuna mappa aggiunta. Le mappe satellitari pronte sono già nel menu delle mappe.</li>';
      return;
    }
    ul.innerHTML = '';
    const kinds = { xyz: 'online', tms: 'online TMS', wms: 'online WMS', bing: 'online Bing', 'file-tiles': 'file', 'file-images': 'immagine' };
    for (const s of this.sources) {
      const li = document.createElement('li');
      const broken = (s.kind === 'file-tiles' && !s.tileId) || (s.kind === 'file-images' && !s.dataOverlays);
      li.innerHTML = `<span class="grow"><b></b><small></small></span>
        ${s.kind !== 'file-images' ? '<button class="btn" data-a="base" title="Usa come mappa di base">Base</button>' : ''}
        <label class="check" title="Sovrapponi alla mappa di base"><input type="checkbox" data-a="ov"> Sopra</label>
        <input type="range" min="0.1" max="1" step="0.05" data-a="op" title="Trasparenza">
        ${s.bounds ? '<button class="btn" data-a="go" title="Vai alla zona della mappa">⌖</button>' : ''}
        <button class="btn danger" data-a="del" title="Rimuovi dall'elenco">✕</button>`;
      li.querySelector('b').textContent = s.name;
      li.querySelector('small').textContent = kinds[s.kind] + (broken ? ' · file non trovato' : '') + (s.path ? ' · ' + s.path : '');
      const ov = li.querySelector('[data-a=ov]');
      ov.checked = this.overlayIds.has(s.id);
      ov.onchange = () => { ov.checked ? this.overlayIds.add(s.id) : this.overlayIds.delete(s.id); this.save(); this.applyOverlays(); };
      const op = li.querySelector('[data-a=op]');
      op.value = s.opacity ?? 0.7;
      op.oninput = () => { s.opacity = +op.value; this.save(); this.applyOverlays(); };
      const b = li.querySelector('[data-a=base]');
      if (b) b.onclick = () => { this.overlayIds.delete(s.id); this.save(); this.fillSelect(); $('baseMap').value = s.id; this.applyBase(s.id); this.applyOverlays(); this.renderList(); };
      const go = li.querySelector('[data-a=go]');
      if (go) go.onclick = () => { map.fitBounds(s.bounds); $('mapsDialog').close(); };
      li.querySelector('[data-a=del]').onclick = () => {
        const l = this.layers[s.id];
        if (l && map.hasLayer(l)) map.removeLayer(l);
        delete this.layers[s.id];
        if (s.tileId && window.sirjoe && window.sirjoe.maps) window.sirjoe.maps.close(s.tileId);
        this.sources = this.sources.filter((x) => x !== s);
        this.overlayIds.delete(s.id);
        if (store.get('base', 'topo') === s.id) this.applyBase('topo');
        this.save(); this.fillSelect(); this.renderList();
      };
      ul.appendChild(li);
    }
  },

  /* ---------------------------------------------------- file di mappa */
  async openFiles(folder) {
    if (!(window.sirjoe && window.sirjoe.maps)) return toast('I file di mappa si aprono nel programma per Windows');
    toast('Apertura della mappa…');
    const res = await window.sirjoe.maps.open(folder);
    for (const r of res) this.addResult(r, true);
  },

  /** Risultato del processo principale -> fonte nell'elenco */
  addResult(r, fresh) {
    if (!r.ok) { toast(r.path.split(/[\\/]/).pop() + ': ' + r.error); return null; }
    let s = this.sources.find((x) => x.path === r.path);
    if (!s) {
      s = { id: 'f' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6), path: r.path, opacity: 0.7 };
      this.sources.push(s);
    }
    s.name = s.name || r.name;
    delete this.layers[s.id];
    if (r.type === 'tiles') {
      Object.assign(s, { kind: 'file-tiles', tileId: r.id, minZoom: r.minzoom, maxZoom: r.maxzoom, bounds: r.bounds, attribution: r.attribution || '' });
    } else {
      s.kind = 'file-images';
      s.dataOverlays = r.overlays.map((o) => ({ url: o.kind === 'raw' ? rawToDataUrl(o) : o.dataUrl, bounds: o.bounds }));
      const all = r.overlays.map((o) => o.bounds).flat();
      s.bounds = [[Math.min(...all.map((p) => p[0])), Math.min(...all.map((p) => p[1]))], [Math.max(...all.map((p) => p[0])), Math.max(...all.map((p) => p[1]))]];
      this.overlayIds.add(s.id); // le immagini stanno sopra la mappa di base
      if (fresh) s.opacity = 0.85;
    }
    this.save();
    this.fillSelect();
    if (fresh) {
      if (s.kind === 'file-tiles') {
        $('baseMap').value = s.id;
        this.applyBase(s.id);
      }
      this.applyOverlays();
      if (s.bounds) map.fitBounds(s.bounds);
      toast('Mappa aggiunta: ' + s.name);
      if ($('mapsDialog').open) this.renderList();
    }
    return s;
  },

  /* ------------------------------------------------------ fonti online */
  addOnline() {
    const name = $('msName').value.trim() || 'Mappa online';
    let url = $('msUrl').value.trim();
    let kind = $('msKind').value;
    if (!/^https?:\/\//i.test(url)) return toast('Scrivi l\'indirizzo completo (https://…)');
    if (kind === 'auto') {
      if (/\{(q|quadkey)\}/i.test(url)) kind = 'bing';
      else if (/service=wms|\/wms\b|request=getmap/i.test(url) && !/\{z\}/i.test(url)) kind = 'wms';
      else if (/\{-y\}/.test(url)) kind = 'tms';
      else kind = 'xyz';
    }
    const src = { id: 'u' + Date.now().toString(36), name, kind, url, maxZoom: +$('msMax').value || 19, attribution: $('msAttr').value.trim(), opacity: 0.7 };
    if (kind === 'wms') {
      // indirizzo GetMap incollato per intero: tieni solo la base e il livello
      try {
        const u = new URL(url);
        const p = Object.fromEntries([...u.searchParams].map(([k, v]) => [k.toLowerCase(), v]));
        src.layers = $('msLayers').value.trim() || p.layers || '';
        if (p.version) src.version = p.version;
        if (p.format) src.format = p.format;
        for (const k of [...u.searchParams.keys()]) if (['service', 'request', 'layers', 'styles', 'format', 'transparent', 'version', 'width', 'height', 'crs', 'srs', 'bbox'].includes(k.toLowerCase())) u.searchParams.delete(k);
        src.url = u.toString();
      } catch (e) { /* lascia com'è */ }
      if (!src.layers) return toast('Per una mappa WMS scrivi il nome del livello (layers)');
    } else if (!/\{(z|TileMatrix|zoom)\}/i.test(url) && kind !== 'bing') {
      return toast('L\'indirizzo deve contenere {z}, {x} e {y} (o {q} per Bing)');
    }
    this.sources.push(src);
    this.save();
    this.fillSelect();
    $('baseMap').value = src.id;
    this.applyBase(src.id);
    this.renderList();
    for (const id of ['msName', 'msUrl', 'msLayers', 'msAttr']) $(id).value = '';
    $('msKind').value = 'auto';
    toast('Mappa aggiunta: ' + name);
  },

  /** All'avvio riapre i file delle mie mappe */
  async init() {
    this.fillSelect();
    if (window.sirjoe && window.sirjoe.maps) {
      for (const s of this.sources.filter((x) => x.path)) {
        const r = await window.sirjoe.maps.reopen(s.path);
        if (r.ok) this.addResult(r, false);
        else { s.tileId = null; s.dataOverlays = null; }
      }
    }
    this.fillSelect();
    const base = store.get('base', 'topo');
    if (base !== 'topo' && base !== 'osm' && this.find(base)) this.applyBase(base);
    else this.labelsCheck();
    this.applyOverlays();
  }
};

/** Immagine grezza (RGBA) dal GeoTIFF -> data URL PNG */
function rawToDataUrl(o) {
  const c = document.createElement('canvas');
  c.width = o.width; c.height = o.height;
  const data = new Uint8ClampedArray(o.rgba.buffer ? o.rgba.buffer.slice(o.rgba.byteOffset, o.rgba.byteOffset + o.rgba.byteLength) : o.rgba);
  c.getContext('2d').putImageData(new ImageData(data, o.width, o.height), 0, 0);
  return c.toDataURL('image/png');
}

/* ------------------------------------- le mie uscite registrate sulla mappa */
const RidesOverlay = {
  layer: L.layerGroup(),
  async show(on) {
    store.set('ridesOverlay', on);
    this.layer.clearLayers();
    if (!on) { map.removeLayer(this.layer); return; }
    let rides = [];
    try { rides = await RideStore.list(); } catch (e) { /* nessuna */ }
    if (!rides.length) { toast('Nessuna uscita registrata: inviale dal telefono con SirJoe Link o aggiungile nella scheda Uscite'); return; }
    const all = [];
    for (const r of rides) {
      try {
        const parsed = Formats.parse(new TextEncoder().encode(await RideStore.read(r.file)).buffer, r.file);
        const pts = parsed.points.filter((p) => isFinite(p.lat) && isFinite(p.lon));
        if (pts.length < 2) continue;
        const step = Math.max(1, Math.floor(pts.length / 2000));
        const ll = pts.filter((_, i) => i % step === 0 || i === pts.length - 1).map((p) => [p.lat, p.lon]);
        all.push(...ll);
        L.polyline(ll, { color: '#8E24AA', weight: 4, opacity: 0.6 })
          .bindTooltip(`${r.name} · ${Fmt.km(Geo.summary(pts).dist)} km`, { sticky: true })
          .on('click', () => { selectTab('rides'); Analysis.open(r.name, pts); })
          .addTo(this.layer);
      } catch (e) { /* uscita illeggibile: salta */ }
    }
    this.layer.addTo(map);
    if (all.length && !map.getBounds().intersects(L.latLngBounds(all))) map.fitBounds(L.latLngBounds(all), { padding: [30, 30] });
  }
};

/* ------------------------------------------------------------- eventi */
$('baseMap').addEventListener('change', (e) => {
  const v = e.target.value;
  if (v === '__more') {
    e.target.value = store.get('base', 'topo');
    Maps.show();
    return;
  }
  Maps.applyBase(v);
  Maps.applyOverlays();
});
$('mapsBtn').addEventListener('click', () => Maps.show());
$('mapsFile').addEventListener('click', () => Maps.openFiles(false));
$('mapsFolder').addEventListener('click', () => Maps.openFiles(true));
$('msAdd').addEventListener('click', () => Maps.addOnline());
$('mapsSat').addEventListener('change', (e) => { store.set('satLabels', e.target.checked); Maps.labelsCheck(); });
$('ridesOverlay').checked = store.get('ridesOverlay', false);
$('ridesOverlay').addEventListener('change', (e) => RidesOverlay.show(e.target.checked));
Maps.init().then(() => { if (store.get('ridesOverlay', false)) RidesOverlay.show(true); });
