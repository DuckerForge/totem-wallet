# L'archivio condiviso

Il servizio teneva in KV quello che un portafoglio possiede, con mezz'ora di
scadenza. Due difetti, e il secondo era serio.

**Il primo.** Una cache a tempo paga solo se la gente arriva insieme. Metà utenti
la mattina e metà la sera vuol dire che nessuno trova mai niente di caldo, e
ognuno paga la lettura per intero.

**Il secondo.** Ogni portafoglio aperto a freddo costava **una scrittura KV**, e
il piano gratuito di Cloudflare ne dà mille al giorno in tutto. Il cron se ne
prende già circa 430. Restavano meno di 600 aperture al giorno, dopo le quali il
worker non poteva più scrivere niente, nemmeno la classifica. Le letture degli
utenti avrebbero spento la scansione.

Quindi non è più una cache, è un archivio, e sta su Firebase Realtime Database,
dove il piano gratuito conta lo spazio e il traffico e **non le scritture**.
Diecimila portafogli con le loro monete sono circa sette megabyte su un giga.

Uno legge, e tutti trovano la stessa risposta già pronta per mezza giornata, a
qualsiasi ora.

## Come è fatto

- `/clearsign/holdings/<indirizzo>` = `{ at, top: [{m, q, u}], unpriced }`.
- `/clearsign/coin/<mint>` = `{ at, v: { "<installazione>": { s, w, at } } }`, il
  verdetto sul web di una moneta. `s` vale 1 per uno stop. Lo scrivono i
  telefoni, attraverso il worker (`POST /?cc=<mint>`), e lo legge chiunque.

  Serve a non pagare mille volte la stessa risposta: la ricerca costa un
  centesimo e la domanda «questa moneta è una truffa» non dipende da chi la fa.
  Chi arriva primo paga e lascia il verdetto; i prossimi lo trovano. Funziona
  anche per i telefoni senza nessuna chiave, che prima quella rete non ce
  l'avevano proprio.

  **Un no vale da chiunque, un sì vale da due.** Quelle righe le scrive della
  gente, e un APK modificato ci mette quello che vuole. Ma le due bugie non
  costano uguale: «stop su una moneta buona» fa saltare un acquisto, «pulita su
  una truffa» fa comprare una truffa a tutti. Quindi uno stop vale subito e da
  uno solo, mentre un «pulita» conta solo quando lo dicono due installazioni
  diverse (`CoinCheck.Shared.MIN_CLEAN`). Il mondo paga due ricerche per moneta
  invece di mille, e chi volesse togliere quel controllo agli altri deve
  arrivare primo su quel mint con due installazioni, ottenendo di far saltare un
  cancello su sette e nient'altro: tutti gli altri girano sul telefono.

  Chi scrive si firma con **un numero a caso fatto una volta**, non col
  portafoglio. Scrivere «la paghetta X sta controllando il mint Y» in un archivio
  pubblico direbbe al mondo che quella paghetta sta per comprare quella moneta,
  qualche secondo prima che lo faccia. Per contare due installazioni diverse
  basta che siano diverse, non sapere quali.

  Il worker tiene al massimo sei righe per moneta, le più recenti, e butta quelle
  più vecchie di una settimana. Non tocca il KV.
- Il worker scrive con il segreto, che sta in `FB_SECRET` fra i segreti di
  Cloudflare. **Mai in un file, mai nel repo, mai dentro l'APK.**
- Il telefono legge senza chiave, perché il ramo è pubblico in lettura e sono
  dati pubblici della catena. L'indirizzo sta in `clearsign.archiveUrl`.
- `holdingsOf` serve quello che trova se ha meno di dodici ore, e rilegge dopo
  aver risposto se ha passato la mezz'ora. Va sulla catena solo se non c'è niente.
- Una seconda sveglia, cinque minuti dopo la scansione, scalda le facce che
  compaiono nella diretta: sono quelle che la gente apre, e sono uguali per
  tutti. Esecuzione separata perché le cinquanta chiamate in uscita del piano
  gratuito si contano per esecuzione e la scansione le vuole tutte.
- Senza `FB_URL` o `FB_SECRET` il worker torna a comportarsi come prima, KV e
  mezz'ora. Si può mettere in produzione prima del segreto.

## Le regole

Il database ospita **altri progetti**. Le regole sono un albero solo e quello che
si concede in alto non si può togliere in basso, quindi non si sostituiscono: si
aggiunge un ramo e basta.

Da aggiungere dentro `"rules"`, accanto a quelli che ci sono già:

```json
"clearsign": {
  ".read": true,
  ".write": false,
  ".indexOn": []
}
```

Lettura aperta perché sono dati pubblici della catena e il telefono non deve
portarsi dietro nessuna chiave. Scrittura chiusa a tutti: il worker scrive con il
segreto, che passa sopra le regole.

## Metterlo in piedi

```bash
cd tools/seeker-worker
npx wrangler secret put FB_SECRET     # il segreto da Firebase, incollato qui e in nessun altro posto
npx wrangler deploy
```

Nel telefono, in `local.properties`:

```
clearsign.archiveUrl=https://<your-project>-default-rtdb.<region>.firebasedatabase.app
```

## Controllare che funzioni

```bash
# Deve rispondere, e la seconda volta deve essere immediata.
curl -s "$CROWD/?w=<un indirizzo del censimento>" | head -c 200

# E la stessa risposta deve comparire nell'archivio, leggibile senza chiave.
curl -s "https://<your-project>-default-rtdb.<region>.firebasedatabase.app/clearsign/holdings/<indirizzo>.json" | head -c 200
```

Nella scheda Usage di Firebase si vede lo spazio e il traffico consumati. I
limiti del gratuito sono 1 GB tenuti e 10 GB al mese scaricati. Una risposta pesa
meno di un chilobyte, quindi i 10 GB sono milioni di letture.

## Il segreto

Il segreto legacy apre **tutto** il database, anche i rami degli altri progetti.
Se finisce in un posto sbagliato si rifà: stessa pagina, `Add secret`, poi si
cancella il vecchio e si aggiorna `FB_SECRET` su Cloudflare.
