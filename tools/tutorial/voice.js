/* Voce italiana del tutorial con Piper (sintesi vocale libera, gira sul computer).
 * Uso: node voice.js <modello.onnx> <cartella-uscita>
 * Crea un file NN.wav per ogni scena di scenes.js. */
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const scenes = require('./scenes');

const MODEL = process.argv[2];
const OUT = path.resolve(process.argv[3] || 'out/voce');
fs.mkdirSync(OUT, { recursive: true });

// come vanno lette sigle e nomi stranieri
const SAY = [
  [/SirJoe/g, 'Sir Giò'], [/\bMTB\b/g, 'emme ti bi'], [/\bGPX\b/g, 'gi pi ics'], [/\bKML\b/g, 'cappa emme elle'],
  [/\bTCX\b/g, 'ti ci ics'], [/\bCSV\b/g, 'ci esse vu'], [/\bAPK\b/g, 'a pi cappa'], [/GitHub/g, 'ghit hab'],
  [/Releases/g, 'rilìsis'], [/Wi-Fi/g, 'uai fai'], [/\bPC\b/g, 'pi ci'], [/\bQR\b/g, 'cu erre'], [/Client ID/g, 'client ai di'],
  [/Client Secret/g, 'client sìcret'], [/localhost/g, 'local host'], [/MBTiles/g, 'emme bi tails'], [/SQLiteDB/g, 'esse cu lait di bi'],
  [/PMTiles/g, 'pi emme tails'], [/GeoTIFF/g, 'geo tiff'], [/\bKMZ\b/g, 'cappa emme zeta'], [/\bXYZ\b/g, 'ics ipsilon zeta'],
  [/\bWMTS\b/g, 'vu emme ti esse'], [/\bWMS\b/g, 'vu emme esse'], [/OziExplorer/g, 'ozi explorer'], [/OpenTopoMap/g, 'open topo map'],
  [/OpenStreetMap/g, 'open strit map'], [/OpenAndroMaps/g, 'open andro maps'], [/CyclOSM/g, 'siclo esse emme'], [/Xiaomi/g, 'sciaomi'],
  [/Wahoo/g, 'uahu'], [/Planner/g, 'plèner'], [/portable/g, 'pòrtabol'], [/\bsetup\b/g, 'setàp'], [/Windows/g, 'uindous'],
  [/browser/g, 'bràuser'], [/firewall/g, 'fàier uol'], [/Elevate/g, 'élevet'], [/\breplay\b/g, 'riplèi'], [/\bFIT\b/g, 'fit'],
  [/GeoJSON/g, 'geo gèison'], [/Komoot/g, 'comùt'], [/Strava/g, 'stràva'], [/\bPIN\b/g, 'pin'], [/\bID\b/g, 'ai di'], [/Esri/g, 'èsri'],
  [/\bE-?mail\b/gi, 'imeil'], [/Redmi/g, 'redmi'], [/\bweb\b/g, 'uèb']
];
const speakable = (t) => SAY.reduce((s, [re, r]) => s.replace(re, r), t);

const PIPER = process.env.PIPER || 'piper';
scenes.forEach((sc, i) => {
  const f = path.join(OUT, String(i).padStart(2, '0') + '.wav');
  const r = spawnSync(PIPER, ['--model', MODEL, '--output_file', f, '--length_scale', process.env.SPEED || '1.0', '--sentence_silence', '0.25'],
    { input: speakable(sc.say), encoding: 'utf8' });
  if (r.status !== 0 || !fs.existsSync(f)) {
    console.error('Piper non riuscito per la scena', i, r.stderr || r.error);
    process.exit(1);
  }
  console.log('voce', i);
});
