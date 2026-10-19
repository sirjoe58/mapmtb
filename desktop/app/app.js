/* SirJoe MTB Planner - interfaccia */
'use strict';

const $ = (id) => document.getElementById(id);
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { toast('Spazio di archiviazione pieno'); } }
};

const DISCLAIMER = `SirJoe MTB Planner è un programma gratuito e open source per USO PERSONALE e non commerciale. Usandolo accetti queste avvertenze.

• I percorsi sono calcolati in automatico (BRouter, dati OpenStreetMap) o disegnati a mano: possono passare su tratti chiusi, privati, pericolosi o vietati alle bici. Controllali prima di partire; sul posto valgono sempre la segnaletica, le ordinanze locali e il buon senso.
• Mappe e dati possono essere incompleti o non aggiornati. Distanze, dislivelli e tempi sono stime.
• Percorri solo sentieri e strade dove il transito in bicicletta è consentito; rispetta parchi, aree protette, proprietà private e divieti stagionali; dai la precedenza agli escursionisti.
• Il programma non è un dispositivo di sicurezza. In montagna valuta meteo, orari e capacità, porta una cartina e informa qualcuno del tuo itinerario. In emergenza chiama il 112.
• Una traccia condivisa mostra luoghi e spesso dove abiti: condividila con attenzione.
• Mappe © OpenStreetMap contributors (ODbL), OpenTopoMap (CC-BY-SA); itinerari MTB waymarkedtrails.org; percorsi BRouter; ricerca Nominatim. Sono servizi gratuiti gestiti da volontari: il programma li usa con moderazione e non scarica aree in blocco. Non modificarlo per aggirare questi limiti.
• Nel Planner per computer (Windows e Mac) puoi usare mappe satellitari e di altri fornitori (Esri, EOX Sentinel-2, CyclOSM) e aggiungere fonti online o file di mappa tuoi: rispetta le condizioni d'uso di ciascun fornitore (alcuni, come Google, non permettono di usare le loro immagini in altri programmi), lascia visibili le attribuzioni e importa solo mappe che hai il diritto di usare. Le immagini satellitari possono essere vecchie: sentieri, frane e costruzioni recenti possono non comparire.
• Il programma è fornito "così com'è", senza garanzie (licenza MIT). Chi lo usa lo fa sotto la propria responsabilità.`;

/* -------------------------------------------------------------- stato */

let state = blankState();
const history = [];
let mode = 'route';
let busy = 0;

function blankState() {
  return { id: null, name: '', profile: store.get('profile', 'offroad'), points: [], legs: [], places: store.get('places', []) };
}
function snapshot() {
  history.push(JSON.stringify({ points: state.points, legs: state.legs, places: state.places }));
  if (history.length > 80) history.shift();
}
function undo() {
  if (busy) return toast('Attendi la fine del calcolo in corso');
  const s = history.pop();
  if (!s) return toast('Niente da annullare');
  const o = JSON.parse(s);
  state.points = o.points; state.legs = o.legs; state.places = o.places;
  savePlaces(); render();
}
function allPoints() {
  if (!state.legs.length) return state.points.map((p) => ({ ...p }));
  const out = [];
  for (const leg of state.legs) {
    if (out.length && leg.length && Geo.dist(out[out.length - 1], leg[0]) < 1) out.push(...leg.slice(1));
    else out.push(...leg);
  }
  return out;
}
function savePlaces() { store.set('places', state.places); }

/* --------------------------------------------------------------- mappa */

const map = L.map('map', { zoomControl: true, attributionControl: true }).setView([45.9, 11.0], 9);
const bases = {
  topo: L.tileLayer('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png', {
    maxZoom: 17, subdomains: 'abc',
    attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>, © <a href="https://opentopomap.org">OpenTopoMap</a> (CC-BY-SA)'
  }),
  osm: L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19, attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
  })
};
const mtbLayer = L.tileLayer('https://tile.waymarkedtrails.org/mtb/{z}/{x}/{y}.png', {
  maxZoom: 18, opacity: 0.8, attribution: 'Itinerari MTB: <a href="https://mtb.waymarkedtrails.org">waymarkedtrails.org</a>'
});
let currentBase = null;
function setBase(id) {
  if (currentBase) map.removeLayer(currentBase);
  currentBase = bases[id] || (typeof Maps !== 'undefined' && Maps.baseLayer(id)) || bases.topo;
  currentBase.addTo(map);
  store.set('base', id);
}
setBase(store.get('base', 'topo'));
if ([...$('baseMap').options].some((o) => o.value === store.get('base', 'topo'))) $('baseMap').value = store.get('base', 'topo');
if (store.get('mtb', true)) mtbLayer.addTo(map); else $('mtbOverlay').checked = false;
L.control.scale({ imperial: false }).addTo(map);
const last = store.get('view', null);
if (last) map.setView([last.lat, last.lon], last.z);
map.on('moveend', () => { const c = map.getCenter(); store.set('view', { lat: c.lat, lon: c.lng, z: map.getZoom() }); });

const routeLayer = L.layerGroup().addTo(map);
const pointLayer = L.layerGroup().addTo(map);
const placeLayer = L.layerGroup().addTo(map);
const drawLayer = L.layerGroup().addTo(map);
let hoverMarker = null;

