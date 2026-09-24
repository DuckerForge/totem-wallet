# Scout — cosa comprano i Seeker

Stato al **14/09/2026, notte**. Tutto compilato, test verdi, installato sul telefono.

---

## 1. Il dato, misurato una volta e per sempre

Il **Seeker Genesis Token** è un NFT Token-2022 **non trasferibile**, coniato una volta
per telefono. Un token uguale un telefono, e l'insieme può solo crescere.

| | |
|---|---|
| gruppo del token | `GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te` |
| autorità che conia | `GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4` |
| il tuo | membro **#29491** |
| Seeker sulla catena | **120.520** |
| con almeno 1 SOL | **10.527** (8,7%) |
| balene (≥10 SOL) | **1.252** |
| ≥100 SOL | 134 · ≥500 SOL: 25 |
| SOL totale sui Seeker | 113.364 |
| Seeker mediano | **0,032 SOL**, cioè sette dollari |

**Non stanno farmando.** Campione di 250 portafogli sotto 1 SOL: **0%** mai usato, 11%
attivo negli ultimi 7 giorni, **89% fermo**, mediana **209 giorni** dall'ultima mossa.
Hanno acceso il telefono, riscosso gli airdrop e mollato.

**Avvertenza onesta:** leggiamo il portafoglio del Seed Vault. Chi usa il Seeker ma trada
da Phantom ci risulta morto pur non essendolo. Ma se i premi dell'ecosistema sono legati a
*quel* wallet con *quell'*NFT, i 10.527 attivi sono la coorte giusta.

**Le balene:** 1.252 portafogli, **15.192.795 $** in totale, mediana 3.533 $, il più grosso
**2.161.310 $**.

**Cosa tengono** (campione di 2.500 su 120.520, 65% ha almeno un token): su 34 cose,
**19 valgono zero** — SEKR, CHAPTER2, HM, MF, PDT, $CWIF, JUPENGU, GRUMPY, NAMI, PLANK,
RETROPASS, HAPPY, PAW, SI, JPMB, by-svUSDC, by-peUSDC, ASTROPASS, IQ50. Sono airdrop
arrivati col telefono. I soldi veri: USDC 145 $ a testa, JUP 256 $, PENGU 150 $,
JitoSOL 116 $, PUMP 93 $, SKR 80 $, USDT/TSLAx 59 $.

**Il wSOL non c'è**, e non è un bug: su 25 balene, **zero** ne tengono. Nasce e muore dentro
lo swap (Jupiter lo apre e richiude nella stessa transazione).

---

## 2. L'architettura: uno scansiona, leggono tutti

Il modello "ogni telefono scansiona" non regge con più utenti: 100 utenti = 100 scansioni
identiche e la chiave da 1M finisce in mezza giornata.

```
Cloudflare Worker (cron */4)  ──scrive──>  KV "crowd"  ──GET──>  ogni telefono
        │                                                         (pochi KB, 0 crediti)
        └── chiave Helius DEDICATA, separata da quella dell'agente
```

**Worker:** il codice sta in `tools/seeker-worker/`. L'indirizzo del worker, l'id del
namespace KV e il nome dell'account non stanno qui: il worker espone un proxy RPC senza
autenticazione davanti alla chiave Helius, e un indirizzo scritto in un file pubblico e' un
invito a bruciarne la quota. Chi lo rimette in piedi usa il suo, con `npx wrangler deploy`.
Segreti da mettere con `wrangler secret put`: `HELIUS_URL`, `FB_SECRET`, `RX_KEY`.

Comandi (da `tools/seeker-worker/`): `npx wrangler deploy`,
`npx wrangler kv key get --binding=SEEKER state --remote`, `npx wrangler tail`.

### Il trucco che lo rende gratis
Chiedere **chi si è mosso** costa **una chiamata ogni cento portafogli** (`getMultipleAccounts`),
perché uno swap cambia sempre il saldo, fosse solo per la commissione. Solo chi si è mosso
costa una lettura in più. Una chiamata per portafoglio costerebbe cento volte tanto.

### I limiti veri del piano gratuito
| limite | come ci sta |
|---|---|
| **50 chiamate in uscita per esecuzione** | riserva contata a 45, balene tutte + 5 blocchi di delfini a turno |
| 1.000 scritture KV al giorno | 2 per giro × 360 giri = **720** |
| 100.000 richieste al giorno | l'app legge un file con cache di 5 minuti |
| 1M crediti Helius/mese | ~**670.000** |

### Due bug già trovati e corretti (non rifarli)
1. **Le esecuzioni morivano a metà.** 34 chiamate saldi + fino a 4 per ogni portafoglio in
   movimento = oltre 80, e la 51ª *lancia*. Moriva **prima di salvare**, quindi il cursore
   non avanzava e nessun acquisto veniva mai registrato. → riserva contata + **si salva sempre**.
