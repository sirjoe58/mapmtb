const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('sirjoe', {
  desktop: true,
  fetchText: (url) => ipcRenderer.invoke('fetch-text', url),
  version: () => ipcRenderer.invoke('app-version'),
  saveFile: (name, data, ext) => ipcRenderer.invoke('save-file', { name, data, ext }),
  openFiles: () => ipcRenderer.invoke('open-files')
});