/* -------------------------------------------------------------- disegno */

function render() {
  routeLayer.clearLayers();
  pointLayer.clearLayers();
  placeLayer.clearLayers();

  const slopeOn = $('slopeColors').checked;
  state.legs.forEach((leg, i) => {
    const ll = leg.map((p) => [p.lat, p.lon]);
    L.polyline(ll, { color: '#ffffff', weight: 9, opacity: 0.8 }).addTo(routeLayer);
    if (slopeOn) colorLine(leg, Geo.grades(leg).map(slopeColor), routeLayer, 5);
    const line = L.polyline(ll, slopeOn ? { color: '#000', weight: 12, opacity: 0 } : { color: '#8e24aa', weight: 5 }).addTo(routeLayer);
    line.on('click', (e) => {
      if (mode !== 'route') return;
      L.DomEvent.stop(e);
      insertPoint(i, { lat: e.latlng.lat, lon: e.latlng.lng });
    });
  });

  state.points.forEach((p, i) => {
    const cls = i === 0 ? 'rp start' : i === state.points.length - 1 ? 'rp end' : 'rp';
    const m = L.marker([p.lat, p.lon], {
      draggable: true,
      icon: L.divIcon({ className: '', html: `<div class="${cls}"></div>`, iconSize: [16, 16], iconAnchor: [8, 8] }),
      title: i === 0 ? 'Partenza' : 'Punto ' + (i + 1) + ' – clic destro per togliere'
    }).addTo(pointLayer);
    m.on('dragend', () => { const ll = m.getLatLng(); movePoint(i, { lat: ll.lat, lon: ll.lng }); });
    m.on('contextmenu', () => removePoint(i));
  });

  state.places.forEach((pl, i) => {
    const t = placeType(pl.type);
    const svg = `<svg width="30" height="40" viewBox="0 0 34 44"><path d="M17 1C8.2 1 1 8.1 1 16.9 1 28.6 17 43 17 43s16-14.4 16-26.1C33 8.1 25.8 1 17 1z" fill="#fff"/><path d="M17 3.5C9.6 3.5 3.5 9.5 3.5 16.9c0 9.7 13.5 22.5 13.5 22.5s13.5-12.8 13.5-22.5C30.5 9.5 24.4 3.5 17 3.5z" fill="${t.color}"/><circle cx="17" cy="17" r="5.5" fill="#fff"/></svg>`;
    const m = L.marker([pl.lat, pl.lon], {
      draggable: true,
      icon: L.divIcon({ className: 'pin', html: svg, iconSize: [30, 40], iconAnchor: [15, 40] }),
      title: pl.name
    }).addTo(placeLayer);
    m.bindTooltip(pl.name, { direction: 'top', offset: [0, -38] });
    m.on('click', () => editPlace(i));
    m.on('dragend', () => {
      snapshot();
      const ll = m.getLatLng();
      Object.assign(state.places[i], { lat: ll.lat, lon: ll.lng, ele: null });
      savePlaces(); render();
    });
  });

  renderStats();
  renderClimbs();
  renderPlaceList();
  drawProfile();
}

/** linea divisa in tratti colorati (colori[i] = colore del tratto che parte dal punto i) */
function colorLine(pts, colors, layer, weight) {
  let start = 0;
  for (let i = 1; i <= pts.length; i++) {
    if (i === pts.length || colors[i] !== colors[start]) {
      const seg = pts.slice(start, Math.min(i + 1, pts.length)).map((p) => [p.lat, p.lon]);
      if (seg.length >= 2) L.polyline(seg, { color: colors[start], weight, interactive: false }).addTo(layer);
      start = i;
    }
  }
}

function climbHtml(c) {
  return `<span>da km ${Fmt.km(c.start)} · ${Fmt.m(c.length)} m</span><span class="grade" style="color:${slopeColor(c.avg)}">${(c.avg * 100).toFixed(1)}% · max ${(c.max * 100).toFixed(0)}% · +${Fmt.m(c.gain)} m</span>`;
}

function renderClimbs() {
  const box = $('climbBox');
  const pts = allPoints();
  const climbs = Geo.climbs(pts);
  box.innerHTML = '';
  if (!climbs.length) {
    box.innerHTML = pts.some((p) => p.ele != null) ? '<div class="small">Nessuna salita impegnativa</div>' : '';
    return;
  }
  const head = document.createElement('div');
  head.className = 'small';
  head.textContent = `Salite: ${climbs.length} · la più dura ${(Math.max(...climbs.map((c) => c.avg)) * 100).toFixed(0)}% di media`;
  box.appendChild(head);
  const cum = Geo.cumulative(pts);
  for (const c of climbs) {
    const d = document.createElement('div');
    d.className = 'climb';
    d.innerHTML = climbHtml(c);
    d.addEventListener('click', () => {
      const a = cum.findIndex((x) => x >= c.start), b = cum.findIndex((x) => x >= c.end);
      const seg = pts.slice(Math.max(0, a), b < 0 ? pts.length : b + 1);
      if (seg.length >= 2) map.fitBounds(L.latLngBounds(seg.map((p) => [p.lat, p.lon])), { padding: [60, 60] });
    });
    box.appendChild(d);
  }
}

