/* SirJoe MTB Planner - Uscite e Sala analisi
 * Uscite ricevute dal telefono (SirJoe Link) o aggiunte da file:
 * traccia colorata per velocità/pendenza/quota, replay animato, confronto con un percorso
 * pianificato, salite e video da pubblicare sui social.
 */
'use strict';

/* ---------------------------------------- archivio uscite (PC o browser) */
const RideStore = {
  desktop: !!(window.sirjoe && window.sirjoe.rides),
  async list() {
    if (this.desktop) return window.sirjoe.rides.list();
    return store.get('rides', []).map(({ file, name, mtime, size }) => ({ file, name, mtime, size }));
  },
  async read(file) {
    if (this.desktop) return window.sirjoe.rides.read(file);
    const r = store.get('rides', []).find((x) => x.file === file);
    return r ? r.text : '';
  },
  async remove(file) {
    if (this.desktop) return window.sirjoe.rides.remove(file);
    store.set('rides', store.get('rides', []).filter((x) => x.file !== file));
  },
  async add(name, text) {
    if (this.desktop) return window.sirjoe.rides.add(name, text);
    const file = name + '-' + Date.now() + '.gpx';
    store.set('rides', [{ file, name, mtime: Date.now(), size: text.length, text }, ...store.get('rides', [])]);
    return file;
  }
};

