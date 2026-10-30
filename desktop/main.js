// SirJoe MTB Planner - processo principale (Electron)
const { app, BrowserWindow, ipcMain, net, shell, session, dialog, protocol } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { execFile } = require('child_process');
const { LinkServer } = require('./link-server');
const { MapFiles } = require('./map-sources');

// mappe da file: riquadri serviti alla finestra come sjtile://<id>/<z>/<x>/<y>
protocol.registerSchemesAsPrivileged([
  { scheme: 'sjtile', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } }
]);
const mapFiles = new MapFiles();

let link = null;
let mainWin = null;

let lastDir = null;

const UA = `SirJoeMTBPlanner/${app.getVersion()} (uso personale)`;

function createWindow() {
  const win = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 900,
    minHeight: 600,
    title: 'SirJoe MTB Planner',
    backgroundColor: '#14261c',
    icon: path.join(__dirname, 'build', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  mainWin = win;
  win.loadFile(path.join(__dirname, 'app', 'index.html'));
  // i link esterni si aprono nel browser
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });
}

/**
 * Sblocca SirJoe Link nel firewall di Windows (chiede il permesso di amministratore):
 * toglie i blocchi creati se all'avviso di Windows si è premuto "Annulla" e consente
 * le porte 47891-47899 in entrata, su qualsiasi tipo di rete.
 */
function unlockFirewall() {
  if (process.platform === 'darwin') return unlockFirewallMac();
  if (process.platform !== 'win32') return Promise.resolve({ ok: false, error: 'Solo su Windows e Mac' });
  const script = [
    "$ErrorActionPreference = 'SilentlyContinue'",
    "Get-NetFirewallApplicationFilter | Where-Object { $_.Program -like '*SirJoe*' } | Get-NetFirewallRule | Where-Object { $_.Direction -eq 'Inbound' -and $_.Action -eq 'Block' } | Remove-NetFirewallRule",
    "Remove-NetFirewallRule -DisplayName 'SirJoe Link'",
    "New-NetFirewallRule -DisplayName 'SirJoe Link' -Description 'SirJoe MTB Planner: collegamento Wi-Fi con il telefono' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 47891-47899 -Profile Any | Out-Null",
    "if (Get-NetFirewallRule -DisplayName 'SirJoe Link') { exit 0 } else { exit 1 }"
  ].join('\r\n');
  const file = path.join(app.getPath('temp'), 'sirjoe-link-firewall.ps1');
  fs.writeFileSync(file, script, 'utf8');
  const outer = `try { $p = Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','"${file}"' -Verb RunAs -WindowStyle Hidden -PassThru -Wait; exit $p.ExitCode } catch { exit 5 }`;
  return new Promise((resolve) => {
    execFile('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', outer], { windowsHide: true }, (err) => {
      try { fs.unlinkSync(file); } catch (e) { /* già tolto */ }
      const code = err ? (typeof err.code === 'number' ? err.code : 1) : 0;
      if (code === 0) resolve({ ok: true });
      else if (code === 5) resolve({ ok: false, error: 'Permesso di amministratore non concesso' });
      else resolve({ ok: false, error: 'Regola non creata (codice ' + code + ')' });
    });
  });
}

/**
 * Mac: consente le connessioni in entrata al programma nel firewall di macOS
 * (chiede la password di amministratore). Se il firewall blocca tutto, lo segnala.
 */