function renderStats() {
  const pts = allPoints();
  const s = Geo.summary(pts);
  $('stDist').textContent = Fmt.km(s.dist);
  $('stUp').textContent = s.hasEle ? Fmt.m(s.up) : '–';
  $('stDown').textContent = s.hasEle ? Fmt.m(s.down) : '–';
  $('stTime').textContent = Fmt.dur(Fmt.estimate(s.dist, s.up));
}

/* ------------------------------------------------------------ percorso */

function setBusy(on, msg) {
  busy += on ? 1 : -1;
  $('busy').hidden = busy <= 0;
  if (msg) $('busy').textContent = msg;
}

async function computeLeg(a, b) {
  try {
    return await Routing.leg(a, b, state.profile);
  } catch (e) {
    toast('Calcolo non riuscito (' + e.message + '): tratto in linea retta');
    return [{ ...a, ele: null }, { ...b, ele: null }];
  }
}

async function addPoint(p) {
  if (busy) return toast('Attendi il calcolo in corso');
  snapshot();
  if (!state.points.length) { state.points.push(p); render(); return; }
  const prev = state.points[state.points.length - 1];
  setBusy(true, 'Calcolo del percorso…');
  try {
    const leg = await computeLeg(prev, p);
    state.points.push(p);
    state.legs.push(leg);
  } finally { setBusy(false); }
  render();
}

async function insertPoint(legIndex, p) {
  if (busy) return;
  snapshot();
  setBusy(true, 'Calcolo del percorso…');
  try {
    const a = state.points[legIndex], b = state.points[legIndex + 1];
    const [l1, l2] = await Promise.all([computeLeg(a, p), computeLeg(p, b)]);
    state.points.splice(legIndex + 1, 0, p);
    state.legs.splice(legIndex, 1, l1, l2);
  } finally { setBusy(false); }
  render();
}

async function movePoint(i, p) {
  if (busy) { render(); return; }
  snapshot();
  setBusy(true, 'Calcolo del percorso…');
  try {
    state.points[i] = p;
    const jobs = [];
    if (i > 0) jobs.push(computeLeg(state.points[i - 1], p).then((l) => { state.legs[i - 1] = l; }));
    if (i < state.points.length - 1) jobs.push(computeLeg(p, state.points[i + 1]).then((l) => { state.legs[i] = l; }));
    await Promise.all(jobs);
  } finally { setBusy(false); }
  render();
}

async function removePoint(i) {
  if (busy) return;
  snapshot();
  const n = state.points.length;
  if (n === 1) { state.points = []; state.legs = []; render(); return; }
  if (i === 0) { state.points.shift(); state.legs.shift(); }
  else if (i === n - 1) { state.points.pop(); state.legs.pop(); }
  else {
    setBusy(true, 'Calcolo del percorso…');
    try {
      const l = await computeLeg(state.points[i - 1], state.points[i + 1]);
      state.points.splice(i, 1);
      state.legs.splice(i - 1, 2, l);
    } finally { setBusy(false); }
  }
  render();
}

async function recalcAll() {
  if (state.points.length < 2 || busy) return;
  snapshot();
  setBusy(true);
  try {
    for (let i = 0; i < state.points.length - 1; i++) {
      $('busy').textContent = `Calcolo tratto ${i + 1} di ${state.points.length - 1}…`;
      state.legs[i] = await computeLeg(state.points[i], state.points[i + 1]);
      render();
    }
  } finally { setBusy(false); }
  render();
}

function reverseRoute() {
  if (state.points.length < 2) return;
  snapshot();
  state.points.reverse();
  state.legs = state.legs.map((l) => l.slice().reverse()).reverse();
  render();
}

function closeLoop() {
  if (state.points.length < 2) return toast('Servono almeno due punti');
  addPoint({ ...state.points[0] });
}

function clearRoute() {
  if (busy) return toast('Attendi la fine del calcolo in corso');
  if (!state.points.length) return toast('Niente da cancellare');
  if (!confirm('Cancellare il percorso? I segnaposto restano.')) return;
  snapshot();
  state.points = []; state.legs = [];
  render();
}

/* --------------------------------------- disegno a mano libera (tavoletta) */

let stroke = null;
const mapEl = $('map');