const Analysis = {
  active: false,
  name: '',
  points: [],
  cum: [],
  layer: L.layerGroup(),
  compareLayer: L.layerGroup(),
  marker: null,
  t0: 0,
  t1: 0,
  playing: false,
  tNow: 0,
  cursorDist: null,
  lastFrame: 0,

  /* ---------------------------------------------------------- elenco */
  async renderList() {
    const ul = $('rideList');
    let rides = [];
    try { rides = await RideStore.list(); } catch (e) { /* vuoto */ }
    if (!rides.length) { ul.innerHTML = '<li class="empty">Nessuna uscita: inviale dal telefono con SirJoe Link</li>'; return; }
    ul.innerHTML = '';
    for (const r of rides) {
      const li = document.createElement('li');
      li.innerHTML = `<span class="grow"><b></b><small>${new Date(r.mtime).toLocaleString('it-IT', { dateStyle: 'short', timeStyle: 'short' })}</small></span>
        <button class="btn danger" title="Elimina">✕</button>`;
      li.querySelector('b').textContent = r.name;
      li.addEventListener('click', async () => {
        try {
          const text = await RideStore.read(r.file);
          const parsed = Formats.parse(new TextEncoder().encode(text).buffer, r.file);
          Analysis.open(r.name, parsed.points);
        } catch (e) { toast('Uscita non leggibile: ' + e.message); }
      });
      li.querySelector('button').addEventListener('click', async (e) => {
        e.stopPropagation();
        if (!confirm('Eliminare l\'uscita "' + r.name + '"?')) return;
        await RideStore.remove(r.file);
        Analysis.renderList();
      });
      ul.appendChild(li);
    }
  },

  async addFiles(files) {
    for (const f of files) {
      try {
        const r = Formats.parse(await f.arrayBuffer(), f.name);
        if (r.points.length < 2) throw new Error('nessuna traccia');
        await RideStore.add(r.name, Formats.gpx(r.name, r.points, []));
        toast('Uscita aggiunta: ' + r.name);
      } catch (e) { toast(f.name + ': ' + e.message); }
    }
    Analysis.renderList();
  },

  /* ------------------------------------------------------ apertura */
  open(name, pts) {
    pts = pts.filter((p) => isFinite(p.lat) && isFinite(p.lon));
    if (pts.length < 2) return toast('Traccia troppo corta');
    this.stop();
    this.active = true;
    this.name = name;
    this.points = pts;
    this.cum = Geo.cumulative(pts);
    const hasTime = pts.every((p) => p.time);
    // senza orari (percorso pianificato) il replay usa 15 km/h
    this.times = hasTime ? pts.map((p) => p.time) : this.cum.map((d) => d / 4.17 * 1000);
    this.t0 = this.times[0];
    this.t1 = this.times[this.times.length - 1];
    this.tNow = this.t0;
    this.speeds = this.computeSpeeds();
    this.grades = Geo.grades(pts);
    this.hasTime = hasTime;

    $('ridesHome').hidden = true;
    $('analysis').hidden = false;
    $('anName').textContent = name;
    this.fillStats();
    this.fillCompare();
    this.fillClimbs();
    // il percorso in costruzione resta, ma passa in secondo piano
    routeLayer.eachLayer((l) => { if (l.setStyle) l.setStyle({ opacity: 0.25 }); });
    this.layer.addTo(map);
    this.compareLayer.addTo(map);
    this.draw();
    map.fitBounds(L.latLngBounds(pts.map((p) => [p.lat, p.lon])), { padding: [40, 40] });
    this.setTime(this.t0);
    selectTab('rides');
  },

  close() {
    this.stop();
    this.active = false;
    this.layer.clearLayers(); this.compareLayer.clearLayers();
    map.removeLayer(this.layer); map.removeLayer(this.compareLayer);
    this.marker = null;
    $('analysis').hidden = true;
    $('ridesHome').hidden = false;
    render();
  },

  computeSpeeds() {
    const p = this.points, t = this.times, c = this.cum;
    return p.map((_, i) => {
      const a = Math.max(0, i - 3), b = Math.min(p.length - 1, i + 3);
      const dt = (t[b] - t[a]) / 1000;
      return dt > 0 ? (c[b] - c[a]) / dt * 3.6 : 0;
    });
  },

  fillStats() {
    const s = Geo.summary(this.points);
    const total = (this.t1 - this.t0) / 3600000;
    // tempo in movimento: tratti sopra i 3 km/h
    let moving = 0;
    for (let i = 1; i < this.points.length; i++) {
      const dt = (this.times[i] - this.times[i - 1]) / 1000;
      const v = dt > 0 ? (this.cum[i] - this.cum[i - 1]) / dt * 3.6 : 0;
      if (v >= 3 && dt < 60) moving += dt;
    }
    const sorted = this.speeds.slice().sort((a, b) => a - b);
    const vmax = sorted[Math.floor(sorted.length * 0.98)] || 0;
    $('anDist').textContent = Fmt.km(s.dist);
    $('anTime').textContent = this.hasTime ? Fmt.dur(total) : '–';
    $('anMoving').textContent = this.hasTime ? Fmt.dur(moving / 3600) : '–';
    $('anAvg').textContent = this.hasTime && moving ? (s.dist / moving * 3.6).toFixed(1).replace('.', ',') : '–';
    $('anMax').textContent = this.hasTime ? vmax.toFixed(1).replace('.', ',') : '–';
    $('anUp').textContent = s.hasEle ? Fmt.m(s.up) : '–';
    if (!this.hasTime && $('anColor').value === 'speed') $('anColor').value = 'slope';
  },

  /* -------------------------------------------------- colorazione */
  colors() {
    const mode = $('anColor').value;
    const ramp = ['#1E88E5', '#26A69A', '#7CB342', '#FDD835', '#FB8C00', '#E53935'];
    if (mode === 'slope') return { colors: this.grades.map(slopeColor), legend: SLOPE_CLASSES.map((c) => c.color), labels: ['discesa', 'piano', 'salita ripida'] };
    const vals = mode === 'speed' ? this.speeds : this.points.map((p) => p.ele ?? 0);
    const sorted = vals.slice().sort((a, b) => a - b);
    const lo = sorted[Math.floor(sorted.length * 0.05)], hi = sorted[Math.floor(sorted.length * 0.95)] || lo + 1;
    const pick = (v) => ramp[Math.max(0, Math.min(ramp.length - 1, Math.floor(((v - lo) / ((hi - lo) || 1)) * ramp.length)))];
    const unit = mode === 'speed' ? ' km/h' : ' m';
    return { colors: vals.map(pick), legend: ramp, labels: [Math.round(lo) + unit, '', Math.round(hi) + unit] };
  },

  draw() {
    this.layer.clearLayers();
    const { colors, legend, labels } = this.colors();
    L.polyline(this.points.map((p) => [p.lat, p.lon]), { color: '#fff', weight: 8, opacity: 0.85, interactive: false }).addTo(this.layer);
    colorLine(this.points, colors, this.layer, 5);
    $('anLegend').innerHTML = legend.map((c) => `<span style="flex:1;background:${c}"></span>`).join('');
    let lab = document.querySelector('#analysis .legend-labels');
    if (!lab) { lab = document.createElement('div'); lab.className = 'legend-labels'; $('anLegend').after(lab); }
    lab.innerHTML = labels.map((t) => `<span>${t}</span>`).join('');
    this.marker = L.marker([this.points[0].lat, this.points[0].lon], {
      icon: L.divIcon({ className: '', html: '<div class="ride-dot"></div>', iconSize: [18, 18], iconAnchor: [9, 9] }),
      interactive: false, zIndexOffset: 1000
    }).addTo(this.layer);
    drawProfile();
  },

  /* ------------------------------------------------------- replay */
  indexAt(t) {
    const ts = this.times;
    let lo = 0, hi = ts.length - 1;
    if (t <= ts[0]) return 0;
    if (t >= ts[hi]) return hi;
    while (hi - lo > 1) { const m = (lo + hi) >> 1; if (ts[m] < t) lo = m; else hi = m; }
    return lo;
  },

  setTime(t) {
    this.tNow = Math.max(this.t0, Math.min(this.t1, t));
    const i = this.indexAt(this.tNow);
    const j = Math.min(i + 1, this.points.length - 1);
    const f = this.times[j] > this.times[i] ? (this.tNow - this.times[i]) / (this.times[j] - this.times[i]) : 0;
    const a = this.points[i], b = this.points[j];
    const lat = a.lat + (b.lat - a.lat) * f, lon = a.lon + (b.lon - a.lon) * f;
    if (this.marker) this.marker.setLatLng([lat, lon]);
    this.cursorDist = this.cum[i] + (this.cum[j] - this.cum[i]) * f;
    $('anSlider').value = Math.round(((this.tNow - this.t0) / ((this.t1 - this.t0) || 1)) * 1000);
    const el = (this.tNow - this.t0) / 3600000;
    const ele = a.ele != null ? ' · ' + Math.round(a.ele) + ' m' : '';
    $('anNow').textContent = `${Fmt.dur(el)} · ${Fmt.km(this.cursorDist)} km · ${this.speeds[i].toFixed(1).replace('.', ',')} km/h${ele}`;
    if (this.playing && !map.getBounds().pad(-0.15).contains([lat, lon])) map.panTo([lat, lon], { animate: false });
    drawProfile();
  },

  afterProfile(ctx, data, h) {
    if (this.cursorDist == null || !data) return;
    const x = data.X(Math.min(this.cursorDist, data.total));
    ctx.strokeStyle = '#ffffff'; ctx.lineWidth = 1.5;
    ctx.beginPath(); ctx.moveTo(x, 20); ctx.lineTo(x, h - 20); ctx.stroke();
  },

  play() {
    if (this.playing) return this.stop();
    if (this.tNow >= this.t1) this.tNow = this.t0;
    this.playing = true;
    $('anPlay').textContent = '❚❚';
    this.lastFrame = performance.now();
    const step = (now) => {
      if (!this.playing) return;
      const dt = now - this.lastFrame;
      this.lastFrame = now;
      this.setTime(this.tNow + dt * Number($('anSpeed').value));
      if (this.tNow >= this.t1) return this.stop();
      requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  },

  stop() {
    this.playing = false;
    $('anPlay').textContent = '▶';
  },

  /* ----------------------------------------------------- confronto */
  fillCompare() {
    const sel = $('anCompare');
    const lib = store.get('library', []);
    sel.innerHTML = '<option value="">Nessuno</option>' + lib.map((it) => `<option value="${it.id}"></option>`).join('');
    lib.forEach((it, k) => { sel.options[k + 1].textContent = it.name; });
    if (state.points.length >= 2) sel.insertAdjacentHTML('beforeend', '<option value="__current">Percorso aperto ora</option>');
    $('anCompareOut').textContent = '';
    this.compareLayer.clearLayers();
  },

  compare(id) {
    this.compareLayer.clearLayers();
    $('anCompareOut').textContent = '';
    if (!id) return;
    let plan = [];
    if (id === '__current') plan = allPoints();
    else {
      const it = store.get('library', []).find((x) => x.id === id);
      if (it) it.legs.forEach((l) => plan.push(...l));
    }
    if (plan.length < 2) return;
    L.polyline(plan.map((p) => [p.lat, p.lon]), { color: '#263238', weight: 4, dashArray: '8 8', opacity: 0.9, interactive: false }).addTo(this.compareLayer);
    // distanza di ogni punto dell'uscita dal percorso pianificato (proiezione locale in metri)
    const lat0 = plan[0].lat * Math.PI / 180;
    const xy = (p) => [p.lon * 111320 * Math.cos(lat0), p.lat * 110540];
    const P = plan.map(xy);
    const segDist = (q, a, b) => {
      const dx = b[0] - a[0], dy = b[1] - a[1];
      const l2 = dx * dx + dy * dy || 1;
      const t = Math.max(0, Math.min(1, ((q[0] - a[0]) * dx + (q[1] - a[1]) * dy) / l2));
      return Math.hypot(q[0] - a[0] - t * dx, q[1] - a[1] - t * dy);
    };
    const off = this.points.map((p) => {
      const q = xy(p);
      let best = Infinity;
      for (let k = 1; k < P.length; k++) {
        const d = segDist(q, P[k - 1], P[k]);
        if (d < best) best = d;
      }
      return best > 50;
    });
    let offM = 0, groups = 0;
    for (let i = 1; i < off.length; i++) {
      if (off[i]) offM += this.cum[i] - this.cum[i - 1];
      if (off[i] && !off[i - 1]) groups++;
    }
    colorLine(this.points, off.map((o) => (o ? '#D500F9' : 'transparent')), this.compareLayer, 9);
    const tot = this.cum[this.cum.length - 1] || 1;
    $('anCompareOut').innerHTML = groups
      ? `Sul percorso il <b>${Math.round((1 - offM / tot) * 100)}%</b> del giro · ${groups} deviazion${groups === 1 ? 'e' : 'i'} per ${Fmt.km(offM)} km (in viola sulla mappa)`
      : 'Hai seguito il percorso pianificato per tutto il giro 👍';
  },

  fillClimbs() {
    const box = $('anClimbs');
    const climbs = Geo.climbs(this.points);
    box.innerHTML = climbs.length ? '' : '<div class="small">Nessuna salita impegnativa</div>';
    for (const c of climbs) {
      const d = document.createElement('div');
      d.className = 'climb';
      let extra = '';
      if (this.hasTime) {
        const ia = this.cum.findIndex((x) => x >= c.start), ib = this.cum.findIndex((x) => x >= c.end);
        if (ia >= 0 && ib > ia) {
          const sec = (this.times[ib] - this.times[ia]) / 1000;
          extra = ` · ${Math.floor(sec / 60)}'${String(Math.round(sec % 60)).padStart(2, '0')}" · ${Math.round(c.gain / sec * 3600)} m/h`;
        }
      }
      d.innerHTML = climbHtml(c) + (extra ? `<span class="small">${extra}</span>` : '');
      d.addEventListener('click', () => {
        const ia = this.cum.findIndex((x) => x >= c.start);
        if (ia >= 0) this.setTime(this.times[ia]);
      });
      box.appendChild(d);
    }
  },

  /**
   * Mappa di sfondo per il video: riquadri OpenTopoMap (o OSM) allo zoom che contiene il giro,
   * scaricati una volta sola (pochi riquadri, come una normale visualizzazione) e uniti in un'immagine.
   * k, ox, oy: la stessa proiezione usata per disegnare il percorso.
   */
  async mapBackground(MAP, k, ox, oy) {
    if (!(window.sirjoe && window.sirjoe.fetchDataUrl)) return null;
    const useOsm = store.get('base', 'topo') === 'osm';
    // zoom un po' più alto del necessario: riquadri rimpiccioliti, quindi nitidi
    const maxZ = useOsm ? 19 : 17;
    const plan = (z) => {
      const n = 2 ** z, ts = k / n; // lato di un riquadro in pixel del video
      const tx0 = Math.floor((MAP.x - ox) / ts), tx1 = Math.floor((MAP.x + MAP.w - ox) / ts);
      const ty0 = Math.max(0, Math.floor((MAP.y - oy) / ts)), ty1 = Math.min(n - 1, Math.floor((MAP.y + MAP.h - oy) / ts));
      const jobs = [];
      for (let ty = ty0; ty <= ty1; ty++) for (let tx = tx0; tx <= tx1; tx++) jobs.push([tx, ty]);
      return { z, n, ts, jobs };
    };
    const zf = Math.log2(k / 256);
    let pl = plan(Math.max(1, Math.min(maxZ, Math.ceil(zf))));
    if (pl.jobs.length > 60) pl = plan(Math.max(1, Math.min(maxZ, Math.floor(zf))));
    const { z, n, ts, jobs } = pl;
    if (!jobs.length || jobs.length > 60) return null;
    const cv = document.createElement('canvas');
    cv.width = MAP.w; cv.height = MAP.h;
    const c = cv.getContext('2d');
    c.fillStyle = '#dfe6d8'; c.fillRect(0, 0, MAP.w, MAP.h);
    let ok = 0, i = 0;
    const worker = async () => {
      while (i < jobs.length) {
        const [tx, ty] = jobs[i++];
        const x = ((tx % n) + n) % n;
        const sub = 'abc'[(tx + ty) % 3];
        const url = useOsm ? `https://tile.openstreetmap.org/${z}/${x}/${ty}.png` : `https://${sub}.tile.opentopomap.org/${z}/${x}/${ty}.png`;
        try {
          const img = new Image();
          img.src = await window.sirjoe.fetchDataUrl(url);
          await img.decode();
          c.drawImage(img, tx * ts + ox - MAP.x, ty * ts + oy - MAP.y, ts + 0.5, ts + 0.5);
          ok++;
        } catch (e) { /* riquadro mancante: resta lo sfondo */ }
      }
    };
    await Promise.all([worker(), worker(), worker(), worker()]);
    if (!ok) return null;
    // sfumature in alto e in basso per staccare titolo e numeri
    let g = c.createLinearGradient(0, 0, 0, 90);
    g.addColorStop(0, 'rgba(20,38,28,.9)'); g.addColorStop(1, 'rgba(20,38,28,0)');
    c.fillStyle = g; c.fillRect(0, 0, MAP.w, 90);
    g = c.createLinearGradient(0, MAP.h - 90, 0, MAP.h);
    g.addColorStop(0, 'rgba(12,26,18,0)'); g.addColorStop(1, 'rgba(12,26,18,1)');
    c.fillStyle = g; c.fillRect(0, MAP.h - 90, MAP.w, 90);
    c.font = '600 18px "Segoe UI", Arial, sans-serif'; c.fillStyle = 'rgba(255,255,255,.85)'; c.textAlign = 'right';
    c.fillText(useOsm ? '© OpenStreetMap contributors' : '© OpenStreetMap contributors, OpenTopoMap (CC-BY-SA)', MAP.w - 16, MAP.h - 14);
    return cv;
  },

  /* --------------------------------------------------------- video */
  async video() {
    if (this.recording) return;
    const W = 1080, H = 1350, FPS = 30, SECONDS = 20;
    const types = ['video/mp4;codecs=avc1.42E01E', 'video/mp4', 'video/webm;codecs=vp9', 'video/webm'];
    const mime = types.find((t) => window.MediaRecorder && MediaRecorder.isTypeSupported(t));
    if (!mime) return toast('Questo sistema non permette di creare video');
    const canvas = document.createElement('canvas');
    canvas.width = W; canvas.height = H;
    const ctx = canvas.getContext('2d');
    const logo = new Image();
    logo.src = 'logo.png';
    await new Promise((r) => { logo.onload = r; logo.onerror = r; });

    // proiezione di Mercatore adattata al riquadro
    const mx = (lon) => lon / 360 + 0.5;
    const my = (lat) => 0.5 - Math.log(Math.tan(Math.PI / 4 + (Math.max(-85, Math.min(85, lat)) * Math.PI) / 360)) / (2 * Math.PI);
    const box = { x: 110, y: 250, w: W - 220, h: 660 };
    const MAP = { x: 0, y: 180, w: W, h: 820 }; // riquadro della mappa di sfondo
    const xs = this.points.map((p) => mx(p.lon)), ys = this.points.map((p) => my(p.lat));
    const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
    const k = Math.min(box.w / ((maxX - minX) || 1e-9), box.h / ((maxY - minY) || 1e-9));
    const ox = box.x + box.w / 2 - ((minX + maxX) / 2) * k, oy = box.y + box.h / 2 - ((minY + maxY) / 2) * k;
    const P = this.points.map((p, i) => [xs[i] * k + ox, ys[i] * k + oy]);
    const { colors } = this.colors();
    const ele = this.points.map((p) => p.ele);
    const hasEle = ele.every((e) => e != null);
    const eMin = hasEle ? Math.min(...ele) : 0, eMax = hasEle ? Math.max(...ele) : 1;
    const total = this.cum[this.cum.length - 1];
    const sum = Geo.summary(this.points);
    const font = (px, w = 700) => `${w} ${px}px "Segoe UI", Arial, sans-serif`;
    $('anVideoState').textContent = 'Scarico la mappa…';
    const mapBg = await this.mapBackground(MAP, k, ox, oy);

    const frame = (prog) => {
      const g = ctx.createLinearGradient(0, 0, 0, H);
      g.addColorStop(0, '#22442F'); g.addColorStop(1, '#0C1A12');
      ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
      ctx.fillStyle = '#fff'; ctx.font = font(54);
      ctx.fillText(this.name.length > 30 ? this.name.slice(0, 29) + '…' : this.name, 60, 110);
      if (this.hasTime) {
        ctx.fillStyle = '#B0C4B8'; ctx.font = font(34, 400);
        ctx.fillText(new Date(this.t0).toLocaleDateString('it-IT', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }), 60, 160);
      }
      // mappa di sfondo con il percorso
      if (mapBg) {
        ctx.drawImage(mapBg, MAP.x, MAP.y);
      }
      // percorso intero tenue + parte già percorsa colorata
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      ctx.strokeStyle = mapBg ? 'rgba(20,38,28,.55)' : 'rgba(255,255,255,.22)'; ctx.lineWidth = mapBg ? 14 : 10;
      ctx.beginPath(); P.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y))); ctx.stroke();
      ctx.strokeStyle = 'rgba(255,255,255,.75)'; ctx.lineWidth = mapBg ? 8 : 10;
      ctx.beginPath(); P.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y))); ctx.stroke();
      const n = Math.max(1, Math.floor(prog * (P.length - 1)));
      ctx.lineWidth = 12;
      for (let i = 1; i <= n; i++) {
        ctx.strokeStyle = colors[i - 1];
        ctx.beginPath(); ctx.moveTo(P[i - 1][0], P[i - 1][1]); ctx.lineTo(P[i][0], P[i][1]); ctx.stroke();
      }
      const [cx, cy] = P[n];
      ctx.fillStyle = '#FF6D00'; ctx.strokeStyle = '#fff'; ctx.lineWidth = 6;
      ctx.beginPath(); ctx.arc(cx, cy, 20, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
      // numeri che crescono
      const d = this.cum[n];
      const t = (this.times[n] - this.t0) / 3600000;
      const stats = [[Fmt.km(d), 'km'], [hasEle ? Fmt.m(Math.max(0, ele[n])) : '–', 'm quota']];
      if (this.hasTime) stats.push([Fmt.dur(t), 'tempo'], [this.speeds[n].toFixed(0), 'km/h']);
      const col = (W - 80) / stats.length;
      stats.forEach(([v, l], i) => {
        const x = 40 + col * i + col / 2;
        ctx.textAlign = 'center';
        ctx.fillStyle = '#fff'; ctx.font = font(64); ctx.fillText(v, x, 1035);
        ctx.fillStyle = '#FFB074'; ctx.font = font(30, 400); ctx.fillText(l, x, 1080);
      });
      ctx.textAlign = 'left';
      // mini profilo altimetrico
      if (hasEle) {
        const px = 80, pw = W - 160, py = 1110, ph = 70;
        ctx.fillStyle = 'rgba(255,255,255,.12)';
        ctx.beginPath(); ctx.moveTo(px, py + ph);
        this.points.forEach((p, i) => ctx.lineTo(px + (this.cum[i] / total) * pw, py + ph - ((p.ele - eMin) / ((eMax - eMin) || 1)) * ph));
        ctx.lineTo(px + pw, py + ph); ctx.fill();
        ctx.strokeStyle = '#FF6D00'; ctx.lineWidth = 3;
        const xx = px + (d / total) * pw;
        ctx.beginPath(); ctx.moveTo(xx, py); ctx.lineTo(xx, py + ph); ctx.stroke();
      }
      if (logo.naturalWidth) {
        const lw = 420, lh = lw * logo.naturalHeight / logo.naturalWidth;
        ctx.drawImage(logo, (W - lw) / 2, H - lh - 30, lw, lh);
      }
      if (prog >= 1) {
        ctx.fillStyle = 'rgba(0,0,0,.35)'; ctx.fillRect(0, 175, W, 40);
        ctx.fillStyle = '#fff'; ctx.font = font(30, 600); ctx.textAlign = 'center';
        ctx.fillText(`${Fmt.km(sum.dist)} km${sum.hasEle ? ' · ↗ ' + Fmt.m(sum.up) + ' m' : ''}`, W / 2, 205);
        ctx.textAlign = 'left';
      }
    };

    this.recording = true;
    $('anVideo').disabled = true;
    const stream = canvas.captureStream(FPS);
    const rec = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: 8_000_000 });
    const chunks = [];
    rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data); };
    const done = new Promise((r) => { rec.onstop = r; });
    rec.start(500);
    const start = performance.now();
    const totalMs = SECONDS * 1000;
    await new Promise((resolve) => {
      const tick = () => {
        const el = performance.now() - start;
        // 85% del tempo per percorrere il giro, poi pausa sul risultato finale
        frame(Math.min(1, el / (totalMs * 0.85)));
        $('anVideoState').textContent = `Creo il video… ${Math.min(100, Math.round((el / totalMs) * 100))}%`;
        if (el >= totalMs) return resolve();
        requestAnimationFrame(tick);
      };
      tick();
    });
    rec.stop();
    await done;
    const blob = new Blob(chunks, { type: mime.split(';')[0] });
    const ext = mime.startsWith('video/mp4') ? 'mp4' : 'webm';
    const ok = await download(this.name + ' – video.' + ext, new Uint8Array(await blob.arrayBuffer()), blob.type);
    $('anVideoState').textContent = ok ? `Video ${ext.toUpperCase()} salvato.` + (ext === 'webm' ? ' (Formato WebM: per Instagram convertilo in MP4.)' : '') : '';
    $('anVideo').disabled = false;
    this.recording = false;
  }
};
window.Analysis = Analysis;

