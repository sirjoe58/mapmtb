/* Copione del videotutorial: ogni scena ha un'immagine, il testo parlato (anche sottotitolo)
 * e i punti da indicare. Coordinate:
 *  - Planner: nomi presi da targets.json oppure [x, y] in pixel 1920x1080;
 *  - telefono: [x, y] in pixel della schermata 400x820 dell'app. */
'use strict';

const PH = { nav: [[40, 788], [120, 788], [200, 788], [280, 788], [360, 788]], layers: [369, 300], heading: [369, 354], center: [369, 408], rec: [362, 469], stop: [362, 537] };

module.exports = [
  // ------------------------------------------------------------------ apertura
  { title: 'SirJoe MTB', sub: 'Videotutorial · app Android e Planner per Windows',
    say: 'Benvenuto nel videotutorial di SirJoe MTB: il ciclocomputer e navigatore per mountain bike sul telefono, e SirJoe MTB Planner, il programma per Windows con cui prepari i percorsi e analizzi le uscite.' },
  { title: 'Cosa vedrai', list: ['1 · Installare l\'app sul telefono', '2 · Mappa, registrazione e navigazione', '3 · Creare percorsi e segnaposto', '4 · Percorsi, condivisione, impostazioni', '5 · Il Planner per Windows', '6 · Sala analisi e video', '7 · SirJoe Link: telefono e PC insieme'],
    say: 'Vedremo come installare l\'app, come usare la mappa per registrare e navigare, come creare percorsi, e poi il Planner per Windows, la sala analisi e SirJoe Link, che collega telefono e computer.' },

  // ------------------------------------------------------------- installazione
  { chapter: '1 · Installazione', title: 'Scarica e installa', list: ['Apri github.com/sirjoe58/mapmtb → Releases', 'Scarica SirJoeMTB-v… .apk (ogni versione ha il suo nome)', 'Consenti l\'installazione da questa fonte', 'Per aggiornare: installa sopra, senza disinstallare'],
    say: 'Dal telefono apri la pagina del progetto su GitHub, sezione Releases, e scarica l\'ultimo file APK. La prima volta Android chiede di consentire l\'installazione da questa fonte. Per aggiornare basta installare la nuova versione sopra la vecchia: tracce, mappe e impostazioni restano.' },
  { chapter: '1 · Installazione', phone: 'a-avvertenze', tap: [[300, 520]], side: ['Al primo avvio leggi le avvertenze', 'Tocca "Ho letto e accetto"', 'Concedi posizione e notifiche'],
    say: 'Al primo avvio compaiono le avvertenze d\'uso: leggile e accetta. Poi concedi il permesso di posizione e quello delle notifiche, che serve alla registrazione con lo schermo spento.' },
  { chapter: '1 · Installazione', title: 'Telefoni Xiaomi e Redmi', list: ['Impostazioni → App → SirJoe MTB', 'Risparmio batteria: Nessuna restrizione', 'Avvio automatico: attivo', 'Posizione: consenti sempre'],
    say: 'Sui telefoni Xiaomi e Redmi imposta il risparmio batteria su nessuna restrizione e attiva l\'avvio automatico, così la registrazione non si interrompe a schermo spento.' },

  // ------------------------------------------------------------------- mappa
  { chapter: '2 · La mappa', phone: 'a-start', tap: [PH.nav[0]], side: ['Cinque pagine in basso: Mappa, Crea, Dati, Percorsi, Info', 'La bici vista da dietro è la tua posizione', 'In basso velocità, ora e km'],
    say: 'In basso ci sono le cinque pagine: Mappa, Crea, Dati, Percorsi e Info. Sulla mappa la tua posizione è una mountain bike vista da dietro, sempre al centro dello schermo. La barra semitrasparente mostra velocità, ora al centro in giallo, e chilometri.' },
  { chapter: '2 · La mappa', phone: 'a-orient', tap: [PH.heading], side: ['Bussola: scegli l\'orientamento', 'Direzione di marcia, Nord in alto o libera', 'Tocco lungo: Nord in alto subito'],
    say: 'Con il pulsante bussola scegli l\'orientamento della mappa: nella direzione di marcia, con il Nord in alto, oppure libera da girare con due dita. Un tocco lungo sulla bussola mette subito il Nord in alto.' },
  { chapter: '2 · La mappa', phone: 'a-layers', tap: [PH.layers], side: ['OpenTopoMap, OpenStreetMap', 'Mappe offline OpenAndroMaps', 'Itinerari MTB e le tue uscite registrate'],
    say: 'Il pulsante delle mappe permette di scegliere OpenTopoMap, OpenStreetMap o le mappe offline, mostrare gli itinerari MTB e, novità, tutte le tue uscite già registrate.' },
  { chapter: '2 · La mappa', phone: 'a-rides', side: ['Le uscite registrate in viola', 'Tocca una linea: nome e km', 'Utile per vedere dove sei già passato'],
    say: 'Le uscite registrate compaiono in viola trasparente. Toccando una linea vedi il nome e i chilometri.' },
  { chapter: '2 · Registrare', phone: 'a-nav', tap: [PH.rec, PH.stop], side: ['Pulsante arancione: registra / pausa', 'Pulsante rosso: termina e salva', 'Funziona anche a schermo spento'],
    say: 'Per registrare un\'uscita tocca il pulsante arancione: lo stesso pulsante mette in pausa e riprende. Quello rosso termina e salva l\'uscita nella pagina Percorsi. La registrazione continua anche con lo schermo spento.' },
  { chapter: '2 · Registrare', phone: 'a-hidden', tap: [[385, 420], [200, 790]], side: ['Dopo 30 secondi pulsanti e pagine spariscono', 'Tocca il bordo destro: tornano i pulsanti', 'Tocca la barra dati: torna la barra delle pagine'],
    say: 'Trenta secondi dopo la partenza i pulsanti e la barra delle pagine spariscono, per lasciare tutta la mappa. Per farli tornare tocca il bordo destro dello schermo, oppure la barra dei dati in basso. A fine uscita restano sempre visibili.' },
  { chapter: '2 · Registrare', phone: 'a-dim', tap: [[200, 400]], side: ['Dopo 5 minuti senza tocchi lo schermo si oscura', 'Un tocco lo riaccende', 'Sosta automatica: da fermo riparte da sola'],
    say: 'Se non tocchi lo schermo per cinque minuti si oscura, ma l\'app continua a lavorare: basta un tocco per riaccenderlo. Con la sosta automatica, quando ti fermi la registrazione va in pausa e riparte da sola quando ti rimetti in movimento.' },

  // ------------------------------------------------------------- navigazione
  { chapter: '2 · Navigare', phone: 'a-nav', side: ['Linea blu: il percorso da seguire', 'Linea arancione: la traccia registrata', 'La freccia indica dove andare'],
    say: 'Quando segui un percorso, la linea blu è la strada da fare e quella arancione la traccia che stai registrando. La freccia davanti alla bici indica dove andare, e in alto vedi i chilometri che mancano al traguardo.' },
  { chapter: '2 · Navigare', phone: 'a-reverse', side: ['Segui il percorso in tutti e due i sensi', 'Parti da qualsiasi punto', 'Il simbolo ⇄ indica il senso inverso'],
    say: 'Il percorso si può seguire in tutti e due i sensi di marcia e partendo da qualsiasi punto. L\'app capisce da sola da che parte stai andando: freccia, distanza e avvisi si adeguano.' },
  { chapter: '2 · Navigare', phone: 'a-offroute', side: ['Fuori percorso oltre 60 m', 'Il banner diventa rosso e il telefono vibra', 'La freccia ti riporta sul percorso'],
    say: 'Se esci dal percorso il banner diventa rosso, il telefono vibra e la freccia ti indica come rientrare.' },
  { chapter: '2 · Navigare', phone: 'a-climb', side: ['Avviso salite: voce e banner', '"Tra 200 metri salita al 9 per cento per 800 metri"', 'Durante la salita: metri alla cima'],
    say: 'Prima di ogni salita la voce ti avvisa: per esempio, tra duecento metri salita al nove per cento per ottocento metri. Durante la salita il banner mostra quanto manca alla cima.' },
  { chapter: '2 · Navigare', phone: 'a-ghost', side: ['Sfida il fantasma', 'Il cerchio viola è la tua uscita precedente', 'Ogni km: avanti o indietro di X secondi'],
    say: 'Con la sfida al fantasma rifai un giro già registrato: il cerchio viola è te stesso la volta scorsa, e ogni chilometro la voce ti dice se sei avanti o indietro.' },
  { chapter: '2 · Dati', phone: 'a-dati', tap: [PH.nav[2]], side: ['Velocità attuale, media e massima', 'Tempi, quota, dislivello, pendenza', 'Profilo altimetrico del percorso'],
    say: 'Nella pagina Dati trovi tutti i numeri del ciclocomputer: velocità, media e massima, tempi, quota, dislivello, pendenza, distanza al traguardo e il profilo altimetrico.' },

  // -------------------------------------------------------------------- crea
  { chapter: '3 · Crea', phone: 'a-crea', tap: [PH.nav[1], [200, 420]], side: ['Tocca la mappa per aggiungere punti', 'Oppure il mirino e il pulsante +', 'I tratti seguono strade e sentieri reali'],
    say: 'Nella pagina Crea disegni i tuoi percorsi: tocca la mappa per aggiungere i punti, oppure centra il mirino e premi più. I tratti seguono strade e sentieri reali, e in alto vedi chilometri e dislivello.' },
  { chapter: '3 · Crea', phone: 'a-creatype', side: ['Fuoristrada / MTB, Equilibrato', 'Il più veloce, il più corto, il più lungo', 'Oppure linea retta'],
    say: 'Con Modalità scegli il tipo di percorso: fuoristrada, equilibrato, il più veloce, il più corto, il più lungo, o la linea retta. Poi puoi chiudere l\'anello, invertire, salvare o seguirlo subito.' },
  { chapter: '3 · Segnaposto', phone: 'a-placemark', side: ['Tocco lungo sulla mappa: nuovo segnaposto', 'Nome, nota e tipo con colore', 'Tieni premuto per spostarlo'],
    say: 'Un tocco lungo sulla mappa crea un segnaposto: dagli un nome, una nota e un tipo, come fontana, rifugio o panorama. Tenendolo premuto lo sposti.' },
  { chapter: '3 · Segnaposto', phone: 'a-placemenu', side: ['Naviga qui dalla tua posizione', 'Aggiungilo al percorso', 'Percorso automatico tra segnaposto'],
    say: 'Toccando un segnaposto puoi navigare fin lì dalla tua posizione, aggiungerlo al percorso, oppure creare un percorso automatico che collega più segnaposto.' },

  // ---------------------------------------------------------------- percorsi
  { chapter: '4 · Percorsi', phone: 'a-percorsi', tap: [PH.nav[3]], side: ['Archivio di uscite e percorsi', 'Importa ed esporta in tutti i formati', 'GPX, FIT, KML, KMZ, TCX, GeoJSON, CSV, ZIP'],
    say: 'La pagina Percorsi è l\'archivio: importa ed esporta tracce in tutti i formati principali, GPX, FIT, KML, TCX e altri, e da qui gestisci mappe offline e SirJoe Link.' },
  { chapter: '4 · Percorsi', phone: 'a-traccia', tap: [[200, 395]], side: ['Mostra e segui sulla mappa', 'Sfida il fantasma', 'Modifica, condividi, rinomina'],
    say: 'Toccando una traccia puoi seguirla sulla mappa, sfidare il fantasma, modificarla nella pagina Crea, condividerla, rinominarla o eliminarla.' },
  { chapter: '4 · Condividere', phone: 'a-condividi', side: ['Carica su Strava', 'Immagine per i social con il logo', 'File per Komoot, Garmin, Wahoo, Wikiloc'],
    say: 'Con Condividi carichi l\'uscita su Strava, crei un\'immagine per i social con il logo, oppure invii il file a Komoot, Garmin, Wahoo o per email.' },
  { chapter: '4 · Mappe offline', phone: 'a-offline', side: ['Scarica da openandromaps.org mappa e stile Elevate', 'Importa prima la mappa, poi lo stile', 'Scegli "Offline: OpenAndroMaps"'],
    say: 'Per usare le mappe senza Internet scarica da OpenAndroMaps la mappa della tua zona e lo stile Elevate, importali da Mappe offline, e scegli Offline OpenAndroMaps dal pulsante delle mappe.' },
  { chapter: '4 · Impostazioni', phone: 'a-info', tap: [PH.nav[4]], side: ['Pausa e sosta automatica', 'Oscuramento: 1–30 minuti o mai', 'Rotazione, avvisi vocali, salite, fantasma'],
    say: 'Nella pagina Info regoli pausa e sosta automatica, dopo quanto si oscura lo schermo, la rotazione, che di base resta verticale, gli avvisi vocali, le salite e il fantasma.' },
  { chapter: '4 · Strava', phone: 'a-strava', side: ['Crea la tua app su strava.com/settings/api', 'Callback domain: localhost', 'Incolla Client ID e Secret'],
    say: 'Per Strava crea una tua applicazione gratuita sul sito di Strava, scrivi localhost come dominio di callback, e incolla qui Client ID e Client Secret.' },

  // ----------------------------------------------------------------- planner
  { chapter: '5 · Planner', title: 'SirJoe MTB Planner per Windows', list: ['Releases → planner-v…', 'portable.exe: senza installazione', 'setup.exe: con icona nel menu Start', 'SmartScreen: Ulteriori informazioni → Esegui comunque'],
    say: 'Ora il Planner per Windows. Lo scarichi sempre da Releases: la versione portable si avvia senza installazione, quella setup si installa. Se Windows mostra un avviso, clicca su ulteriori informazioni e poi esegui comunque.' },
  { chapter: '5 · Planner', shot: 'p01-avvertenze', point: ['accept'], say: 'Al primo avvio accetta le avvertenze d\'uso.' },
  { chapter: '5 · Planner', shot: 'p02-finestra', point: ['link', 'modes', 'search', 'base', 'maps', 'tabs'],
    say: 'In alto trovi SirJoe Link, le modalità punti, disegno e segnaposto, la ricerca dei luoghi, la scelta della mappa e il pulsante Mappe. A sinistra le schede: percorso, segnaposto, uscite, archivio e informazioni.' },
  { chapter: '5 · Creare un percorso', shot: 'p03-percorso', point: ['type', 'p1', 'p2', 'p3', 'p4', 'stats'],
    say: 'Scegli il tipo di percorso e clicca sulla mappa: ogni clic aggiunge un punto e il tratto segue sentieri e strade. Trascini un punto per spostarlo, clic destro per toglierlo. A sinistra chilometri, dislivello e tempo stimato.' },
  { chapter: '5 · Creare un percorso', shot: 'p04-pendenza-altimetria', point: ['slope', 'profileBtn', 'profile', 'close'],
    say: 'Colora il percorso per pendenza e vedi l\'elenco delle salite. Il profilo altimetrico è a scomparsa: lo apri con il pulsante altimetria e lo chiudi con la freccia.' },
  { chapter: '5 · Disegno a mano libera', shot: 'p07-disegno-in-corso', point: ['draw', 'pen'],
    say: 'Con Disegna tracci il percorso a mano libera, con il mouse o con la penna di una tavoletta grafica.' },
  { chapter: '5 · Disegno a mano libera', shot: 'p08-disegno', point: ['undo', 'clear'],
    say: 'Il tratto viene agganciato ai sentieri. Con annulla togli l\'ultimo tratto, con cancella ricominci.' },
  { chapter: '5 · Segnaposto', shot: 'p09-segnaposto', point: ['dialog', 'type', 'save'],
    say: 'In modalità segnaposto clicca sulla mappa: dai nome, nota e tipo. I segnaposto vengono esportati insieme al percorso.' },
  { chapter: '5 · Salvare', shot: 'p05-salva', point: ['saveAs', 'lib', 'format'],
    say: 'Salva con nome crea il file GPX da mettere sul telefono. Salva in archivio lo tiene nel Planner, e da qui SirJoe Link lo offre al telefono. Puoi esportare anche in FIT, KML, TCX, GeoJSON e CSV.' },
  { chapter: '5 · Salvare', shot: 'p06-archivio', point: ['name', 'ok'], say: 'Dai un nome al percorso e salvalo in archivio.' },
  { chapter: '5 · Mappe', shot: 'p11-satellite', point: ['base'],
    say: 'Dal menu delle mappe scegli anche il satellite, la topografica Esri o CyclOSM. Sul satellite puoi mostrare nomi e confini.' },
  { chapter: '5 · Mappe', shot: 'p12-mappe', point: ['file', 'folder', 'url', 'add'],
    say: 'Con Mappe aggiungi mappe tue: file MBTiles, SQLiteDB, PMTiles, GeoTIFF, KMZ, immagini con world file o carte OziExplorer, oppure fonti online XYZ, WMS e WMTS. Ogni mappa si usa come base o sovrapposta con la trasparenza che vuoi.' },

  // ------------------------------------------------------------ sala analisi
  { chapter: '6 · Sala analisi', shot: 'p13-uscite', point: ['list', 'add'],
    say: 'Nella scheda Uscite arrivano le uscite registrate con il telefono. Clicca un\'uscita per aprire la sala analisi.' },
  { chapter: '6 · Sala analisi', shot: 'p14-analisi', point: ['stats', 'color', 'play', 'compare', 'climbs'],
    say: 'Qui vedi tutti i numeri dell\'uscita e la traccia colorata per velocità, pendenza o altitudine.' },
  { chapter: '6 · Sala analisi', shot: 'p15-replay', point: ['play', 'slider', 'now'],
    say: 'Il replay ripercorre l\'uscita in velocità, e con il cursore vai a un momento preciso.' },
  { chapter: '6 · Sala analisi', shot: 'p16-confronto-salite', point: ['compare', 'out', 'climbs', 'video'],
    say: 'Confronta l\'uscita con il percorso pianificato per vedere le deviazioni, controlla le salite, e con Crea video ottieni un filmato di venti secondi con mappa, numeri e logo, pronto per i social.' },
  { chapter: '6 · Le mie uscite', shot: 'p17-mie-uscite', point: ['rides'],
    say: 'Con la casella le mie uscite vedi sulla mappa tutti i giri fatti, in viola.' },

  // ---------------------------------------------------------------- sirjoe link
  { chapter: '7 · SirJoe Link', shot: 'p18-link', point: ['link', 'qr', 'manual'],
    say: 'SirJoe Link collega telefono e PC nella rete Wi-Fi di casa, senza cloud. Sul PC premi SirJoe Link: compare un QR code, e sotto indirizzo e PIN.' },
  { chapter: '7 · SirJoe Link', phone: 'a-link', side: ['Percorsi → SirJoe Link', 'Inquadra il QR del PC', 'Oppure Inserisci codice: indirizzo e PIN'],
    say: 'Sul telefono vai in Percorsi, SirJoe Link, e scegli inquadra il QR del PC, oppure inserisci a mano indirizzo e PIN.' },
  { chapter: '7 · SirJoe Link', phone: 'a-scan', side: ['Telefono in verticale', 'A 20–30 cm dallo schermo', 'Stessa rete Wi-Fi del PC'],
    say: 'Inquadra il codice tenendo il telefono a venti, trenta centimetri dallo schermo. Telefono e PC devono essere sulla stessa rete Wi-Fi.' },
  { chapter: '7 · SirJoe Link', phone: 'a-linkok', side: ['Ricevi i percorsi dal PC', 'Invia le uscite al PC', 'Arrivano nella scheda Uscite'],
    say: 'Una volta collegato puoi ricevere i percorsi dal PC, oppure inviare le tue uscite, che arrivano subito nella sala analisi.' },
  { chapter: '7 · SirJoe Link', shot: 'p18-link', point: ['ping', 'fw'],
    say: 'Se il telefono non si collega, apri nel suo browser l\'indirizzo di prova: se non risponde, premi sblocca nel firewall di Windows e conferma.' },

  // -------------------------------------------------------------------- fine
  { title: 'Buone pedalate!', list: ['Prima di partire: batteria carica e percorso controllato', 'Non usare il telefono mentre pedali', 'Sul posto valgono la segnaletica e i divieti', 'Manuale completo: docs/manuale nel progetto'],
    say: 'Ricorda: parti con la batteria carica, controlla il percorso prima di partire, non usare il telefono mentre pedali, e rispetta sempre segnaletica e divieti. Trovi tutto nel manuale in PDF. Buone pedalate con SirJoe MTB!', end: true }
];