function douglasPeucker(pts, tol) {
  if (pts.length < 3) return pts;
  const toXY = (p) => ({ x: p.lon * 111320 * Math.cos(p.lat * Math.PI / 180), y: p.lat * 110540 });
  const xy = pts.map(toXY);
  const keep = new Uint8Array(pts.length);
  keep[0] = keep[pts.length - 1] = 1;
  const stack = [[0, pts.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop();
    let maxD = 0, idx = -1;
    const A = xy[a], B = xy[b];
    const dx = B.x - A.x, dy = B.y - A.y, len = Math.hypot(dx, dy) || 1;
    for (let i = a + 1; i < b; i++) {
      const d = Math.abs(dy * xy[i].x - dx * xy[i].y + B.x * A.y - B.y * A.x) / len;
      if (d > maxD) { maxD = d; idx = i; }
    }
    if (maxD > tol && idx > 0) { keep[idx] = 1; stack.push([a, idx], [idx, b]); }
  }
  return pts.filter((_, i) => keep[i]);
}

/** punti del tratto disegnato presi ogni "step" metri (per agganciarlo ai sentieri) */
function resample(pts, step) {
  const out = [pts[0]];
  let acc = 0;
  for (let i = 1; i < pts.length; i++) {
    acc += Geo.dist(pts[i - 1], pts[i]);
    if (acc >= step) { out.push(pts[i]); acc = 0; }
  }
  if (out[out.length - 1] !== pts[pts.length - 1]) out.push(pts[pts.length - 1]);
  return out;
}

mapEl.addEventListener('pointerdown', (e) => {
  if (mode !== 'draw' || e.button > 0 && e.pointerType === 'mouse') return;
  if (busy) return toast('Attendi il calcolo in corso');
  e.preventDefault();
  e.stopPropagation();
  mapEl.setPointerCapture(e.pointerId);
  const ll = map.mouseEventToLatLng(e);
  stroke = { id: e.pointerId, pts: [{ lat: ll.lat, lon: ll.lng }], line: L.polyline([ll], { color: '#ff6d00', weight: 4, dashArray: '6 6' }).addTo(drawLayer) };
}, true);

mapEl.addEventListener('pointermove', (e) => {
  if (!stroke || e.pointerId !== stroke.id) return;
  e.preventDefault();
  e.stopPropagation();
  const events = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
  for (const ev of events) {
    const ll = map.mouseEventToLatLng(ev);
    stroke.pts.push({ lat: ll.lat, lon: ll.lng });
    stroke.line.addLatLng(ll);
  }
}, true);

async function endStroke(e) {
  if (!stroke || e.pointerId !== stroke.id) return;
  e.stopPropagation();
  const raw = stroke.pts;
  stroke = null;
  drawLayer.clearLayers();
  if (raw.length < 2 || Geo.cumulative(raw).pop() < 15) return;
  // pulizia del tratto: toglie i tremolii della mano (tolleranza in metri secondo lo zoom)
  const metersPerPx = 156543.03 * Math.cos(map.getCenter().lat * Math.PI / 180) / 2 ** map.getZoom();
  const clean = douglasPeucker(raw, Math.max(2, metersPerPx * 2));
  snapshot();
  const first = clean[0];
  // se il percorso esiste e il tratto parte lontano dalla fine, lo collega
  if (state.points.length) {
    const lastPt = state.points[state.points.length - 1];
    clean.unshift({ lat: lastPt.lat, lon: lastPt.lon });
  } else {
    state.points.push({ lat: first.lat, lon: first.lon });
  }
  if (state.profile === 'straight') {
    // il tratto resta come disegnato
    const end = clean[clean.length - 1];
    state.points.push({ lat: end.lat, lon: end.lon });
    state.legs.push(clean.map((p) => ({ lat: p.lat, lon: p.lon, ele: null })));
    render();
    return;
  }
  // aggancio ai sentieri: punti di passaggio ogni ~ 1/6 del tratto (tra 150 m e 600 m)
  const len = Geo.cumulative(clean).pop();
  const step = Math.min(600, Math.max(150, len / 6));
  const vias = resample(clean, step).slice(1);
  setBusy(true);
  try {
    for (let i = 0; i < vias.length; i++) {
      $('busy').textContent = `Aggancio ai sentieri: ${i + 1} di ${vias.length}…`;
      const prev = state.points[state.points.length - 1];
      const leg = await computeLeg(prev, vias[i]);
      state.points.push({ lat: vias[i].lat, lon: vias[i].lon });
      state.legs.push(leg);
      render();
    }
  } finally { setBusy(false); }
  render();
}
mapEl.addEventListener('pointerup', endStroke, true);
mapEl.addEventListener('pointercancel', endStroke, true);

/* ---------------------------------------------------------- segnaposto */

let editing = null;
function fillTypes() {
  $('pdType').innerHTML = PLACE_TYPES.map((t) => `<option value="${t.id}">${t.label}</option>`).join('');
}
fillTypes();

function newPlace(ll) {
  const p = { name: 'Segnaposto ' + (state.places.length + 1), note: '', lat: ll.lat, lon: ll.lng, ele: null, type: 'generic' };
  snapshot();
  state.places.push(p);
  savePlaces(); render();
  editPlace(state.places.length - 1, true);
}

function editPlace(i, isNew) {
  const p = state.places[i];
  editing = i;
  $('placeDialogTitle').textContent = isNew ? 'Nuovo segnaposto' : 'Modifica segnaposto';
  $('pdName').value = p.name;
  $('pdNote').value = p.note || '';
  $('pdType').value = p.type;
  $('pdCoords').textContent = `${p.lat.toFixed(5)}, ${p.lon.toFixed(5)}`;
  $('placeDialog').showModal();
  setTimeout(() => $('pdName').select(), 30);
}

$('placeDialog').addEventListener('close', () => {
  const i = editing;
  editing = null;
  if (i == null || !state.places[i]) return;
  const v = $('placeDialog').returnValue;
  if (v === 'save') {
    Object.assign(state.places[i], { name: $('pdName').value.trim() || state.places[i].name, note: $('pdNote').value.trim(), type: $('pdType').value });
  } else if (v === 'delete') {
    snapshot();
    state.places.splice(i, 1);
  } else if (v === 'addroute') {
    const p = state.places[i];
    savePlaces(); render();
    addPoint({ lat: p.lat, lon: p.lon });
    return;
  }
  savePlaces(); render();
});

function renderPlaceList() {
  const ul = $('placeList');
  if (!state.places.length) {
    ul.innerHTML = '<li class="empty">Nessun segnaposto</li>';
    return;
  }
  const checked = store.get('placesChecked', {});
  ul.innerHTML = '';
  state.places.forEach((p, i) => {
    const li = document.createElement('li');
    const key = p.lat.toFixed(6) + ',' + p.lon.toFixed(6);
    li.innerHTML = `<input type="checkbox" ${checked[key] !== false ? 'checked' : ''}>
      <span class="dot" style="background:${placeType(p.type).color}"></span>
      <span class="grow"><b></b><small>${placeType(p.type).label}</small></span>
      <button class="btn" data-a="up" title="Sposta su">▲</button>`;
    li.querySelector('b').textContent = p.name;
    li.querySelector('input').addEventListener('click', (e) => {
      e.stopPropagation();
      const c = store.get('placesChecked', {}); c[key] = e.target.checked; store.set('placesChecked', c);
    });
    li.querySelector('[data-a=up]').addEventListener('click', (e) => {
      e.stopPropagation();
      if (i === 0) return;
      snapshot();
      [state.places[i - 1], state.places[i]] = [state.places[i], state.places[i - 1]];
      savePlaces(); render();
    });
    li.addEventListener('click', () => { map.setView([p.lat, p.lon], Math.max(map.getZoom(), 15)); editPlace(i); });
    ul.appendChild(li);
  });
}

async function routeThroughPlaces() {
  const checked = store.get('placesChecked', {});
  const list = state.places.filter((p) => checked[p.lat.toFixed(6) + ',' + p.lon.toFixed(6)] !== false);
  const pts = list.map((p) => ({ lat: p.lat, lon: p.lon }));
  if ($('placesLoop').checked && pts.length >= 2) pts.push({ ...pts[0] });
  if (pts.length < 2) return toast('Spunta almeno due segnaposto');
  if (state.points.length && !confirm('Sostituire il percorso attuale?')) return;
  snapshot();
  state.points = [pts[0]]; state.legs = [];
  setBusy(true);
  try {
    for (let i = 1; i < pts.length; i++) {
      $('busy').textContent = `Calcolo tratto ${i} di ${pts.length - 1}…`;
      state.legs.push(await computeLeg(pts[i - 1], pts[i]));
      state.points.push(pts[i]);
      render();
    }
  } finally { setBusy(false); }
  if (!$('routeName').value) $('routeName').value = list[0].name + ' → ' + list[list.length - 1].name;
  render();
  fitRoute();
  selectTab('route');
}

/* ------------------------------------------------- profilo altimetrico */

const elev = $('elev');
let elevData = null;
function drawProfile() {
  if ($('profileBox').hidden) return; // profilo nascosto: niente da disegnare
  const pts = (window.Analysis && Analysis.active) ? Analysis.points : allPoints();
  const ctx = elev.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  const w = elev.clientWidth, h = elev.clientHeight;
  elev.width = w * dpr; elev.height = h * dpr;
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, w, h);
  const withEle = pts.filter((p) => p.ele != null);
  if (withEle.length < 2) {
    elevData = null;
    $('elevInfo').textContent = pts.length >= 2
      ? 'Profilo altimetrico non disponibile (tratti in linea retta)'
      : 'Profilo altimetrico: aggiungi almeno due punti';
    return;
  }
  const cum = Geo.cumulative(withEle);
  const total = cum[cum.length - 1] || 1;
  const eles = withEle.map((p) => p.ele);
  const min = Math.min(...eles), max = Math.max(...eles), range = Math.max(20, max - min);
  const pl = 48, pr = 14, pt = 26, pb = 22;
  const X = (d) => pl + (d / total) * (w - pl - pr);
  const Y = (e) => pt + (1 - (e - min) / range) * (h - pt - pb);
  elevData = { withEle, cum, total, X, Y, pl, pr, w };

  ctx.strokeStyle = 'rgba(255,255,255,.08)'; ctx.fillStyle = '#a9bfb2'; ctx.font = '11px Segoe UI, sans-serif';
  for (let k = 0; k <= 2; k++) {
    const e = min + (range * k) / 2, y = Y(e);
    ctx.beginPath(); ctx.moveTo(pl, y); ctx.lineTo(w - pr, y); ctx.stroke();
    ctx.fillText(Math.round(e) + ' m', 4, y + 4);
  }
  const stepKm = total > 40000 ? 10 : total > 15000 ? 5 : total > 5000 ? 2 : 1;
  for (let d = 0; d <= total; d += stepKm * 1000) ctx.fillText(d / 1000 + ' km', X(d) - 10, h - 6);

  const grad = ctx.createLinearGradient(0, pt, 0, h - pb);
  grad.addColorStop(0, 'rgba(255,109,0,.55)'); grad.addColorStop(1, 'rgba(255,109,0,.05)');
  ctx.beginPath(); ctx.moveTo(X(0), Y(min));
  withEle.forEach((p, i) => ctx.lineTo(X(cum[i]), Y(p.ele)));
  ctx.lineTo(X(total), Y(min)); ctx.closePath(); ctx.fillStyle = grad; ctx.fill();
  ctx.beginPath();
  withEle.forEach((p, i) => (i ? ctx.lineTo(X(cum[i]), Y(p.ele)) : ctx.moveTo(X(cum[i]), Y(p.ele))));
  ctx.strokeStyle = '#ff6d00'; ctx.lineWidth = 2; ctx.stroke();
  $('elevInfo').textContent = `Profilo altimetrico · min ${Math.round(min)} m · max ${Math.round(max)} m — passa il mouse per vedere il punto sulla mappa`;
  if (window.Analysis && Analysis.active) Analysis.afterProfile(ctx, elevData, h);
}
elev.addEventListener('mousemove', (e) => {
  if (!elevData) return;
  const r = elev.getBoundingClientRect();
  const x = e.clientX - r.left;
  const d = ((x - elevData.pl) / (elevData.w - elevData.pl - elevData.pr)) * elevData.total;
  let i = elevData.cum.findIndex((c) => c >= d);
  if (i < 0) i = elevData.cum.length - 1;
  const p = elevData.withEle[i];
  if (!hoverMarker) hoverMarker = L.marker([p.lat, p.lon], { icon: L.divIcon({ className: '', html: '<div class="hover-dot"></div>', iconSize: [12, 12], iconAnchor: [6, 6] }), interactive: false }).addTo(map);
  hoverMarker.setLatLng([p.lat, p.lon]);
  $('elevInfo').textContent = `${Fmt.km(elevData.cum[i])} km · ${Math.round(p.ele)} m`;
});
elev.addEventListener('mouseleave', () => { if (hoverMarker) { map.removeLayer(hoverMarker); hoverMarker = null; } drawProfile(); });
window.addEventListener('resize', drawProfile);

