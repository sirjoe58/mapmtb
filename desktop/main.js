// SirJoe MTB Planner - processo principale (Electron)
const { app, BrowserWindow, ipcMain, net, shell, session, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { LinkServer } = require('./link-server');

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

  // ---- SirJoe Link: server Wi-Fi verso il telefono, attivo solo a finestra aperta
  link = new LinkServer({
    dataDir: app.getPath('userData'),
    pcName: os.hostname() || 'PC',
    onEvent: (ev) => { if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send('link-event', ev); }
  });
  ipcMain.handle('link-start', async (_e, outbox) => { link.setOutbox(outbox); return link.start(); });
  ipcMain.handle('link-qr', (_e, address) => link.qrFor(address));
  ipcMain.handle('link-stop', () => { link.stop(); return true; });
  ipcMain.handle('rides-list', () => link.listRides());
  ipcMain.handle('ride-read', (_e, file) => link.readRide(file));
  ipcMain.handle('ride-delete', (_e, file) => { link.deleteRide(file); return true; });
  ipcMain.handle('ride-add', (_e, name, text) => link.addRide(name, text));
  ipcMain.handle('rides-folder', () => { shell.openPath(link.ridesDir); return link.ridesDir; });

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
