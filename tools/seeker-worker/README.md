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
# l'elenco dei portafogli, una volta sola:
wrangler kv key put --binding=SEEKER roster --path=../../app/src/main/assets/seekers.txt
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