/* profilo altimetrico a scomparsa: si apre su richiesta */
function setProfile(open) {
  $('profileBox').hidden = !open;
  $('profileTab').hidden = open;
  $('profileBtn').classList.toggle('on', open);
  store.set('profileOpen', open);
  map.invalidateSize();
  if (open) drawProfile();
}
$('profileBtn').addEventListener('click', () => setProfile($('profileBox').hidden));
$('profileTab').addEventListener('click', () => setProfile(true));
$('profileClose').addEventListener('click', () => setProfile(false));
setProfile(store.get('profileOpen', false));

/* --------------------------------------------- salva, archivio, file */

function fitRoute() {
  const pts = allPoints().concat(state.places);
  if (pts.length >= 2) map.fitBounds(L.latLngBounds(pts.map((p) => [p.lat, p.lon])), { padding: [40, 40] });
}

function routeName() {
  return $('routeName').value.trim() || 'Percorso ' + new Date().toLocaleDateString('it-IT');
}

/** Salva nell'archivio interno chiedendo il nome */
function saveToLibrary() {
  if (state.points.length < 2) return toast('Aggiungi almeno due punti');
  $('ndName').value = routeName();
  $('ndNew').hidden = !state.id;
  $('nameDialog').showModal();
  setTimeout(() => $('ndName').select(), 30);
}
$('nameDialog').addEventListener('close', () => {
  const v = $('nameDialog').returnValue;
  if (v !== 'save' && v !== 'new') return;
  const name = $('ndName').value.trim() || routeName();
  $('routeName').value = name;
  if (v === 'new') state.id = null;
  const lib = store.get('library', []);
  const item = { id: state.id || Date.now().toString(36), name, date: Date.now(), profile: state.profile, points: state.points, legs: state.legs };
  const i = lib.findIndex((x) => x.id === item.id);
  if (i >= 0) lib[i] = item; else lib.unshift(item);
  state.id = item.id;
  store.set('library', lib);
  renderLibrary();
  toast('Salvato in archivio: ' + name);
});

