<p align="center"><img src="docs/logo-by-sirjoe.png" alt="BY SIRJOE" width="360"></p>

# SirJoe MTB

Ciclocomputer e navigatore per mountain bike su Android, con mappa a tutto schermo, cursore a forma di MTB e percorsi fuoristrada. Progetto personale, gratuito e open source, **non destinato alla vendita**.

> ⚠️ Prima di usare l'app leggi le [Avvertenze d'uso](AVVERTENZE.md). L'app le mostra al primo avvio e vanno accettate.

## Funzioni

**Schermata Mappa**
- Mappa a tutto schermo (OpenTopoMap con sentieri e curve di livello, oppure OpenStreetMap standard).
- Sovrapposizione degli itinerari MTB segnati.
- Cursore di navigazione a forma di bici da fuoristrada, con freccia di direzione.
- Mappa orientata nella direzione di marcia oppure a Nord.
- Barra dati sottile e semitrasparente in basso: velocità a sinistra, distanza e ora a destra; un tocco mostra tempo in movimento, salita e quota.
- Pulsanti per mappa, orientamento, centratura, avvio/pausa e fine registrazione.
- Logo "BY SIRJOE" in alto a sinistra.

**Pagina Dati**: velocità attuale, media e massima, distanza, tempo in movimento e totale, ora, quota, dislivello positivo e negativo, pendenza, direzione, distanza al traguardo, precisione GPS, profilo altimetrico del percorso.

**Pagina Percorsi**
- Archivio delle uscite registrate.
- Import: GPX, KML, KMZ, TCX, GeoJSON, CSV.
- Export e condivisione: GPX, KML, TCX, GeoJSON, CSV.
- Segui un percorso: linea sulla mappa, distanza al traguardo, avviso con vibrazione quando esci dal percorso.
- Mappe offline: importa un file MBTiles (o .sqlite, .zip, .gemf).

**Registrazione**: continua a schermo spento (servizio in primo piano con notifica), pausa automatica, filtri sui salti del GPS, avvisi vocali a ogni chilometro.

## Installazione

1. Apri la scheda **Actions** del repository, scegli l'ultima esecuzione di "Compila APK" e scarica l'artefatto **SirJoeMTB-apk**.
   In alternativa crea un tag (es. `v1.0.0`): l'APK viene allegato automaticamente alla **Release**.
2. Copia `SirJoeMTB.apk` sul telefono e aprilo. Android chiederà di consentire l'installazione da questa fonte.
3. Al primo avvio accetta le avvertenze e concedi il permesso di posizione (e delle notifiche, su Android 13+).

Requisiti: Android 8.0 o superiore, GPS, Google Play Services.

## Compilare in locale

Apri la cartella con Android Studio (versione recente) e premi *Run*. Da riga di comando, con Gradle 8.10+ e JDK 17:

```
gradle assembleDebug
```

## Struttura del codice

| Cartella | Contenuto |
| --- | --- |
| `app/src/main/java/.../ui` | Pagine Mappa, Dati, Percorsi, Info e profilo altimetrico |
| `app/src/main/java/.../service` | Servizio di registrazione GPS in background |
| `app/src/main/java/.../data` | Stato condiviso, impostazioni, lettura e scrittura dei formati |
| `app/src/main/assets/avvertenze.txt` | Testo delle avvertenze mostrato nell'app |

## Dati e crediti

- Dati cartografici © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), licenza ODbL.
- Stile mappa [OpenTopoMap](https://opentopomap.org) (CC-BY-SA).
- Itinerari MTB: [waymarkedtrails.org](https://mtb.waymarkedtrails.org).
- Libreria mappe: [osmdroid](https://github.com/osmdroid/osmdroid) (Apache 2.0).

I server di mappe gratuiti sono gestiti da volontari: l'app si identifica correttamente, salva in cache solo ciò che visualizzi e **non scarica aree intere in blocco**. Per l'uso offline importa file MBTiles che hai diritto di usare.

## Roadmap

- Navigazione fuoristrada offline con BRouter e indicazioni di svolta.
- Sensori Bluetooth: frequenza cardiaca, cadenza, potenza.
- Formato FIT, statistiche settimanali e mensili, rilevamento caduta.

## Licenza

Codice rilasciato con licenza [MIT](LICENSE), senza alcuna garanzia. Vedi anche le [Avvertenze d'uso](AVVERTENZE.md).
