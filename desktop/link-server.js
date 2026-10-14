/* SirJoe Link - piccolo server nella rete Wi-Fi di casa.
 * Il telefono (app SirJoe MTB) inquadra il QR, scarica i percorsi offerti dal PC
 * e invia le uscite registrate. Niente cloud, niente account: funziona solo in rete locale,
 * solo mentre la finestra "SirJoe Link" è aperta e solo con il codice segreto del QR.
 */
'use strict';
const http = require('http');
const os = require('os');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const QRCode = require('qrcode');

const PREFERRED_PORT = 47891;
const LAST_PORT = 47899; // porte provate in ordine (la regola del firewall copre 47891-47899)
const QR_OPTS = { scale: 8, margin: 4, errorCorrectionLevel: 'L', color: { dark: '#000000', light: '#ffffff' } };
const MAX_UPLOAD = 30 * 1024 * 1024;

/** Indirizzi IPv4 della rete locale, i più probabili per primi */
function localAddresses() {
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family !== 'IPv4' && a.family !== 4) continue;
      if (a.internal) continue;
      const score = a.address.startsWith('192.168.') ? 0 : a.address.startsWith('10.') ? 1 : a.address.startsWith('172.') ? 2 : 3;
      const virtual = /vmware|virtualbox|vethernet|hyper-v|docker|wsl|loopback/i.test(name) ? 10 : 0;
      out.push({ address: a.address, iface: name, score: score + virtual });
    }
  }
  return out.sort((x, y) => x.score - y.score).map((x) => x.address);
}

function safeName(s) {
  return String(s || 'Uscita').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim().slice(0, 80) || 'Uscita';
}

class LinkServer {
  /**
   * @param {object} o
   * @param {string} o.dataDir   cartella dove salvare token e uscite ricevute
   * @param {string} o.pcName    nome del PC mostrato sul telefono
   * @param {(ev:object)=>void} o.onEvent  notifiche per la finestra
   */
  constructor({ dataDir, pcName, onEvent }) {
    this.dataDir = dataDir;
    this.pcName = pcName;
    this.onEvent = onEvent || (() => {});
    this.ridesDir = path.join(dataDir, 'uscite');
    fs.mkdirSync(this.ridesDir, { recursive: true });
    this.token = this._loadToken();
    this.outbox = [];
    this.server = null;
    this.port = null;
    this.pin = null;
    this.pinFails = 0;
  }

  /** PIN di 6 cifre per il collegamento a mano (senza QR), nuovo a ogni apertura */
  _newPin() {
    this.pin = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
    this.pinFails = 0;
    return this.pin;
  }

  _loadToken() {
    const f = path.join(this.dataDir, 'link.json');
    try {
      const j = JSON.parse(fs.readFileSync(f, 'utf8'));
      if (j.token && j.token.length >= 20) return j.token;
    } catch (e) { /* primo avvio */ }
    const token = crypto.randomBytes(18).toString('base64url');
    fs.writeFileSync(f, JSON.stringify({ token }));
    return token;
  }

  /** percorsi offerti al telefono: [{ id, name, km, gpx }] */
  setOutbox(items) {
    this.outbox = (items || []).map((it, i) => ({ id: String(it.id || i), name: it.name, km: it.km, gpx: it.gpx }));
  }

  _send(res, code, body, type = 'application/json; charset=utf-8') {
    res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' });
    res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
  }