/* ---------------------------------------------------------- collegamenti */
$('anColor').addEventListener('change', () => { if (Analysis.active) { Analysis.draw(); Analysis.setTime(Analysis.tNow); } });
$('anPlay').addEventListener('click', () => Analysis.play());
$('anSlider').addEventListener('input', (e) => { Analysis.stop(); Analysis.setTime(Analysis.t0 + (e.target.value / 1000) * (Analysis.t1 - Analysis.t0)); });
$('anCompare').addEventListener('change', (e) => Analysis.compare(e.target.value));
$('anVideo').addEventListener('click', () => Analysis.video().catch((e) => { toast('Video non riuscito: ' + e.message); $('anVideo').disabled = false; Analysis.recording = false; }));
$('closeAnalysis').addEventListener('click', () => Analysis.close());
$('addRide').addEventListener('click', () => $('rideFile').click());
$('rideFile').addEventListener('change', (e) => { Analysis.addFiles(Array.from(e.target.files)); e.target.value = ''; });
$('ridesFolder').addEventListener('click', () => {
  if (window.sirjoe && window.sirjoe.rides) window.sirjoe.rides.folder();
  else toast('Disponibile nel programma per Windows');
});
document.querySelector('.tab[data-tab=rides]').addEventListener('click', () => { if (!Analysis.active) Analysis.renderList(); });
Analysis.renderList();
