const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('sirjoe', {
  desktop: true,
  fetchText: (url) => ipcRenderer.invoke('fetch-text', url),
  version: () => ipcRenderer.invoke('app-version'),
  saveFile: (name, data, ext) => ipcRenderer.invoke('save-file', { name, data, ext }),
  openFiles: () => ipcRenderer.invoke('open-files'),
  link: {
    start: (outbox) => ipcRenderer.invoke('link-start', outbox),
    qr: (address) => ipcRenderer.invoke('link-qr', address),
    stop: () => ipcRenderer.invoke('link-stop'),
    onEvent: (cb) => ipcRenderer.on('link-event', (_e, ev) => cb(ev))
  },
  rides: {
    list: () => ipcRenderer.invoke('rides-list'),
    read: (file) => ipcRenderer.invoke('ride-read', file),
    remove: (file) => ipcRenderer.invoke('ride-delete', file),
    add: (name, text) => ipcRenderer.invoke('ride-add', name, text),
    folder: () => ipcRenderer.invoke('rides-folder')
  }
});
