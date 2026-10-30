/* SirJoe MTB Planner - scheda Training
 * Programmi di allenamento per l'app Android SirJoe Training (fascia cardio e cadenza),
 * profilo con FC max e zone, allenamenti ricevuti dal telefono con grafico di battito e cadenza.
 * Il formato dei programmi è lo stesso dell'app:
 *   { id, name, note, steps: [{ label, sec, zLo, zHi, cLo, cHi, tip }] }
 */
'use strict';

/* ------------------------------------------------------------ zone cardio (come nell'app) */
const HrZones = {
  list: [
    { n: 1, name: 'Recupero', lo: 50, hi: 60, color: '#78909c' },
    { n: 2, name: 'Fondo lento', lo: 60, hi: 70, color: '#43a047' },
    { n: 3, name: 'Fondo medio', lo: 70, hi: 80, color: '#fdd835' },
    { n: 4, name: 'Soglia', lo: 80, hi: 90, color: '#fb8c00' },
    { n: 5, name: 'Massimale', lo: 90, hi: 100, color: '#e53935' }
  ],
  /** formula di Tanaka: 208 - 0,7 × età */
  estimate: (age) => Math.round(208 - 0.7 * age),
  lo: (n, max) => Math.round(max * HrZones.list[n - 1].lo / 100),
  hi: (n, max) => Math.round(max * HrZones.list[n - 1].hi / 100),
  of(bpm, max) {
    const p = bpm * 100 / max;
    return p < 60 ? 1 : p < 70 ? 2 : p < 80 ? 3 : p < 90 ? 4 : 5;
  }
};

const mmss = (sec) => {
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = Math.round(sec % 60);
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
};

/* ------------------------------------------------------------ esempi pronti (uguali all'app) */
const TR_TEMPLATES = (() => {
  const st = (label, min, zLo, zHi, cLo = 0, cHi = 0, tip = '') => ({ label, sec: min * 60, zLo, zHi, cLo, cHi, tip });
  return [
    { name: 'Fondo lento 45\'', note: 'Uscita tranquilla per costruire resistenza: quasi tutta in zona 2.',
      steps: [st('Riscaldamento', 10, 1, 2, 80, 95, 'Rapporto agile, gambe sciolte.'), st('Fondo lento', 30, 2, 2, 80, 95, 'Ritmo da chiacchierata, anche in salita.'), st('Defaticamento', 5, 1, 1, 75, 95, 'Rallenta e lascia scendere il battito.')] },
    { name: 'Agilità 40\'', note: 'Allena a pedalare rotondo con cadenza alta e battito basso.',
      steps: [st('Riscaldamento', 10, 1, 2, 80, 90), st('Agile', 3, 2, 2, 95, 105, 'Rapporto leggero, pedala veloce senza saltare sulla sella.'), st('Normale', 2, 1, 2, 80, 90),
        st('Agile', 3, 2, 2, 95, 105), st('Normale', 2, 1, 2, 80, 90), st('Agile', 3, 2, 2, 95, 105), st('Normale', 2, 1, 2, 80, 90), st('Fondo', 10, 2, 2, 80, 95), st('Defaticamento', 5, 1, 1, 75, 95)] },
    { name: 'Salite in zona 3 (50\')', note: 'Tre tratti sostenuti in zona 3 con recupero: ideale su salite regolari.',
      steps: [st('Riscaldamento', 12, 1, 2, 80, 95), st('Salita sostenuta', 6, 3, 3, 70, 85, 'Ritmo costante, seduto, rapporto che non ti fa piantare.'), st('Recupero', 4, 1, 2, 80, 95, 'Scendi di rapporto e respira.'),
        st('Salita sostenuta', 6, 3, 3, 70, 85), st('Recupero', 4, 1, 2, 80, 95), st('Salita sostenuta', 6, 3, 3, 70, 85), st('Defaticamento', 12, 1, 2, 75, 95)] },
    { name: 'Recupero attivo 30\'', note: 'Dopo un\'uscita impegnativa: tutto in zona 1, gambe che girano.',
      steps: [st('Recupero attivo', 30, 1, 1, 80, 95, 'Nessuno sforzo: se il battito sale, rallenta.')] }
  ];
})();

