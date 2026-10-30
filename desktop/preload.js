const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('sirjoe', {
  desktop: true,
  platform: process.platform,
  fetchText: (url) => ipcRenderer.invoke('fetch-text', url),
  fetchDataUrl: (url) => ipcRenderer.invoke('fetch-data-url', url),
  maps: {
    open: (folder) => ipcRenderer.invoke('maps-open', !!folder),
    reopen: (p) => ipcRenderer.invoke('maps-reopen', p),
    close: (id) => ipcRenderer.invoke('maps-close', id)
  },
  version: () => ipcRenderer.invoke('app-version'),
  saveFile: (name, data, ext) => ipcRenderer.invoke('save-file', { name, data, ext }),
  openFiles: () => ipcRenderer.invoke('open-files'),
  link: {
    start: (outbox, training) => ipcRenderer.invoke('link-start', outbox, training),
    qr: (address) => ipcRenderer.invoke('link-qr', address),
    stop: () => ipcRenderer.invoke('link-stop'),
    firewall: () => ipcRenderer.invoke('link-firewall'),
    onEvent: (cb) => ipcRenderer.on('link-event', (_e, ev) => cb(ev))
  },
  rides: {
    list: () => ipcRenderer.invoke('rides-list'),
    read: (file) => ipcRenderer.invoke('ride-read', file),
    remove: (file) => ipcRenderer.invoke('ride-delete', file),
    add: (name, text) => ipcRenderer.invoke('ride-add', name, text),
    folder: () => ipcRenderer.invoke('rides-folder')
  },
  sessions: {
    list: () => ipcRenderer.invoke('sessions-list'),
    read: (file) => ipcRenderer.invoke('session-read', file),
    remove: (file) => ipcRenderer.invoke('session-delete', file),
    folder: () => ipcRenderer.invoke('sessions-folder')
  }
});
