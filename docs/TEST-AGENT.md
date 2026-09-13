# I test dell'Agent Gate

Tutto quello che serve per dimostrare che funziona, e per rifarlo davanti a
qualcuno. Diviso in due: i test automatici, che girano in un secondo senza
telefono, e i test sul dispositivo, quelli da mostrare.

---

## La domanda a cui questi test rispondono

> Una volta "connesso", l'agente può fare uno swap da solo, senza firma?

**No, e non esiste nemmeno una connessione.** Non c'è nessuna sessione da
aprire, nessun permesso da concedere una volta per sempre, nessun OAuth. Un
agente può soltanto **chiedere**. Ogni singola operazione passa dal telefono,
dalla schermata di Apex e dalla tua impronta: anche la centesima di fila.

È la differenza con i servizi tipo PayBox, dove al momento dell'OAuth colleghi
un conto e da lì in poi il servizio opera. Qui non c'è un conto da collegare:
l'autorizzazione è il tuo dito, ogni volta.

Per questo nella linguetta Agent non trovi la scritta "connesso". Trovi scritto
che niente è pre-autorizzato, e un contatore "firmate senza di te: **mai**".

---

## 1. Test automatici, senza telefono

### Il motore che confronta dichiarato ed effettivo

`IntentGuardTest` in `:core`, dieci casi. Sono la matrice degli attacchi che un
agente può provare:

| Caso | Cosa fa l'agente | Atteso |
|---|---|---|
| `transferMatches` | dichiara il vero | verificato |
| `transferAmountLieIsBlocked` | dichiara 5, la transazione manda 50 | **bloccato** |
| `transferWrongRecipientIsBlocked` | dichiara un destinatario, ne paga un altro | **bloccato** |
| `undeclaredExtraOutflowIsBlocked` | dichiara un token, ne fa uscire anche un secondo | **bloccato** |
| `solTransferToleratesFeeAndRent` | dichiara il vero, ma la commissione sposta i decimali | verificato |
| `swapMatchesWithinSlippage` | swap onesto, ricevuto poco meno dell'atteso | verificato |
| `swapWithNoInflowIsBlocked` | dichiara uno swap, non entra nulla | **bloccato** |
| `burnMatchesAndRentBackIsFine` | burn onesto, torna solo il rent | verificato |
| `otherWithHiddenApprovalIsBlocked` | dichiara "altro", nasconde una delega illimitata | **bloccato** |
| `italianSummaryReadsNaturally` | leggibilità del testo mostrato | ok |

```
gradle :core:test :app:testDebugUnitTest      # 76 test in totale
```

### La metà su PC

```
python3 scripts/apex_agent/cli.py selftest
```

Venti controlli: base58 e lunghezze compatte su vettori noti, la **transazione
di riferimento byte per byte** identica alla versione già provata, l'URL che si
ricompone e si ri-legge identico, il QR che si genera, e con la rete un blockhash
valido, una simulazione senza errori e un preventivo Jupiter con la rotta.

### Il protocollo MCP

```
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","clientInfo":{"name":"test","version":"1"},"capabilities":{}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | python3 scripts/apex_agent/mcp_server.py
```

Devono uscire due risposte JSON-RPC valide e nient'altro su stdout. La notifica
`notifications/initialized` non deve produrre risposta.

Per la versione via URL:

```
APEX_MCP_TOKEN=prova python3 scripts/apex_agent/mcp_server.py --http 8765
curl -s -X POST http://127.0.0.1:8765/mcp -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'   # 401
curl -s -X POST 'http://127.0.0.1:8765/mcp?token=prova' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'                                          # 7 strumenti
```

---

## 2. Test sul dispositivo, quelli da mostrare

Uno script li lancia nell'ordine giusto. Manda la richiesta al Seeker via cavo se
è collegato, e genera sempre anche il QR da inquadrare.

```
./scripts/test-agent.sh honest          # l'agente dice il vero  -> puoi firmare
./scripts/test-agent.sh liar            # l'agente mente         -> Apex blocca
./scripts/test-agent.sh swap            # swap reale Jupiter, sola firma
./scripts/test-agent.sh result <req_id> # legge l'esito dalla catena
```

### Esito del 12 settembre 2026

