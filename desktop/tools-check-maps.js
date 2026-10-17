/* Verifica i lettori di mappe da file (eseguito dalla compilazione su GitHub) */
const D = '';
const Database = require(D + 'better-sqlite3');
const JSZip = require(D + 'jszip');
const GeoTIFF = require(D + 'geotiff');
const fs = require('fs'), path = require('path');
const { MapFiles } = require('./map-sources.js');
const png = fs.readFileSync('app/icon.png');
const dir = path.join(require('os').tmpdir(), 'sirjoe-maps-test'); fs.rmSync(dir, { recursive: true, force: true }); fs.mkdirSync(dir);
(async () => {
  // MBTiles
  let db = new Database(dir + '/a.mbtiles');
  db.exec('CREATE TABLE metadata(name,value); CREATE TABLE tiles(zoom_level,tile_column,tile_row,tile_data)');
  db.prepare('INSERT INTO metadata VALUES (?,?)').run('name', 'Prova MB');
  db.prepare('INSERT INTO metadata VALUES (?,?)').run('format', 'png');
  db.prepare('INSERT INTO tiles VALUES (?,?,?,?)').run(10, 543, (1 << 10) - 1 - 364, png);
  db.close();
  // SQLiteDB RMaps
  db = new Database(dir + '/b.sqlitedb');
  db.exec('CREATE TABLE tiles(x,y,z,s,image); CREATE TABLE info(maxzoom,minzoom)');
  db.prepare('INSERT INTO tiles VALUES (?,?,?,?,?)').run(543, 364, 17 - 10, 0, png);
  db.close();
  // cartella
  fs.mkdirSync(dir + '/tiles/Atlas/10/543', { recursive: true });
  fs.writeFileSync(dir + '/tiles/Atlas/10/543/364.png', png);
  // world file
  fs.writeFileSync(dir + '/w.png', png);
  fs.writeFileSync(dir + '/w.pgw', '0.001\n0\n0\n-0.001\n11.0005\n46.0\n');
  // KMZ
  const zip = new JSZip();
  zip.file('doc.kml', '<kml><Document><GroundOverlay><name>Carta</name><Icon><href>files/c.png</href></Icon><LatLonBox><north>46</north><south>45.9</south><east>11.1</east><west>11</west></LatLonBox></GroundOverlay></Document></kml>');
  zip.file('files/c.png', png);
  fs.writeFileSync(dir + '/k.kmz', await zip.generateAsync({ type: 'nodebuffer' }));
  // GeoTIFF WGS84
  const w = 20, h = 10, vals = new Uint8Array(w * h * 3).fill(200);
  const ab = await GeoTIFF.writeArrayBuffer(vals, { width: w, height: h, SamplesPerPixel: 3, BitsPerSample: [8, 8, 8], PhotometricInterpretation: 2,
    ModelPixelScale: [0.01, 0.01, 0], ModelTiepoint: [0, 0, 0, 11, 46, 0], GeographicTypeGeoKey: 4326, GTModelTypeGeoKey: 2, GTRasterTypeGeoKey: 1 });
  fs.writeFileSync(dir + '/g.tif', Buffer.from(ab));
  // Ozi
  fs.writeFileSync(dir + '/o.png', png);
  fs.writeFileSync(dir + '/o.map', 'OziExplorer Map Data File Version 2.2\nCarta\no.png\n1 ,Map Code,\nWGS 84,WGS 84,   0.0000,   0.0000,WGS 84\n' +
    'MMPLL,1,  11.000000,  46.000000\nMMPLL,2,  11.100000,  46.000000\nMMPLL,3,  11.100000,  45.900000\nMMPLL,4,  11.000000,  45.900000\n');
  fs.writeFileSync(dir + '/x.map', Buffer.from('mapsforge binary OSM......'));

  const mf = new MapFiles();
  let bad = 0;
  for (const f of ['a.mbtiles', 'b.sqlitedb', 'tiles', 'w.png', 'k.kmz', 'g.tif', 'o.map', 'x.map']) {
    try {
      const r = await mf.open(path.join(dir, f));
      if (r.type === 'tiles') {
        const res = await mf.handle(`sjtile://${r.id}/10/543/364`);
        const miss = await mf.handle(`sjtile://${r.id}/10/1/1`);
        console.log(f, r.name, r.minzoom, r.maxzoom, JSON.stringify(r.bounds && r.bounds.map((b) => b.map((v) => +v.toFixed(3)))), res.status, res.headers.get('content-type'), miss.status);
      } else {
        console.log(f, r.overlays.map((o) => o.kind + ' ' + JSON.stringify(o.bounds.map((b) => b.map((v) => +v.toFixed(4)))) + (o.width ? ` ${o.width}x${o.height} ${o.rgba.length}` : '')).join(' | '));
      }
    } catch (e) {
      console.log(f, 'ERR', e.unsupported ? '(msg)' : '', e.message);
      if (f !== 'x.map') bad++;
    }
  }
  if (bad) { console.error('Lettori mappe: ' + bad + ' errori'); process.exit(1); }
  console.log('lettori mappe ok');
})().catch((e) => { console.error(e); process.exit(1); });
