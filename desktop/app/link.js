/* SirJoe MTB Planner - finestra "SirJoe Link" (collegamento Wi-Fi con il telefono) */
'use strict';

const Link = {
  open: false,

  /** percorsi offerti al telefono: quello aperto ora + l'archivio */
  outbox() {
    const items = [];
    const cur = allPoints();
    if (cur.length >= 2 || state.places.length) {
      items.push({ id: 'current', name: routeName(), km: Fmt.km(Geo.summary(cur).dist), gpx: Formats.gpx(routeName(), cur, state.places) });
    } else if (state.places.length) {
      items.push({ id: 'places', name: 'Segnaposto', km: '0', gpx: Formats.gpx('Segnaposto', [], state.places) });
    }
    for (const it of store.get('library', [])) {
      const pts = [];
      it.legs.forEach((l) => pts.push(...l));
      if (pts.length >= 2) items.push({ id: it.id, name: it.name, km: Fmt.km(Geo.summary(pts).dist), gpx: Formats.gpx(it.name, pts, []) });
    }
    return items;
  },

  log(text) {
    const li = document.createElement('li');
    li.textContent = new Date().toLocaleTimeString('it-IT', { hour: '2-digit', minute: '2-digit' }) + '  ' + text;
    $('linkLog').prepend(li);
  },

  async show() {
    if (!(window.sirjoe && window.sirjoe.link)) {
      return toast('SirJoe Link funziona nel programma per Windows');
    }
    const items = this.outbox();
    $('linkOut').innerHTML = items.length ? '' : '<li class="empty">Nessun percorso: creane uno o salvalo in archivio</li>';
    for (const it of items) {
      const li = document.createElement('li');
      li.innerHTML = '<span class="grow"><b></b><small></small></span>';
      li.querySelector('b').textContent = (it.id === 'current' ? '★ ' : '') + it.name;
      li.querySelector('small').textContent = it.km + ' km' + (it.id === 'current' && state.places.length ? ' · ' + state.places.length + ' segnaposto' : '');
      $('linkOut').appendChild(li);
    }
    $('linkLog').innerHTML = '';
    $('linkQr').removeAttribute('src');
    $('linkAddr').textContent = 'Avvio…';
    $('linkDialog').showModal();
    this.open = true;
    try {
      const r = await window.sirjoe.link.start(items);
      $('linkQr').src = r.qr;
      $('linkAddr').textContent = `${r.addresses[0]}:${r.port} · ${r.pcName}`;
      const pick = $('linkAddrPick'), sel = $('linkAddrSel');
      pick.hidden = r.addresses.length < 2;
      sel.innerHTML = r.addresses.map((a) => `<option>${a}</option>`).join('');
      sel.onchange = async () => {
        $('linkQr').src = await window.sirjoe.link.qr(sel.value);
        $('linkAddr').textContent = `${sel.value}:${r.port} · ${r.pcName}`;
      };
    } catch (e) {
      $('linkAddr').textContent = '';
      this.log('Collegamento non disponibile: ' + e.message);
    }
  },

  onEvent(ev) {
    if (ev.type === 'connected') this.log('📱 Telefono collegato (' + ev.from + ')');
    else if (ev.type === 'sent') this.log('➜ Inviato al telefono: ' + ev.name);
    else if (ev.type === 'received') {
      this.log('⬇ Ricevuta uscita: ' + ev.name + ' (scheda Uscite)');
      toast('Uscita ricevuta dal telefono: ' + ev.name);
      Analysis.renderList();
    }
  }
};

$('linkBtn').addEventListener('click', () => Link.show());
$('linkDialog').addEventListener('close', () => {
  Link.open = false;
  if (window.sirjoe && window.sirjoe.link) window.sirjoe.link.stop();
});
if (window.sirjoe && window.sirjoe.link) window.sirjoe.link.onEvent((ev) => Link.onEvent(ev));