| Test | Cosa è successo | Esito |
|---|---|---|
| Onesto, con invio | ricevuta con intento verificato, firma col Seed Vault, transazione trasmessa | **passato** |
| Lettura dell'esito | firma trovata dal PC tramite l'etichetta memo, senza che il telefono richiamasse nessuno | **passato** |
| Bugiardo | "L'agente non dice la verità: ha dichiarato «swap 0.000001 SOL → 1 USDC», ma la transazione in realtà: nessuna entrata di USDC" e **firma bloccata**, nessun pulsante | **passato** |
| Swap v0 | preventivo Jupiter, intento derivato dal preventivo, ricevuta accettata, Seed Vault ha chiesto la firma | **passato** |
| Attribuzione | mostra "App su questo telefono: com.android.shell" come origine verificata e "nome dichiarato dall'agente, non verificato" | **passato** |

Firma del caso onesto:
`ZW53GWDH6dkvWifprwDmZd4sm6wVaybgcF48HAvhsQwFkxfyxuwknxQg7GjWkXoayUvB3CgqTbbBfcZT6mrWZF9`
allo slot 446487803. Costo reale: 0,000006 SOL.

### Numeri utili

| | Trasferimento | Swap Jupiter |
|---|---|---|
| Byte della transazione | 263 | 636 |
| Lunghezza dell'URL | 723 | 1511 |
| Moduli del QR | 113 | 137 |
| Correzione d'errore | M | L |

Entrambi si inquadrano da un monitor. Lo swap è più denso: se fai fatica,
allontana il telefono o usa il link invece del QR.

---

## 3. Cosa resta da provare

- **Proposta scaduta**: aspettare oltre novanta secondi prima di inquadrare, e
  verificare che il telefono dica che il blockhash non esiste più invece di
  firmare qualcosa di inatteso.
- **Percorso QR reale**: finora sul dispositivo ho spinto le richieste via cavo.
  Va provato inquadrando davvero il codice dallo schermo del computer.
- **Percorso link**: mandarsi l'URL in chat e toccarlo sul telefono.
- **Connettore via URL** su Claude, ChatGPT e Grok, seguendo `SETUP-AGENT.md`.
- **Delega nascosta sul dispositivo**: il caso è coperto dai test automatici, ma
  per il video vale la pena costruire una transazione con un `approve` illimitato
  dichiarato come semplice invio.

---

## 4. Se qualcosa va storto

**Il pulsante di firma non c'è.** Guarda se in alto c'è una banda rossa: allora
è un blocco voluto, e la riga ti dice quale discrepanza ha trovato. Se invece
gira un indicatore di attesa, la simulazione non è ancora tornata.

**"Blockhash not found".** La proposta è scaduta. Rilanciala.

**"Nessun wallet collegato in Apex".** Apri l'app e collega il Seed Vault, o
passa l'indirizzo nella richiesta.

**Il QR non si legge.** È lo swap, che è più denso. Usa il link.


## 5. La busta: matrice dei casi

Automatici: `AgentPolicyTest` in `:core`, 16 casi (auto sotto tetto, chiede sopra il tetto per operazione e su quello giornaliero cumulato, rifiuta destinatario nuovo, rifiuta asset in uscita fuori lista, chiede per un token in entrata non in lista, chiede per un programma nuovo, rifiuta una bugia anche sotto tetto, rifiuta per ritmo, rifiuta in pausa/scaduta/sola lettura, chiede con prezzo sconosciuto, "chiedi sempre" chiede anche per un lamport, swap equo silenzioso, tasso pessimo chiede, invio travestito da swap rifiutato, motivi in italiano). Lato PC: 7 controlli sul collegamento nell'autotest (URL di pairing, token stabile, hello, un lavoro consegnato una volta sola, verdetto che sblocca, timeout pulito, token sbagliato rifiutato).

Sul dispositivo (stesso Wi-Fi, o `adb reverse tcp:8765 tcp:8765`):

| caso | atteso | esito |
|---|---|---|
| pairing da QR | card "Collegato a …", notifica con Pausa/Revoca | da provare |
| invio 0,001 SOL a un contatto, sotto tetto | firmato in silenzio: notifica, bolla, riga "da solo" nel registro, firma tornata al PC | da provare |
| swap Jupiter SOL→USDC sotto tetto | firmato in silenzio | da provare |
| invio sopra la soglia silenziosa | "L'agente chiede", ricevuta, impronta, riga "confermato da te" | da provare |
| invio a indirizzo non in lista | rifiutato, motivo letto dal PC, nessuna tx | da provare |
| intento mentito sotto tetto | rifiutato con la discrepanza, mai "chiedi" | da provare |
| Pausa, poi richiesta | rifiutata "agente in pausa" | da provare |
| Chiudi e recupera | residuo torna, busta e collegamento cancellati | da provare |
