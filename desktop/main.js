// SirJoe MTB Planner - processo principale (Electron)
const { app, BrowserWindow, ipcMain, net, shell, session } = require('electron');
const path = require('path');

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
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
