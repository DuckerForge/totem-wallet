# seeker-crowd

Uno scanner solo, per tutti gli utenti.

Legge cosa comprano i portafogli Seeker e pubblica un file da pochi KB. L'app fa
un GET e basta: zero crediti Helius per chi la usa, identico con dieci utenti o
con centomila. Il giro sul telefono resta solo come ripiego.

## Perché sta nel piano gratuito

| limite di Cloudflare | come ci sta dentro |
|---|---|
| 50 chiamate in uscita per esecuzione | ogni esecuzione legge un terzo dei portafogli, mai tutti |
| 1.000 scritture KV al giorno | 2 per esecuzione, 288 esecuzioni = 576 |
| 100.000 richieste al giorno | l'app legge un file, e lo tiene in cache |

E il conto crediti Helius: trovare chi si è mosso costa **una chiamata ogni cento
portafogli**, perché uno swap sposta sempre il saldo, fosse solo per la
commissione. Solo chi si è mosso costa una lettura in più. Circa **670.000 crediti
al mese** su una chiave da un milione.

## Installazione

```sh
npm i -g wrangler
wrangler login
wrangler kv namespace create SEEKER          # copia l'id in wrangler.toml
wrangler secret put HELIUS_URL               # l'URL Helius completo, con la chiave
wrangler deploy
# l'elenco dei portafogli, una volta sola, spezzato nei pezzi che il worker legge:
python3 roster.py > /tmp/kv_roster.json
npx wrangler kv bulk put --binding=SEEKER --remote /tmp/kv_roster.json
```

Poi in `local.properties`:

```
clearsign.crowdUrl=https://seeker-crowd.<tuo-sottodominio>.workers.dev/
```

## Cosa pubblica

```json
{"at": 1789400000000, "followed": 10527, "window": 86400000,
 "rows": [{"mint": "...", "sym": "SNDK", "wallets": 4, "whales": 3, "buys": 5, "sol": 7.4, "last": 1789399000000}]}
```

Solo monete comprate da almeno tre portafogli diversi. Sotto tre è una persona,
non una tendenza.

## Perché lo stato è in tre pezzi (15/09/2026)

Il worker moriva di **CPU** (`exceededCpu` a 10 ms, il limite del piano gratuito) mentre
leggeva e riscriveva lo stato: un JSON da 612 KB con 10.527 saldi come chiavi. Ora:

- `bal`: i saldi come `Float64Array` grezzo (84 KB), letto come buffer, indice = posizione nel
  censimento. Niente parse, niente stringify.
- `rw` e `rd:<k>`: il censimento a pezzi (balene con indice; blocchi di cento delfini). Ogni giro
  legge solo i blocchi che tocca.
- `state2`: acquisti, cursore, e un'impronta del pubblicato, così `crowd` si scrive solo quando
  cambia. Tre scritture per giro al massimo, un giro ogni cinque minuti: sotto le mille al giorno.

Le chiavi vecchie `state` e `roster` non servono più.

## La sentinella (25/09/2026)

Scout e' morto due volte in silenzio: il **15/09** di CPU (`exceededCpu` a 10 ms
mentre leggeva 612 KB di JSON) e il **16/09** con le mille scritture KV esaurite
dalle letture degli utenti. Le due volte il primo segnale e' stato accorgersene
guardando.

Ora la scansione scrive come sta in `/clearsign/health`, dentro la stessa `put`
dello stato: zero scritture in piu'. E `watchman.mjs` lo legge da **GitHub
Actions**, ogni mezz'ora.

Sta fuori da Cloudflare di proposito. Il terzo guasto probabile e' l'account che
supera le centomila richieste al giorno: Cloudflare smette di invocare il worker,
cron compresi, e una sentinella che vivesse dentro il worker morirebbe con lui,
zitta, come le altre due volte.

Guarda il sintomo e non le cause. La domanda principale e' una sola, *Scout si e'
aggiornato negli ultimi 25 minuti*, e quella prende tutti e tre i guasti noti piu'
il quarto che non abbiamo previsto. Gli altri tre controlli sono le scritture KV
sopra 750, tre giri di fila che finiscono la riserva di chiamate, e la classifica
pubblicata piu' vecchia di sei ore.

```bash
# In locale, per vedere come sta adesso.
ARCHIVE_URL=https://<db>.firebasedatabase.app node tools/seeker-worker/watchman.mjs

# Su GitHub serve il secret ARCHIVE_URL nelle impostazioni del repo.
```

**La prova che conta non e' il rosso, e' la mail.** Un controllo che diventa rosso
senza avvisare nessuno e' lo stesso problema di prima. Si lancia il workflow a
mano con `stale_min = 1`, si guarda la casella, e solo quando la mail e' arrivata
la sentinella esiste. Da sapere: GitHub disattiva i cron su un repo pubblico dopo
60 giorni senza commit.
