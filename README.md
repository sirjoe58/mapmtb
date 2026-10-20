<p align="center"><img src="docs/logo-by-sirjoe.png" alt="BY SIRJOE" width="380"></p>

# SirJoe MTB

Ciclocomputer e navigatore per mountain bike su Android: mappa a tutto schermo, cursore "bici vista da dietro" con freccia che segue il percorso, creazione di percorsi personalizzati, tracce in tutti i formati principali e condivisione su Strava, app e social. Progetto personale, gratuito e open source, **non destinato alla vendita**.

> ⚠️ Prima di usare l'app leggi le [Avvertenze d'uso](AVVERTENZE.md). L'app le mostra al primo avvio e vanno accettate.

📘 **[Manuale d'uso completo (PDF)](docs/manuale/SirJoe-MTB-Manuale.pdf)**: app Android e Planner per Windows, divisi per applicazione e capitoli.

## Pagine

| Pagina | Cosa fa |
| --- | --- |
| **Mappa** | Mappa a tutto schermo (OpenTopoMap o OpenStreetMap) con itinerari MTB. Cursore: MTB vista da dietro con una freccia davanti che indica dove andare. Orientamento a scelta dal pulsante bussola: direzione di marcia, Nord in alto o libero; la mappa si sposta con un dito e si gira con due dita (tocco lungo sulla bussola = Nord in alto). Barra dati in basso: velocità, distanza, ora. Registrazione, avviso di fuori percorso con vibrazione, distanza al traguardo. |
| **Crea** | Segnaposto modificabili (tocco lungo sulla mappa per crearli, tieni premuto per spostarli; nome, nota, tipo con colore), importabili/esportabili in GPX. Percorso automatico tra segnaposto, o dalla tua posizione a un segnaposto, scegliendo tra *fuoristrada/MTB*, *equilibrato*, *più veloce*, *più corto* e *più lungo*. Percorsi personalizzati: tocca la mappa (o usa "+" col mirino) per aggiungere punti. I tratti seguono strade e sentieri reali (BRouter) oppure vanno in linea retta. Annulla, chiudi anello, inverti, salva, segui subito, condividi. Mostra km e dislivello. |
| **Sosta automatica** | Da fermo per un tempo a scelta (30 s – 5 min) l'app va in sosta: luminosità al minimo, schermo libero di spegnersi, GPS più lento. Quando riparti si risveglia da sola, riaccende lo schermo e riprende registrazione e navigazione. |
| **Dati** | Velocità attuale, media e massima, distanza, tempi, ora, quota, dislivello, pendenza, direzione, distanza al traguardo, precisione GPS, profilo altimetrico. |
| **Percorsi** | Archivio di uscite e percorsi. Importa, esporta, rinomina, modifica nella pagina Crea, condividi. Esporta tutto in un archivio ZIP. Mappe offline. |
| **Percorsi in tutti i sensi** | Un percorso caricato si segue in qualsiasi senso di marcia e partendo da qualsiasi punto: l'app capisce da sola se lo fai al contrario (freccia, distanza al traguardo e avvisi salite si adeguano). Dal menu mappe o da *Percorsi* puoi mostrare sulla mappa tutte le uscite già registrate. |
| **Fantasma e salite** | *Sfida il fantasma* (dal menu di un'uscita registrata): un'icona 👻 rifà la tua uscita precedente in tempo reale e la voce ti dice ogni km se sei avanti o indietro. Sui percorsi con quote l'app avvisa prima delle salite: "tra 200 metri salita al 9 per cento per 800 metri". |
| **Info** | Impostazioni, collegamento a Strava, avvertenze, crediti. |

## Mappe offline

L'app legge le mappe vettoriali gratuite **[OpenAndroMaps](https://www.openandromaps.org)** (formato Mapsforge `.map`) con lo stile **Elevate**, impostato automaticamente sulla variante MTB: sentieri con difficoltà, curve di livello, itinerari ciclabili, tutto senza Internet.

1. Su openandromaps.org scarica la mappa della tua zona (Europa → Italia, file `.zip` per Android/Mapsforge) e lo stile Elevate (`.zip`). Meglio col Wi-Fi: le mappe regionali pesano centinaia di MB.
2. Nell'app: **Percorsi → Mappe offline → Importa**, prima la mappa e poi lo stile.
3. Dal pulsante mappe (livelli) scegli **Offline: OpenAndroMaps**. Dallo stesso menu puoi regolare la dimensione di testi e simboli.

Sono supportati anche file MBTiles/.sqlite/.gemf a immagini. Inoltre le zone viste online restano in memoria (fino a circa 1 GB) e si vedono anche senza segnale.

## Mappa satellitare

Dal pulsante mappe (livelli), nelle pagine **Mappa** e **Crea**:

- **Satellite (MapTiler)**: immagini dettagliate con una chiave gratuita personale.
  1. Registrati su [maptiler.com](https://www.maptiler.com) con il piano **Free** (uso personale e non commerciale).
  2. Apri **Account → API keys** ([cloud.maptiler.com/account/keys](https://cloud.maptiler.com/account/keys/)) e copia la chiave.
  3. Nell'app apri la pagina **Info → Mappa satellitare → Inserisci chiave MapTiler** (oppure scegli **Satellite (MapTiler)** dal pulsante mappe): nella finestra c'è il riquadro *Chiave MapTiler* e il pulsante **Incolla la chiave copiata**; il pulsante *Apri il sito* porta alla pagina delle chiavi. Tocca **Salva**: l'app prova subito la chiave e ti dice se funziona. Poi **Usa il satellite**. Per cambiarla: **Cambia chiave**.
- **Satellite Esri (senza chiave)**: le stesse immagini Esri World Imagery del Planner per computer.

Sopra il satellite restano visibili gli itinerari MTB, il percorso da seguire e la traccia; l'attribuzione del fornitore compare in alto a destra. Serve Internet, ma le zone già viste restano in memoria (nello stesso spazio da circa 1 GB delle altre mappe) e si vedono anche senza segnale: prima di un giro in zona senza campo, scorri la mappa sulla zona con il Wi-Fi. L'app non scarica aree intere in blocco, come chiedono le condizioni dei fornitori. Il piano gratuito MapTiler ha un limite di richieste al mese: la memoria evita di richiedere di nuovo le zone già viste.

## Formati

| Formato | Importa | Esporta | Per chi |
| --- | --- | --- | --- |
| GPX | ✓ | ✓ | Komoot, Strava, Wikiloc, Outdooractive, RideWithGPS, quasi tutte le app |
| FIT | ✓ | ✓ (percorso) | Ciclocomputer Garmin Edge, Wahoo, Hammerhead |
| TCX | ✓ | ✓ | Garmin Connect, Strava |
| KML / KMZ | ✓ | KML | Google Earth, Google My Maps |
| GeoJSON | ✓ | ✓ | Siti web e programmi GIS |
| CSV | ✓ | ✓ | Fogli di calcolo |
| ZIP | ✓ (più tracce) | ✓ (tutte le tracce) | Backup e invio in blocco |

Puoi anche aprire un file traccia direttamente da file manager, email, WhatsApp o Telegram con **"Apri con" / "Condividi" → SirJoe MTB**.

## Condivisione

Da **Percorsi → traccia → Condividi**, oppure dal pulsante **Condividi** nella pagina Crea:

- **Carica su Strava**: le uscite registrate vengono pubblicate come attività sul tuo profilo (vedi sotto come collegare Strava).
- **Immagine per i social**: un'immagine con la forma del percorso, i numeri principali e il logo, con testo e hashtag pronti, per Instagram, Facebook, WhatsApp, Telegram, X e simili.
- **File per app e piattaforme**: scegli il formato e invialo a Komoot, Garmin Connect, Wahoo, Wikiloc, email, Drive… (compaiono le app installate che accettano il file).
- **Salva su file** nel telefono.

### Collegare Strava (una volta sola)

Strava permette il caricamento solo tramite un'"applicazione API" personale, gratuita:

1. Da browser vai su **strava.com/settings/api** e crea un'applicazione (nome a piacere, sito anche `https://github.com`).
2. In **Authorization Callback Domain** scrivi `localhost`.
3. Nell'app: **Info → Collega Strava**, incolla **Client ID** e **Client Secret** e autorizza.

Client ID e Secret restano solo sul telefono. Strava accetta come attività solo le uscite registrate (con orari); i percorsi creati a mano si condividono come file GPX.

## SirJoe MTB Planner per Windows e Mac

Nella cartella `desktop/` c'è il programma per PC con cui preparare percorsi e segnaposto da mandare all'app:

- mappa grande (OpenTopoMap / OpenStreetMap) con itinerari MTB e ricerca dei luoghi;
- percorso a punti (clic per aggiungere, trascina per spostare, clic destro per togliere, clic sulla linea per inserire) calcolato come nell'app: fuoristrada, equilibrato, veloce, corto, lungo o linea retta;
- **disegno a mano libera con tavoletta grafica** (o mouse): con un tipo di percorso il tratto si aggancia ai sentieri, con "Linea retta" resta come disegnato;
- segnaposto con nome, nota e tipo (stessi colori dell'app) e percorso automatico tra segnaposto;
- profilo altimetrico, km, dislivello, tempo stimato, annulla (Ctrl+Z), archivio dei percorsi;
- import GPX, KML, GeoJSON, TCX, FIT, CSV (anche trascinando i file nella finestra); export GPX (percorso + segnaposto, pronto per l'app), FIT, KML, TCX, GeoJSON, CSV.

- **Mappe**: satellite (Esri World Imagery, Sentinel-2), topografica Esri, CyclOSM; fonti online tue (XYZ/TMS, WMS, WMTS, Bing) e file di mappa (MBTiles, SQLiteDB, PMTiles, cartelle di riquadri, GeoTIFF, KMZ, immagini con world file, OziExplorer), come base o sovrapposte con trasparenza;
- **Le mie uscite** sulla mappa: tutte le uscite registrate in viola, cliccabili;
- **Sala analisi** (scheda *Uscite*): traccia colorata per velocità, pendenza o quota; confronto tra percorso pianificato e fatto; replay animato; elenco salite; **video social da 20 secondi** con il logo BY SIRJOE.

Scaricalo da **Releases** → "SirJoe MTB Planner … per Windows e Mac":

- **Windows**: `…-portable.exe` (senza installazione) o `…-setup.exe`.
- **Mac**: `…-mac-arm64.dmg` per Mac con chip Apple (M1 e successivi) oppure `…-mac-x64.dmg` per Mac Intel. Apri il `.dmg` e trascina il programma in **Applicazioni**; nella stessa finestra c'è **LEGGIMI - Installazione Mac.txt**. Il programma non è firmato da Apple: al primo avvio macOS lo blocca, poi vai in **Impostazioni di Sistema → Privacy e sicurezza → Apri comunque**. Se compare "l'app è danneggiata", nel Terminale: `xattr -cr "/Applications/SirJoe MTB Planner.app"`.

### SirJoe Link (telefono ↔ PC, senza cloud)

1. Sul PC premi **📲 SirJoe Link**: compare un QR code. La prima volta Windows chiede di consentire l'accesso alla rete: scegli **Reti private**.
2. Sul telefono: **Percorsi → 📲 SirJoe Link → Inquadra il QR** (telefono e PC sulla stessa rete Wi-Fi), oppure **Inserisci codice** con indirizzo e PIN scritti sotto il QR.
3. Scegli *Ricevi percorsi dal PC* (quelli in archivio e quello aperto) o *Invia le mie uscite al PC*: le trovi nella scheda **Uscite** pronte per la Sala analisi.

Se il telefono non si collega, apri nel suo browser l'indirizzo di prova mostrato dal PC (`http://IP:47891/ping`): se non risponde premi **🛡 Sblocca nel firewall di Windows** nella finestra Link. Sul Mac il pulsante si chiama **🛡 Sblocca nel firewall del Mac** e chiede la password del Mac; il programma deve essere aperto dalla cartella **Applicazioni** (non dal disco .dmg).

In alternativa resta il metodo classico: *Esporta file* in GPX, copialo sul telefono e importalo da **Percorsi → Importa**.

## Installazione

1. Vai su **Releases**: ogni versione ha il suo file, ad esempio `SirJoeMTB-v1.4.1.apk` (in alternativa: **Actions** → ultima esecuzione → artefatto `SirJoeMTB-v…`).
2. Aprilo sul telefono e consenti l'installazione da questa fonte. Per aggiornare installa il nuovo APK sopra il vecchio: tracce, mappe e impostazioni restano (dalla 1.4.1 tutte le versioni sono firmate con la stessa chiave, in `keystore/`; serve solo per questo progetto personale).
3. Al primo avvio accetta le avvertenze e concedi posizione e notifiche.

Requisiti: Android 6.0 o superiore (ad esempio Redmi 4X), GPS, Google Play Services.

## Compilare in locale

Apri la cartella con Android Studio e premi *Run*. Da riga di comando, con Gradle 8.10+ e JDK 17: `gradle assembleDebug`. I test (`gradle testDebugUnitTest`) verificano scrittura e lettura del formato FIT; GitHub Actions controlla inoltre i file con la libreria indipendente `fitparse`.

## Dati e crediti

- Dati cartografici © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), licenza ODbL.
- Stile mappa [OpenTopoMap](https://opentopomap.org) (CC-BY-SA). Itinerari MTB: [waymarkedtrails.org](https://mtb.waymarkedtrails.org).
- Calcolo dei percorsi: [BRouter](https://brouter.de) (server pubblico).
- Mappe offline: [OpenAndroMaps](https://www.openandromaps.org) e stile Elevate (scaricati dall'utente, secondo le loro condizioni); motore [Mapsforge](https://github.com/mapsforge/mapsforge).
- Libreria mappe: [osmdroid](https://github.com/osmdroid/osmdroid) (Apache 2.0).
- Carattere del logo: Russo One di Jovanny Lemonad, [SIL Open Font License](docs/licenses/OFL-RussoOne.txt).
- Strava e gli altri nomi citati sono marchi dei rispettivi proprietari; il progetto non è affiliato a loro.

I server gratuiti di mappe e calcolo percorsi sono gestiti da volontari: l'app si identifica, salva in cache solo ciò che visualizzi, fa una sola richiesta di calcolo per ogni punto aggiunto e **non scarica aree intere in blocco**.

## Licenza

Codice rilasciato con licenza [MIT](LICENSE), senza alcuna garanzia. Vedi anche le [Avvertenze d'uso](AVVERTENZE.md).