2. **Budget sbilanciato.** 26 chiamate per i saldi ne lasciavano 19: 3 portafogli approfonditi
   su 11 che si erano mossi. → delfini da 13 a 5 blocchi, ciclo da 5 a 4 minuti.

---

## 3. L'app

**Home → Scout** (ha sostituito "Tocco", che è finito in *Altro*, intatto).
Icona: anello, linea che sale dentro, manico. Tre tratti — prima aveva anche telefono,
quattro tacche e manico a 26dp, cioè una macchia.

Ordine della pagina, che è deliberato:
1. **Striscia**: 10.527 seguiti su 120.520, con l'**anello che si svuota** (prossimo giro fra…).
   In ritardo diventa un archetto che gira: "sto guardando…".
2. **Cosa tengono i 120.520** — la linea, sopra quello che vale (nome + $ + %), sotto le
   19 scure che valgono zero. Legenda **fuori** dal disegno.
3. **In diretta dai Seeker** — `Vesper·7r ha comprato KITTY`, livello a sinistra nel cerchio
   (`2.7k`), logo della moneta come distintivo, `Compra` a destra → swap normale con scontrino.
4. **Classifica** — solo monete con **3+ portafogli diversi**.
5. **Le balene** — 60 portafogli, tocca e si apre Solscan.

### Regole che non vanno rilassate
- **Solo acquisti, mai possedimenti.** Una classifica dei possedimenti di questa folla
  restituisce airdrop da zero dollari.
- **Tre portafogli diversi o non esiste.** Su 60 balene in 7 giorni la "tendenza" migliore
  era un tizio che comprava TOS dieci volte.
- **Soglia 0,005 SOL.** 13 acquisti su 99 stavano sotto: erano riscossioni di airdrop, dove
  l'unica uscita è commissione + affitto del conto. A 0,02 spariva anche il segnale vero.
- **Il soprannome nasce dall'indirizzo** (`SeekerCrowd.nickname`), stabile ovunque, senza
  elenchi da tenere. Due caratteri dell'indirizzo restano attaccati.

### File
`core/`: `SeekerCrowd.kt` + `SeekerCrowdTest.kt`
`app/`: `SeekerScan.kt` `SeekerFeed.kt` `SeekerCard.kt` `CrowdFeed.kt` `SeekerHoldings.kt`
`SeekerWhales.kt` `SeekerKeeper.kt` `PriceChart.kt`
`assets/`: `seekers.txt` (10.527, 537 KB) `seeker_holdings.json` `seeker_whales.json`
`local.properties`: `clearsign.scanRpcUrl`, `clearsign.crowdUrl`

---

## 4. Altro fatto stasera

- **Grafico prezzi** sotto la moneta nello swap: **5 ore** (candele 5m), **2 giorni** (1h),
  **3 mesi** (1g). Bucket veri di GeckoTerminal, non lo stesso dato ridisegnato. Fornitore
  diverso da chi quota lo swap. Niente storia = niente grafico, mai una riga piatta.
- **"Manda soldi con un link"** (era "Regala con un link"): **un pulsante alla volta** —
  Rivedi, poi Tieni premuto. La X per uscire c'è.
- **Tema predefinito: Menta** (`mintSoft`). **Solana** sbloccato e gratis (erano 5 premium,
  ora 4). **Solana Flow** = colori di casa, solo il bordo che scorre.
- **Bordo vivo su tutto**: 50 punti che lo disegnavano a mano passano da `cardBorder()`.
- **Bug bordo**: il gradiente corre in diagonale ma lo spostavo in orizzontale → 0,82 di
  periodo per ciclo, lo scatto era il 18% mancante. Ora trasla lungo il proprio asse.
- **Bug salto della home**: cambiando scheda Compose butta la composizione, il portafoglio
  ripartiva da `null`. Ora `Portfolio.cached()` tiene l'ultimo valore per la vita del processo.

---

## 5. Da fare domani

1. **Alzare il tetto giornaliero.** È ancora **0,047 SOL**, cioè una mossa al giorno.
   Finché sta lì qualsiasi automazione parte una volta e si ferma. **Decisione tua.**
2. **Auto trading dal segnale della folla.** Collegabile, ma nel modo sensato: due o più
   balene sulla stessa moneta entro un'ora → entra fra le candidate → decide il collare.
   Non "copia tutto": con 15 minuti di ritardo copiare una memecoin spesso vuol dire
   comprare l'uscita di chi l'ha presa prima.
3. **Niente è stato committato.** Una sessantina di file fra modificati e nuovi. Dire su
   che ramo.
4. Verificare di giorno che la diretta si riempia (di notte 4 eventi, di giorno ne ho
   misurati 99 in due ore).