function renderLibrary() {
  const lib = store.get('library', []);
  const ul = $('libList');
  if (!lib.length) { ul.innerHTML = '<li class="empty">Nessun percorso salvato</li>'; return; }
  ul.innerHTML = '';
  for (const it of lib) {
    const pts = [];
    it.legs.forEach((l) => pts.push(...l));
    const s = Geo.summary(pts);
    const li = document.createElement('li');
    li.innerHTML = `<span class="grow"><b></b><small>${Fmt.km(s.dist)} km${s.hasEle ? ' · ↗ ' + Fmt.m(s.up) + ' m' : ''} · ${new Date(it.date).toLocaleDateString('it-IT')}</small></span>
      <button class="btn" data-a="dup" title="Duplica">⧉</button><button class="btn danger" data-a="del" title="Elimina">✕</button>`;
    li.querySelector('b').textContent = it.name;
    li.addEventListener('click', () => {
      snapshot();
      Object.assign(state, { id: it.id, name: it.name, profile: it.profile, points: it.points, legs: it.legs });
      $('routeName').value = it.name; $('profile').value = it.profile;
      render(); fitRoute(); selectTab('route');
    });
    li.querySelector('[data-a=dup]').addEventListener('click', (e) => {
      e.stopPropagation();
      const copy = { ...it, id: Date.now().toString(36), name: it.name + ' (copia)', date: Date.now() };
      store.set('library', [copy, ...lib]); renderLibrary();
    });
    li.querySelector('[data-a=del]').addEventListener('click', (e) => {
      e.stopPropagation();
      if (!confirm('Eliminare "' + it.name + '"?')) return;
      store.set('library', lib.filter((x) => x.id !== it.id)); renderLibrary();
    });
    ul.appendChild(li);
  }
}