  _handle(req, res) {
    const url = new URL(req.url, 'http://localhost');
    const remote0 = (req.socket.remoteAddress || '').replace('::ffff:', '');

    // prova dal browser del telefono: http://IP:PORTA/ping
    if (req.method === 'GET' && url.pathname === '/ping') {
      this.onEvent({ type: 'ping', from: remote0 });
      return this._send(res, 200, 'SirJoe Link OK: il telefono raggiunge il PC ' + this.pcName + '.\nTorna nell\'app SirJoe MTB.', 'text/plain; charset=utf-8');
    }
    // collegamento a mano: il PIN mostrato sul PC dà il codice segreto
    if (req.method === 'GET' && url.pathname === '/pair') {
      const pin = String(url.searchParams.get('pin') || '');
      if (this.pin && pin.length === 6 && crypto.timingSafeEqual(Buffer.from(pin), Buffer.from(this.pin))) {
        this.onEvent({ type: 'connected', from: remote0 });
        return this._send(res, 200, { token: this.token, name: this.pcName });
      }
      this.pinFails++;
      if (this.pinFails >= 5) {
        this._newPin();
        this.onEvent({ type: 'pin', pin: this.pin, reason: 'troppi tentativi sbagliati' });
      }
      return this._send(res, 403, { error: 'PIN sbagliato: controlla il numero sul PC' });
    }
    const token = req.headers['x-sirjoe-token'] || url.searchParams.get('t');
    const okToken = token && token.length === this.token.length &&
      crypto.timingSafeEqual(Buffer.from(String(token)), Buffer.from(this.token));
    if (!okToken) {
      this.onEvent({ type: 'denied', from: remote0 });
      return this._send(res, 403, { error: 'Codice non valido: inquadra di nuovo il QR' });
    }

    const remote = (req.socket.remoteAddress || '').replace('::ffff:', '');
    if (req.method === 'GET' && url.pathname === '/info') {
      this.onEvent({ type: 'connected', from: remote });
      return this._send(res, 200, { app: 'SirJoe MTB Planner', name: this.pcName, items: this.outbox.length });
    }
    if (req.method === 'GET' && url.pathname === '/outbox') {
      return this._send(res, 200, this.outbox.map(({ id, name, km }) => ({ id, name, km })));
    }
    const m = url.pathname.match(/^\/outbox\/([^/]+)\.gpx$/);
    if (req.method === 'GET' && m) {
      const it = this.outbox.find((x) => x.id === decodeURIComponent(m[1]));
      if (!it) return this._send(res, 404, { error: 'Percorso non trovato' });
      this.onEvent({ type: 'sent', name: it.name, from: remote });
      return this._send(res, 200, it.gpx, 'application/gpx+xml; charset=utf-8');
    }
    if (req.method === 'POST' && url.pathname === '/inbox') {
      const chunks = [];
      let size = 0;
      req.on('data', (c) => {
        size += c.length;
        if (size > MAX_UPLOAD) { req.destroy(); return; }
        chunks.push(c);
      });
      req.on('end', () => {
        const body = Buffer.concat(chunks);
        if (!body.toString('utf8', 0, 400).includes('<gpx')) return this._send(res, 400, { error: 'Non è un file GPX' });
        const name = safeName(url.searchParams.get('name'));
        let file = path.join(this.ridesDir, name + '.gpx');
        for (let i = 2; fs.existsSync(file); i++) file = path.join(this.ridesDir, `${name} (${i}).gpx`);
        fs.writeFileSync(file, body);
        this.onEvent({ type: 'received', name: path.basename(file, '.gpx'), file: path.basename(file), from: remote });
        this._send(res, 200, { ok: true, name: path.basename(file, '.gpx') });
      });
      return;
    }
    this._send(res, 404, { error: 'Richiesta sconosciuta' });
  }

  _listen(port) {
    return new Promise((resolve, reject) => {
      const srv = http.createServer((req, res) => {
        try { this._handle(req, res); } catch (e) { this._send(res, 500, { error: e.message }); }
      });
      srv.once('error', reject);
      srv.listen(port, '0.0.0.0', () => resolve(srv));
    });
  }

  /** Avvia il server e prepara il QR: { url, qr, port, addresses, token } */
  async start() {
    if (!this.server) {
      for (let p = PREFERRED_PORT; p <= LAST_PORT && !this.server; p++) {
        try { this.server = await this._listen(p); } catch (e) { /* porta occupata: prova la successiva */ }
      }
      if (!this.server) this.server = await this._listen(0);
      this.port = this.server.address().port;
    }
    this._newPin();
    const addresses = localAddresses();
    if (!addresses.length) throw new Error('Il PC non è collegato a nessuna rete');
    const url = this.urlFor(addresses[0]);
    const qr = await QRCode.toDataURL(url, QR_OPTS);
    return { url, qr, port: this.port, addresses, pcName: this.pcName, pin: this.pin };
  }

  urlFor(address) {
    const q = new URLSearchParams({ h: address, p: String(this.port), t: this.token, n: this.pcName });
    return 'sirjoemtb://link?' + q.toString();
  }

  async qrFor(address) {
    return QRCode.toDataURL(this.urlFor(address), QR_OPTS);
  }

  stop() {
    if (this.server) { this.server.close(); this.server = null; this.port = null; }
    this.pin = null;
  }

  /* ------------------------------- uscite ricevute (cartella sul PC) */

  listRides() {
    return fs.readdirSync(this.ridesDir)
      .filter((f) => f.toLowerCase().endsWith('.gpx'))
      .map((f) => {
        const st = fs.statSync(path.join(this.ridesDir, f));
        return { file: f, name: f.replace(/\.gpx$/i, ''), size: st.size, mtime: st.mtimeMs };
      })
      .sort((a, b) => b.mtime - a.mtime);
  }

  _ridePath(file) {
    const p = path.join(this.ridesDir, path.basename(String(file)));
    if (!p.startsWith(this.ridesDir)) throw new Error('Percorso non valido');
    return p;
  }

  readRide(file) { return fs.readFileSync(this._ridePath(file), 'utf8'); }

  deleteRide(file) { fs.unlinkSync(this._ridePath(file)); }

  addRide(name, text) {
    const base = safeName(name);
    let file = path.join(this.ridesDir, base + '.gpx');
    for (let i = 2; fs.existsSync(file); i++) file = path.join(this.ridesDir, `${base} (${i}).gpx`);
    fs.writeFileSync(file, text);
    return path.basename(file);
  }
}

module.exports = { LinkServer, localAddresses };
