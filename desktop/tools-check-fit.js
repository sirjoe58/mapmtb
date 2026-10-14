// Genera un file FIT di prova con lo stesso codice del programma (verificato poi con fitparse)
const { Fit } = require('./app/formats.js');
const pts = [];
for (let i = 0; i < 60; i++) pts.push({ lat: 45.5 + i * 0.0003, lon: 9.2 + i * 0.0002, ele: 300 + i });
require('fs').writeFileSync('test-course.fit', Buffer.from(Fit.encodeCourse('Giro di prova MTB', pts)));
const back = Fit.decode(Fit.encodeCourse('Giro di prova MTB', pts), 'x');
if (back.points.length !== 60) throw new Error('rilettura FIT errata');
console.log('FIT ok');