/** Salva un file: nel programma per Windows apre la finestra "Salva con nome" */
async function download(name, data, mime) {
  const ext = (name.split('.').pop() || 'gpx').toLowerCase();
  if (window.sirjoe && window.sirjoe.saveFile) {
    try {
      const path = await window.sirjoe.saveFile(name, data, ext);
      if (path) toast('Salvato: ' + path);
      return !!path;
    } catch (e) { toast('Salvataggio non riuscito: ' + e.message); return false; }
  }
  const blob = new Blob([data], { type: mime });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name.replace(/[\\/:*?"<>|]/g, '_');
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  return true;
}

/** "Salva con nome": GPX con percorso e segnaposto, pronto per l'app */
async function saveAs() {
  const pts = allPoints();
  if (pts.length < 2 && !state.places.length) return toast('Niente da salvare: crea prima un percorso');
  const ok = await download(routeName() + '.gpx', Formats.gpx(routeName(), pts, state.places), 'application/gpx+xml');
  if (ok && !(window.sirjoe && window.sirjoe.saveFile)) toast('GPX pronto: copialo sul telefono e importalo in SirJoe MTB (Percorsi → Importa)');
}

/** "Apri": finestra di Windows per scegliere i file */
async function openFiles() {
  if (window.sirjoe && window.sirjoe.openFiles) {
    const files = await window.sirjoe.openFiles();
    await importFiles(files.map((f) => ({ name: f.name, arrayBuffer: async () => f.data.buffer.slice(f.data.byteOffset, f.data.byteOffset + f.data.byteLength) })));
  } else $('importFile').click();
}

function exportFile() {
  const fmt = $('exportFormat').value;
  const pts = allPoints();
  if (pts.length < 2 && !(fmt === 'gpx' && state.places.length)) return toast('Niente da esportare: crea prima un percorso');
  try {
    const out = Formats.write(fmt, routeName(), pts, state.places);
    download(routeName() + '.' + out.ext, out.data, out.mime);
  } catch (e) { toast('Esportazione non riuscita: ' + e.message); }
}

async function importFiles(files) {
  for (const f of files) {
    try {
      const r = Formats.parse(await f.arrayBuffer(), f.name);
      snapshot();
      if (r.waypoints.length) { state.places.push(...r.waypoints.filter((w) => isFinite(w.lat))); savePlaces(); }
      if (r.points.length >= 2) {
        const pts = r.points.map((p) => ({ lat: p.lat, lon: p.lon, ele: p.ele }));
        state.id = null;
        state.points = [pts[0], pts[pts.length - 1]];
        state.legs = [pts];
        $('routeName').value = r.name;
      }
      render(); fitRoute();
      toast(`Importato "${r.name}": ${r.points.length} punti${r.waypoints.length ? ', ' + r.waypoints.length + ' segnaposto' : ''}`);
    } catch (e) { toast(f.name + ': ' + e.message); }
  }
}

/* --------------------------------------------------------------- vari */

let toastTimer = null;
function toast(msg) {
  const t = $('toast');
  t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 4000);
}

function selectTab(id) {
  document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === id));
  document.querySelectorAll('.panel').forEach((p) => p.classList.toggle('active', p.id === 'tab-' + id));
}

function setMode(m) {
  mode = m;
  document.querySelectorAll('.seg-btn').forEach((b) => b.classList.toggle('active', b.dataset.mode === m));
  mapEl.classList.toggle('place-mode', m === 'place');
  mapEl.classList.toggle('draw-mode', m === 'draw');
  $('drawBar').hidden = m !== 'draw';
  if (m === 'draw') {
    map.dragging.disable();
    toast(state.profile === 'straight'
      ? 'Disegna con la penna o col mouse: il tratto resta come lo disegni'
      : 'Disegna con la penna o col mouse: il tratto verrà agganciato ai sentieri (' + ROUTE_TYPES[state.profile].label + ')');
  } else map.dragging.enable();
}

map.on('click', (e) => {
  if (mode === 'route') addPoint({ lat: e.latlng.lat, lon: e.latlng.lng });
  else if (mode === 'place') newPlace(e.latlng);
});