function unlockFirewallMac() {
  const FW = '/usr/libexec/ApplicationFirewall/socketfilterfw';
  const exe = process.execPath;                       // …/SirJoe MTB Planner.app/Contents/MacOS/…
  const bundle = path.resolve(exe, '..', '..', '..');  // …/SirJoe MTB Planner.app
  const run = (args) => new Promise((resolve) => {
    execFile(FW, args, (err, out) => resolve(String(out || '') + (err ? ' ' + err.message : '')));
  });
  if (/AppTranslocation|^\/Volumes\//.test(bundle)) {
    return Promise.resolve({ ok: false, error: 'il programma è aperto dal disco .dmg: trascinalo prima nella cartella Applicazioni, chiudilo e riaprilo da lì, poi riprova' });
  }
  return (async () => {
    const globale = await run(['--getglobalstate']);
    const bloccaTutto = await run(['--getblockall']);
    if (/ENABLED|attivat|abilitat/i.test(bloccaTutto) && !/DISABLED|disattivat|disabilitat/i.test(bloccaTutto)) {
      return { ok: false, error: 'nel firewall del Mac è attivo "Blocca tutte le connessioni in entrata": apri Impostazioni di Sistema → Rete → Firewall → Opzioni e disattivalo, poi riprova' };
    }
    const q = (s) => "'" + s.replace(/'/g, "'\\''") + "'";
    const cmd = [bundle, exe].map((p) => `${FW} --add ${q(p)}; ${FW} --unblockapp ${q(p)}`).join('; ');
    const script = `do shell script "${cmd}" with administrator privileges with prompt "SirJoe MTB Planner vuole consentire il collegamento Wi-Fi con il telefono."`;
    return new Promise((resolve) => {
      execFile('osascript', ['-e', script], (err, _out, errOut) => {
        if (!err) {
          const spento = /disabled|disattivat|disabilitat/i.test(globale);
          return resolve({ ok: true, note: spento ? 'il firewall del Mac risulta già spento' : '' });
        }
        if (/-128/.test(String(errOut) + err.message)) return resolve({ ok: false, error: 'Password di amministratore non inserita' });
        resolve({ ok: false, error: 'Regola non creata: ' + (String(errOut).trim() || err.message) });
      });
    });
  })();
}

app.whenReady().then(() => {
  // identificazione corretta verso i server di mappe e percorsi (richiesta dalle loro regole)
  session.defaultSession.setUserAgent(UA);
  // richieste di rete dal processo principale: nessun problema di CORS
  ipcMain.handle('fetch-text', async (_e, url) => {
    const res = await net.fetch(url, { headers: { 'User-Agent': UA } });
    const text = await res.text();
    return { ok: res.ok, status: res.status, text };
  });
  ipcMain.handle('app-version', () => app.getVersion());

  // ---- mappe da file (MBTiles, SQLiteDB, PMTiles, cartelle, GeoTIFF, KMZ, world file, OziExplorer)
  protocol.handle('sjtile', (req) => mapFiles.handle(req.url));
  const openMap = async (p) => {
    try {
      return { ok: true, path: p, ...(await mapFiles.open(p)) };
    } catch (e) {
      return { ok: false, path: p, error: e.message };
    }
  };
  ipcMain.handle('maps-open', async (e, folder) => {
    const win = BrowserWindow.fromWebContents(e.sender);
    const r = await dialog.showOpenDialog(win, folder ? {
      title: 'Cartella con i riquadri della mappa (z/x/y)', properties: ['openDirectory']
    } : {
      title: 'Apri mappe',
      properties: ['openFile', 'multiSelections'],
      filters: [
        { name: 'Mappe', extensions: ['mbtiles', 'sqlitedb', 'sqlite', 'db', 'pmtiles', 'tif', 'tiff', 'kmz', 'kml', 'map', 'jpg', 'jpeg', 'png', 'gif', 'webp'] },
        { name: 'Tutti i file', extensions: ['*'] }
      ]
    });
    if (r.canceled) return [];
    const out = [];
    for (const p of r.filePaths) out.push(await openMap(p));
    return out;
  });
  ipcMain.handle('maps-reopen', (_e, p) => openMap(p));
  ipcMain.handle('maps-close', (_e, id) => { mapFiles.close(id); return true; });
  // immagini (riquadri di mappa per il video) come data URL: niente problemi di origine sul canvas
  ipcMain.handle('fetch-data-url', async (_e, url) => {
    if (!/^https?:\/\//i.test(url)) throw new Error('Indirizzo non consentito');
    const res = await net.fetch(url, { headers: { 'User-Agent': UA } });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    if (!/^image\//.test(res.headers.get('content-type') || 'image/')) throw new Error('Non è un\'immagine');
    const buf = Buffer.from(await res.arrayBuffer());
    return `data:${res.headers.get('content-type') || 'image/png'};base64,${buf.toString('base64')}`;
  });

  // ---- SirJoe Link: server Wi-Fi verso il telefono, attivo solo a finestra aperta
  link = new LinkServer({
    dataDir: app.getPath('userData'),
    pcName: os.hostname() || 'PC',
    onEvent: (ev) => { if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send('link-event', ev); }
  });
  ipcMain.handle('link-start', async (_e, outbox, training) => { link.setOutbox(outbox); link.setTraining(training); return link.start(); });
  ipcMain.handle('link-qr', (_e, address) => link.qrFor(address));
  ipcMain.handle('link-training', (_e, t) => { link.setTraining(t); return true; });
  ipcMain.handle('link-stop', () => { link.stop(); return true; });
  ipcMain.handle('link-firewall', () => unlockFirewall());
  ipcMain.handle('rides-list', () => link.listRides());
  ipcMain.handle('ride-read', (_e, file) => link.readRide(file));
  ipcMain.handle('ride-delete', (_e, file) => { link.deleteRide(file); return true; });
  ipcMain.handle('ride-add', (_e, name, text) => link.addRide(name, text));
  ipcMain.handle('rides-folder', () => { shell.openPath(link.ridesDir); return link.ridesDir; });
  // allenamenti ricevuti da SirJoe Training
  ipcMain.handle('sessions-list', () => link.listSessions());
  ipcMain.handle('session-read', (_e, file) => link.readSession(file));
  ipcMain.handle('session-delete', (_e, file) => { link.deleteSession(file); return true; });
  ipcMain.handle('sessions-folder', () => { shell.openPath(link.sessionsDir); return link.sessionsDir; });

  // "Salva con nome": scegli nome e cartella
  ipcMain.handle('save-file', async (e, { name, data, ext }) => {
    const win = BrowserWindow.fromWebContents(e.sender);
    const safe = String(name).replace(/[\\/:*?"<>|]/g, '_');
    const r = await dialog.showSaveDialog(win, {
      title: 'Salva con nome',
      defaultPath: path.join(lastDir || app.getPath('documents'), safe),
      buttonLabel: 'Salva',
      filters: [{ name: ext.toUpperCase(), extensions: [ext] }, { name: 'Tutti i file', extensions: ['*'] }]
    });
    if (r.canceled || !r.filePath) return null;
    await fs.promises.writeFile(r.filePath, typeof data === 'string' ? data : Buffer.from(data));
    lastDir = path.dirname(r.filePath);
    return r.filePath;
  });

  // "Apri": uno o più file traccia
  ipcMain.handle('open-files', async (e) => {
    const win = BrowserWindow.fromWebContents(e.sender);
    const r = await dialog.showOpenDialog(win, {
      title: 'Apri percorso o traccia',
      defaultPath: lastDir || app.getPath('documents'),
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: 'Tracce', extensions: ['gpx', 'kml', 'geojson', 'json', 'tcx', 'fit', 'csv'] }, { name: 'Tutti i file', extensions: ['*'] }]
    });
    if (r.canceled) return [];
    lastDir = path.dirname(r.filePaths[0]);
    return Promise.all(r.filePaths.map(async (f) => ({ name: path.basename(f), data: new Uint8Array(await fs.promises.readFile(f)) })));
  });
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