/* ------------------------------------------------------------ archivio allenamenti (PC o browser) */
const SessionStore = {
  desktop: !!(window.sirjoe && window.sirjoe.sessions),
  async list() { return this.desktop ? window.sirjoe.sessions.list() : []; },
  async read(file) { return window.sirjoe.sessions.read(file); },
  async remove(file) { return window.sirjoe.sessions.remove(file); }
};

const Training = {
  draft: null,
  editingId: null,
  session: null,
  sessionFile: null,

  workouts() { return store.get('trWorkouts', []); },
  saveWorkouts(list) { store.set('trWorkouts', list); },
  profile() { return store.get('trProfile', { age: 0, maxHr: 0 }); },
  maxHr() {
    const p = this.profile();
    if (p.maxHr >= 100 && p.maxHr <= 230) return p.maxHr;
    if (p.age >= 10 && p.age <= 100) return HrZones.estimate(p.age);
    return 160;
  },

  /** quello che il telefono scarica con SirJoe Link */
  payload() {
    const p = this.profile();
    return { workouts: this.workouts(), maxHr: p.maxHr || 0, age: p.age || 0 };
  },

  total(w) { return (w.steps || []).reduce((a, s) => a + (Number(s.sec) || 0), 0); },

  target(s, max) {
    const parts = [];
    if (s.zLo > 0) {
      const a = HrZones.lo(s.zLo, max), b = HrZones.hi(Math.max(s.zLo, s.zHi || s.zLo), max);
      parts.push((s.zLo === (s.zHi || s.zLo) ? `Z${s.zLo}` : `Z${s.zLo}-${s.zHi}`) + ` ${a}–${b} bpm`);
    }
    if (s.cLo > 0) parts.push(`cad. ${s.cLo}–${s.cHi || s.cLo}`);
    return parts.join(' · ') || 'libero';
  },

  /* ---------------------------------------------------------- profilo e zone */
  renderProfile() {
    const p = this.profile();
    $('trAge').value = p.age || '';
    $('trMax').value = p.maxHr || '';
    const max = this.maxHr();
    $('trMaxInfo').textContent = (p.maxHr ? 'FC max impostata: ' : p.age ? 'FC max stimata dall\'età: ' : 'FC max non impostata, uso ') + max +
      (p.maxHr ? '' : ' (meglio quella misurata: test da sforzo o valore più alto visto col Garmin)');
    $('trZones').innerHTML = HrZones.list.map((z) =>
      `<div class="zone" style="background:${z.color};color:${z.n === 3 ? '#000' : '#fff'}"><b>Z${z.n}</b> ${z.name}<span>${HrZones.lo(z.n, max)}–${HrZones.hi(z.n, max)}</span></div>`).join('');
  },

  saveProfile() {
    const age = parseInt($('trAge').value, 10) || 0;
    const maxHr = parseInt($('trMax').value, 10) || 0;
    store.set('trProfile', { age: age >= 10 && age <= 100 ? age : 0, maxHr: maxHr >= 100 && maxHr <= 230 ? maxHr : 0 });
    this.renderProfile();
    this.renderList();
  },

  /* ---------------------------------------------------------- elenco programmi */
  renderList() {
    const ul = $('trList');
    const list = this.workouts();
    if (!list.length) { ul.innerHTML = '<li class="empty">Nessun programma: creane uno o parti da un esempio</li>'; return; }
    ul.innerHTML = '';
    for (const w of list) {
      const li = document.createElement('li');
      li.innerHTML = `<span class="grow"><b></b><small></small></span>
        <button class="btn" title="Duplica">⧉</button><button class="btn danger" title="Elimina">✕</button>`;
      li.querySelector('b').textContent = w.name;
      li.querySelector('small').textContent = `${Math.round(this.total(w) / 60)} min · ${w.steps.length} fasi` + (w.note ? ' · ' + w.note : '');
      const [dup, del] = li.querySelectorAll('button');
      li.addEventListener('click', () => this.edit(w.id));
      dup.addEventListener('click', (e) => {
        e.stopPropagation();
        const copy = JSON.parse(JSON.stringify(w));
        copy.id = 'w' + Date.now();
        copy.name = w.name + ' (copia)';
        this.saveWorkouts([...this.workouts(), copy]);
        this.renderList();
      });
      del.addEventListener('click', (e) => {
        e.stopPropagation();
        if (!confirm('Eliminare il programma "' + w.name + '"?')) return;
        this.saveWorkouts(this.workouts().filter((x) => x.id !== w.id));
        this.renderList();
      });
      ul.appendChild(li);
    }
  },

  addTemplates() {
    const have = new Set(this.workouts().map((w) => w.name));
    const add = TR_TEMPLATES.filter((t) => !have.has(t.name)).map((t, i) => ({ ...JSON.parse(JSON.stringify(t)), id: 'w' + Date.now() + i }));
    if (!add.length) return toast('Gli esempi sono già nell\'elenco');
    this.saveWorkouts([...this.workouts(), ...add]);
    this.renderList();
    toast(add.length + ' esempi aggiunti: modificali come vuoi');
  },

  /* ---------------------------------------------------------- editor */
  edit(id) {
    const w = id ? this.workouts().find((x) => x.id === id) : null;
    this.editingId = w ? w.id : null;
    this.draft = w ? JSON.parse(JSON.stringify(w)) : {
      id: 'w' + Date.now(), name: '', note: '',
      steps: [{ label: 'Riscaldamento', sec: 600, zLo: 1, zHi: 2, cLo: 80, cHi: 95, tip: '' }]
    };
    $('woName').value = this.draft.name;
    $('woNote').value = this.draft.note || '';
    $('woDelete').hidden = !w;
    $('woRepeatBox').hidden = true;
    this.renderSteps();
    $('woDialog').returnValue = '';
    $('woDialog').showModal();
  },

  renderSteps() {
    const tb = $('woSteps');
    tb.innerHTML = '';
    const zoneOpts = (v) => [0, 1, 2, 3, 4, 5].map((n) => `<option value="${n}"${n === v ? ' selected' : ''}>${n || '—'}</option>`).join('');
    this.draft.steps.forEach((s, i) => {
      const tr = document.createElement('tr');
      tr.innerHTML = `<td>${i + 1}</td>
        <td><input data-k="label" value="${esc(s.label)}"></td>
        <td><input data-k="min" type="number" min="0" max="360" value="${Math.floor(s.sec / 60)}" class="num"></td>
        <td><input data-k="s" type="number" min="0" max="59" step="5" value="${s.sec % 60}" class="num"></td>
        <td class="pair"><select data-k="zLo">${zoneOpts(s.zLo)}</select><select data-k="zHi">${zoneOpts(s.zHi)}</select></td>
        <td class="pair"><input data-k="cLo" type="number" min="0" max="200" value="${s.cLo || 0}" class="num"><input data-k="cHi" type="number" min="0" max="200" value="${s.cHi || 0}" class="num"></td>
        <td><input data-k="tip" value="${esc(s.tip || '')}" placeholder="es. rapporto agile"></td>
        <td class="acts"><button type="button" class="btn" data-a="up" title="Su">↑</button><button type="button" class="btn" data-a="down" title="Giù">↓</button><button type="button" class="btn danger" data-a="del" title="Elimina fase">✕</button></td>`;
      tr.querySelectorAll('input,select').forEach((el) => el.addEventListener('input', () => {
        const k = el.dataset.k;
        if (k === 'label' || k === 'tip') s[k] = el.value;
        else if (k === 'min' || k === 's') {
          const m = parseInt(tr.querySelector('[data-k=min]').value, 10) || 0;
          const sec = parseInt(tr.querySelector('[data-k=s]').value, 10) || 0;
          s.sec = Math.max(10, m * 60 + Math.min(59, sec));
        } else {
          s[k] = parseInt(el.value, 10) || 0;
          // zona "a" mai sotto la zona "da"; cadenza idem
          if (k === 'zLo' && s.zHi < s.zLo) { s.zHi = s.zLo; tr.querySelector('[data-k=zHi]').value = String(s.zHi); }
          if (k === 'zHi' && s.zHi && s.zHi < s.zLo) { s.zLo = s.zHi; tr.querySelector('[data-k=zLo]').value = String(s.zLo); }
          if (k === 'zHi' && !s.zHi) { s.zLo = 0; tr.querySelector('[data-k=zLo]').value = '0'; }
          if (k === 'zLo' && !s.zLo) { s.zHi = 0; tr.querySelector('[data-k=zHi]').value = '0'; }
        }
        this.updateTotal();
      }));
      tr.querySelectorAll('[data-a]').forEach((b) => b.addEventListener('click', () => {
        const st = this.draft.steps;
        if (b.dataset.a === 'del') { if (st.length > 1) st.splice(i, 1); }
        else if (b.dataset.a === 'up' && i > 0) [st[i - 1], st[i]] = [st[i], st[i - 1]];
        else if (b.dataset.a === 'down' && i < st.length - 1) [st[i + 1], st[i]] = [st[i], st[i + 1]];
        this.renderSteps();
      }));
      tb.appendChild(tr);
    });
    this.updateTotal();
  },

  updateTotal() {
    $('woTotal').value = mmss(this.total(this.draft)) + ' (' + this.draft.steps.length + ' fasi)';
  },

  repeat() {
    const n = parseInt($('woRepN').value, 10) || 0;
    const times = parseInt($('woRepT').value, 10) || 0;
    const st = this.draft.steps;
    if (n < 1 || n > st.length || times < 1 || times > 20) return toast('Valori non validi');
    const block = st.slice(st.length - n);
    for (let t = 0; t < times; t++) st.push(...JSON.parse(JSON.stringify(block)));
    $('woRepeatBox').hidden = true;
    this.renderSteps();
  },

  closeEditor(v) {
    if (v === 'save') {
      const d = this.draft;
      d.name = $('woName').value.trim() || 'Programma';
      d.note = $('woNote').value.trim();
      d.steps = d.steps.map((s) => ({
        label: String(s.label || 'Fase').trim() || 'Fase', sec: Math.max(10, Number(s.sec) || 60),
        zLo: s.zLo || 0, zHi: s.zLo ? Math.max(s.zLo, s.zHi || s.zLo) : 0,
        cLo: s.cLo || 0, cHi: s.cLo ? Math.max(s.cLo, s.cHi || s.cLo) : 0, tip: String(s.tip || '').trim()
      }));
      const list = this.workouts();
      const i = list.findIndex((x) => x.id === d.id);
      if (i >= 0) list[i] = d; else list.push(d);
      this.saveWorkouts(list);
      toast('Programma salvato: invialo al telefono con 📲 SirJoe Link');
    } else if (v === 'delete' && this.editingId) {
      if (confirm('Eliminare il programma?')) this.saveWorkouts(this.workouts().filter((x) => x.id !== this.editingId));
    }
    this.draft = null;
    this.renderList();
  },

  /* ---------------------------------------------------------- allenamenti ricevuti */
  async renderSessions() {
    const ul = $('trSessions');
    let list = [];
    try { list = await SessionStore.list(); } catch (e) { /* vuoto */ }
    if (!SessionStore.desktop) { ul.innerHTML = '<li class="empty">Gli allenamenti arrivano nel programma per computer</li>'; return; }
    if (!list.length) { ul.innerHTML = '<li class="empty">Nessun allenamento: inviali dal telefono (SirJoe Training → Uscite)</li>'; return; }
    ul.innerHTML = '';
    for (const r of list) {
      const li = document.createElement('li');
      li.className = 'ses';
      const tot = (r.zoneSeconds || []).reduce((a, b) => a + b, 0);
      const bar = tot ? '<div class="zbar">' + (r.zoneSeconds || []).map((v, i) => v ? `<i style="flex:${v};background:${HrZones.list[i].color}"></i>` : '').join('') + '</div>' : '';
      li.innerHTML = `<span class="grow"><b></b><small></small>${bar}</span>`;
      li.querySelector('b').textContent = r.name;
      li.querySelector('small').textContent = new Date(r.start).toLocaleString('it-IT', { dateStyle: 'short', timeStyle: 'short' }) +
        ' · ' + mmss(r.seconds) + (r.distanceM ? ' · ' + Fmt.km(r.distanceM) + ' km' : '') + (r.avgHr ? ' · ' + r.avgHr + ' bpm' : '');
      li.addEventListener('click', () => this.openSession(r.file));
      ul.appendChild(li);
    }
  },

  async openSession(file) {
    let j;
    try { j = await SessionStore.read(file); } catch (e) { return toast('Allenamento non leggibile: ' + e.message); }
    this.session = j;
    this.sessionFile = file;
    $('sesName').textContent = j.name || 'Allenamento';
    $('sesWhen').textContent = new Date(j.start).toLocaleString('it-IT', { dateStyle: 'full', timeStyle: 'short' }) +
      (j.workout ? ' · programma: ' + j.workout : ' · uscita libera') + ' · FC max ' + (j.maxHr || '?');
    $('sesTime').textContent = mmss(j.seconds || 0);
    $('sesDist').textContent = Fmt.km(j.distanceM || 0);
    $('sesAvgHr').textContent = j.avgHr || '--';
    $('sesMaxHr').textContent = j.maxHrSeen || '--';
    $('sesCad').textContent = j.avgCad || '--';
    $('sesSpeed').textContent = j.seconds ? ((j.distanceM || 0) / j.seconds * 3.6).toFixed(1).replace('.', ',') : '--';
    const zs = j.zoneSeconds || [];
    const tot = zs.reduce((a, b) => a + b, 0) || 1;
    $('sesZones').innerHTML = HrZones.list.map((z, i) => {
      const v = zs[i] || 0;
      const pct = Math.round(v * 100 / tot);
      return `<div class="zrow"><span>Z${z.n} ${z.name}</span><div class="track"><i style="width:${pct}%;background:${z.color}"></i></div><b>${mmss(v)}</b><small>${pct}%</small></div>`;
    }).join('');
    $('sesDialog').returnValue = '';
    $('sesDialog').showModal();
    this.drawChart();
  },

  drawChart() {
    const j = this.session;
    const cv = $('sesChart');
    const ctx = cv.getContext('2d');
    const W = cv.width, H = cv.height, padL = 40, padR = 40, padT = 10, padB = 24;
    ctx.clearRect(0, 0, W, H);
    const rows = (j.samples || []).filter((r) => Array.isArray(r));
    if (rows.length < 2) { ctx.fillStyle = '#a9bfb2'; ctx.fillText('Nessun dato da mostrare', padL, H / 2); return; }
    const t0 = rows[0][0], t1 = rows[rows.length - 1][0];
    const max = j.maxHr || 160;
    const hrMin = Math.round(max * 0.4), hrMax = max + 5;
    const x = (t) => padL + (t - t0) / Math.max(1, t1 - t0) * (W - padL - padR);
    const yHr = (v) => padT + (1 - (v - hrMin) / (hrMax - hrMin)) * (H - padT - padB);
    const yCad = (v) => padT + (1 - v / 140) * (H - padT - padB);
    // fasce delle zone
    for (const z of HrZones.list) {
      const top = yHr(Math.min(hrMax, HrZones.hi(z.n, max))), bot = yHr(Math.max(hrMin, HrZones.lo(z.n, max)));
      ctx.fillStyle = z.color + '33';
      ctx.fillRect(padL, top, W - padL - padR, bot - top);
    }
    // assi
    ctx.fillStyle = '#a9bfb2';
    ctx.font = '11px Segoe UI, sans-serif';
    ctx.textAlign = 'right';
    for (const v of [HrZones.lo(1, max), HrZones.lo(3, max), HrZones.lo(5, max), max]) ctx.fillText(v, padL - 4, yHr(v) + 4);
    ctx.textAlign = 'left';
    for (const v of [60, 90, 120]) ctx.fillText(v, W - padR + 4, yCad(v) + 4);
    ctx.textAlign = 'center';
    const mins = (t1 - t0) / 60000;
    const step = mins > 120 ? 30 : mins > 60 ? 15 : mins > 20 ? 5 : 1;
    for (let m = 0; m <= mins; m += step) ctx.fillText(m + '\'', x(t0 + m * 60000), H - 6);
    // linee: cadenza (blu) sotto, battito (rosso) sopra
    const line = (col, color, y) => {
      ctx.strokeStyle = color; ctx.lineWidth = 1.6; ctx.beginPath();
      let on = false;
      for (const r of rows) {
        const v = r[col];
        if (v == null || (col === 2 && v === 0)) { on = false; continue; }
        if (on) ctx.lineTo(x(r[0]), y(v)); else { ctx.moveTo(x(r[0]), y(v)); on = true; }
      }
      ctx.stroke();
    };
    line(2, '#42a5f5', yCad);
    line(1, '#ff5252', yHr);
  },

  async closeSession(v) {
    if (v === 'delete' && this.sessionFile) {
      if (!confirm('Eliminare questo allenamento dal PC?')) return;
      await SessionStore.remove(this.sessionFile);
      this.renderSessions();
    } else if (v === 'csv' && this.session) {
      const rows = (this.session.samples || []).map((r) =>
        [new Date(r[0]).toISOString(), r[1] ?? '', r[2] ?? '', r[3] ?? '', r[4] ?? '', r[5] ?? '', r[6] ?? ''].join(';'));
      const csv = 'ora;battito;cadenza;km/h;lat;lon;quota\n' + rows.join('\n') + '\n';
      const name = String(this.session.name || 'allenamento').replace(/[\\/:*?"<>|]/g, '_') + '.csv';
      if (window.sirjoe && window.sirjoe.saveFile) {
        const p = await window.sirjoe.saveFile(name, csv, 'csv');
        if (p) toast('Salvato: ' + p);
      }
    }
  }
};

/* ------------------------------------------------------------ collegamenti */
(() => {
  // riga "ripeti" nell'editor
  const box = document.createElement('div');
  box.id = 'woRepeatBox';
  box.className = 'repeat-box';
  box.hidden = true;
  box.innerHTML = 'Ripeti le ultime <input id="woRepN" type="number" min="1" max="20" value="2" class="num"> fasi altre ' +
    '<input id="woRepT" type="number" min="1" max="20" value="3" class="num"> volte ' +
    '<button type="button" class="btn primary" id="woRepOk">OK</button>';
  $('woRepeat').insertAdjacentElement('afterend', box);
  $('woRepeat').addEventListener('click', () => { box.hidden = !box.hidden; });
  $('woRepOk').addEventListener('click', () => Training.repeat());

  $('trAge').addEventListener('change', () => Training.saveProfile());
  $('trMax').addEventListener('change', () => Training.saveProfile());
  $('trNew').addEventListener('click', () => Training.edit(null));
  $('trTemplates').addEventListener('click', () => Training.addTemplates());
  $('woAdd').addEventListener('click', () => {
    const last = Training.draft.steps[Training.draft.steps.length - 1];
    Training.draft.steps.push(last ? { ...last, label: 'Nuova fase', tip: '' } : { label: 'Fase', sec: 300, zLo: 2, zHi: 2, cLo: 0, cHi: 0, tip: '' });
    Training.renderSteps();
  });
  // Invio nei campi non deve premere il primo pulsante del modulo (Elimina)
  $('woDialog').addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target.tagName === 'INPUT') e.preventDefault(); });
  $('woDialog').addEventListener('close', () => Training.closeEditor($('woDialog').returnValue));
  $('sesDialog').addEventListener('close', () => Training.closeSession($('sesDialog').returnValue));
  $('trFolder').addEventListener('click', () => {
    if (window.sirjoe && window.sirjoe.sessions) window.sirjoe.sessions.folder();
    else toast('Disponibile nel programma per computer');
  });
  $('trRefresh').addEventListener('click', () => Training.renderSessions());
  document.querySelector('.tab[data-tab="training"]').addEventListener('click', () => {
    Training.renderProfile();
    Training.renderList();
    Training.renderSessions();
  });
  Training.renderProfile();
  Training.renderList();
})();