document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => selectTab(b.dataset.tab)));
document.querySelectorAll('.seg-btn').forEach((b) => b.addEventListener('click', () => setMode(b.dataset.mode)));
$('profile').value = state.profile;
$('profile').addEventListener('change', (e) => { state.profile = e.target.value; store.set('profile', state.profile); });
$('recalc').addEventListener('click', recalcAll);
$('undo').addEventListener('click', undo);
$('reverse').addEventListener('click', reverseRoute);
$('loop').addEventListener('click', closeLoop);
$('clearRoute').addEventListener('click', clearRoute);
$('drawUndo').addEventListener('click', undo);
$('drawClear').addEventListener('click', clearRoute);
$('saveRoute').addEventListener('click', saveToLibrary);
$('newRoute').addEventListener('click', () => {
  if (state.points.length && !confirm('Iniziare un nuovo percorso? Quello attuale, se non salvato, andrà perso.')) return;
  snapshot();
  state.id = null; state.points = []; state.legs = [];
  $('routeName').value = '';
  render();
});
$('export').addEventListener('click', exportFile);
$('importBtn').addEventListener('click', openFiles);
$('saveAs').addEventListener('click', saveAs);
$('importFile').addEventListener('change', (e) => { importFiles(Array.from(e.target.files)); e.target.value = ''; });
$('routePlaces').addEventListener('click', routeThroughPlaces);
$('exportPlaces').addEventListener('click', () => {
  if (!state.places.length) return toast('Nessun segnaposto');
  download('Segnaposto SirJoe MTB.gpx', Formats.gpx('Segnaposto', [], state.places), 'application/gpx+xml');
});
// il cambio di mappa è gestito in maps.js (anche satellitari e le mie mappe)
$('slopeColors').checked = store.get('slopeColors', false);
$('slopeColors').addEventListener('change', (e) => { store.set('slopeColors', e.target.checked); render(); });
$('mtbOverlay').addEventListener('change', (e) => {
  store.set('mtb', e.target.checked);
  if (e.target.checked) mtbLayer.addTo(map); else map.removeLayer(mtbLayer);
});
document.addEventListener('keydown', (e) => {
  const k = e.key.toLowerCase();
  if ((e.ctrlKey || e.metaKey) && k === 'z' && !['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) { e.preventDefault(); undo(); }
  if ((e.ctrlKey || e.metaKey) && k === 's') { e.preventDefault(); saveAs(); }
  if ((e.ctrlKey || e.metaKey) && k === 'o') { e.preventDefault(); openFiles(); }
});

// trascina i file sulla finestra per importarli
document.addEventListener('dragover', (e) => e.preventDefault());
document.addEventListener('drop', (e) => { e.preventDefault(); if (e.dataTransfer.files.length) importFiles(Array.from(e.dataTransfer.files)); });

// ricerca luoghi (solo quando premi Invio: uso moderato del servizio)
$('searchForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const q = $('searchInput').value.trim();
  const ul = $('searchResults');
  ul.innerHTML = '';
  if (!q) return;
  try {
    const res = await Routing.search(q);
    if (!res.length) { ul.innerHTML = '<li>Nessun risultato</li>'; return; }
    for (const r of res) {
      const li = document.createElement('li');
      li.textContent = r.display_name;
      li.addEventListener('click', () => {
        ul.innerHTML = '';
        if (r.boundingbox) map.fitBounds([[+r.boundingbox[0], +r.boundingbox[2]], [+r.boundingbox[1], +r.boundingbox[3]]], { maxZoom: 15 });
        else map.setView([+r.lat, +r.lon], 14);
      });
      ul.appendChild(li);
    }
  } catch (err) { ul.innerHTML = '<li>Ricerca non disponibile: ' + err.message + '</li>'; }
});
document.addEventListener('click', (e) => { if (!e.target.closest('.search')) $('searchResults').innerHTML = ''; });

// avvio
$('disclaimerText').textContent = DISCLAIMER;
$('welcomeText').textContent = DISCLAIMER;
$('version').textContent = 'SirJoe MTB Planner · BY SIRJOE';
if (window.sirjoe && window.sirjoe.version) window.sirjoe.version().then((v) => { $('version').textContent = 'SirJoe MTB Planner ' + v + ' · BY SIRJOE'; });
if (!store.get('accepted', false)) {
  $('welcome').addEventListener('cancel', (e) => e.preventDefault());
  $('welcome').addEventListener('close', () => store.set('accepted', true));
  $('welcome').showModal();
}
render();
renderLibrary();


/* -------------------------------------------------------------- Mac */

// Sul Mac: scorciatoie con ⌘ e niente pulsante del firewall di Windows
(function adattaMac() {
  if (!(window.sirjoe && window.sirjoe.platform === 'darwin')) return;
  document.documentElement.classList.add('mac');
  for (const el of document.querySelectorAll('[title*="Ctrl+"]')) el.title = el.title.replace(/Ctrl\+/g, '⌘');
  const fw = $('linkFw');
  if (fw) fw.hidden = true;
  const hint = $('linkFwHint');
  if (hint) {
    hint.textContent = 'se non compare "SirJoe Link OK", sul Mac apri Impostazioni di Sistema → Rete → Firewall ' +
      'e consenti le connessioni in entrata a SirJoe MTB Planner (oppure rispondi "Consenti" alla domanda che compare al primo collegamento).';
  }
})();
