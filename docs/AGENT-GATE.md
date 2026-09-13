# Agent Gate — il co-firmatario hardware per agenti AI

Un agente (app, script, browser) **non possiede mai una chiave**. Consegna a Apex una transazione e un *intento dichiarato*; Apex simula i byte veri, confronta la dichiarazione con l'effetto (`IntentGuard`, in `:core`, testato) e solo poi l'utente firma col Seed Vault tenendo premuto con biometria.

## Deep link

```
apex://agent/sign?tx=<base64url>&intent=<json>[&account=<pubkey>][&cluster=mainnet-beta][&callback=<uri>][&send=0|1]
```

- `tx` — transazione serializzata (legacy o v0), base64 url-safe o standard.
- `intent` — JSON: `{"action":"transfer|swap|burn|other","outMint":"SOL|<mint>|<symbol>","outAmount":0.1,"inMint":"USDC","inAmount":10,"to":"<base58>","agent":"nome","reason":"perché"}`.
- `account` — wallet da usare (default: quello collegato in Apex). `send=0` = solo firma, restituisce la tx firmata.
- `callback` — se presente, Apex riapre quell'URI con `?signature=…&signed_tx=…` oppure `?error=…`. Il risultato arriva anche via `setResult` (extras `signature`, `signed_tx`, `error`).

## Cosa controlla IntentGuard

- ogni uscita deve essere quella dichiarata (mint/simbolo) e nell'importo (1% + fee/rent per SOL);
- `transfer`: il destinatario dichiarato deve ricevere; nessuna entrata inattesa;
- `swap`: deve entrare `inMint`, almeno `inAmount` meno 3% di slippage;
- `burn`: nessun destinatario oltre te stesso; solo rent SOL in entrata;
- `other`: nessuna uscita non dichiarata;
- sempre: nessuna delega, cambio autorità, cambio proprietario o durable nonce nascosti.

Esito: `AGENT_INTENT_OK` (info, "Intento agente verificato") oppure `AGENT_INTENT_MISMATCH` (**DANGER, blocca la firma**) con l'elenco delle discrepanze in italiano/inglese. Tutto finisce nel registro come `kind = agent` con attestazione.

## Demo (tester)

Nel tester: **🤖 Agent Gate · agente onesto** (intento coerente → verificato ✓ → firma) e **🤖 Agent Gate · agente bugiardo** (dichiara uno swap, la tx è un invio → bloccato). Entrambi `send=0`: nessuna spesa.

Da shell (esempio con una tx già in base64):
```
adb shell am start -a android.intent.action.VIEW -d 'apex://agent/sign?tx=BASE64&intent=%7B%22action%22%3A%22transfer%22%2C%22outMint%22%3A%22SOL%22%2C%22outAmount%22%3A0.000001%2C%22to%22%3A%22...%22%2C%22agent%22%3A%22cli%22%7D&send=0'
```

## Compatibilità: nessun fornitore in particolare

Il contratto è **un URL e un JSON**. Non c'è niente di specifico per Claude, per X/Grok, per GPT o per nessun altro: qualunque modello, bot, script o cron può comporre la richiesta. Tre modi, dal più semplice:

**Riga di comando**
```
python3 -m apex_agent.cli wallet <pubkey> mainnet
python3 -m apex_agent.cli transfer <destinatario> 0.000001 "perché" --send0
python3 -m apex_agent.cli lie <destinatario> 0.000001     # la scena bloccata
```

**Python, nel tuo agente**
```python
from apex_agent import solana, link
tx = solana.build_transfer_sol(wallet, dest, 1000, solana.latest_blockhash(solana.rpc_url(None)))
url = link.build_url(tx, {"action": "transfer", "outMint": "SOL", "outAmount": 0.000001,
                          "to": dest, "agent": "il mio bot", "reason": "ribilancio serale"})
```

**JavaScript**
```js
const intent = {action:"swap", outMint:"SOL", outAmount:0.1, inMint:"USDC", inAmount:10,
                agent:"il mio bot", reason:"ribilancio"};
const url = `apex://agent/sign?tx=${encodeURIComponent(btoa(String.fromCharCode(...tx)))}`
          + `&intent=${encodeURIComponent(JSON.stringify(intent))}&account=${wallet}`;
```

**Claude via MCP.** `.mcp.json` nel repo registra il server; gli strumenti sono `apex_status`, `apex_set_wallet`, `apex_build_transfer`, `apex_build_swap`, `apex_propose`, `apex_await_outcome`, `apex_send_via_adb`. Il nome dell'agente **non** lo decide il modello: viene dal `clientInfo` della stretta di mano `initialize`, che una chiamata a strumento non può falsificare. Al modello resta la motivazione, che il telefono mostra parola per parola.

## Come arriva la richiesta al telefono

Non c'è nessun server, e non ci sarà. Tre vie:

1. **QR sullo schermo** — l'agente lo disegna, tu lo inquadri con Apex (icona di scansione nella home, o la scheda Agent Gate in Impostazioni). Un trasferimento sta in circa 720 caratteri (105-113 moduli), uno swap Jupiter in circa 1150 (121 moduli): entrambi si leggono da un monitor.
2. **Link da toccare** — mandati l'URL in chat e aprilo sul telefono.
3. **Cavo** — `apex_send_via_adb`, solo per sviluppo.

## Come l'agente scopre com'è andata

Il telefono non può richiamare il PC, quindi il canale di ritorno è la catena. Con `send=1` il telefono trasmette e l'agente riconosce la transazione:

- **trasferimenti**: portano un memo `apex:<id>` che `getSignaturesForAddress` restituisce, quindi basta una chiamata;
- **swap v0**: niente memo, perché una chiave statica in più sposterebbe gli indici delle lookup table; si riconoscono confrontando `recentBlockhash`.

**Mai usare `callback` da un PC**: lo riapre il telefono con `ACTION_VIEW`, quindi un indirizzo http farebbe comparire un browser sul telefono e lascerebbe la transazione firmata nella cronologia e nei log. Con `send=0` la transazione firmata resta sul telefono, mostrata come testo copiabile e come QR.

## Nota sullo schema

`apex://agent/sign` è quello corrente. `omni://agent/sign` continua a funzionare come alias, così le richieste generate prima della rinomina non si rompono.


## Due tasche: il caveau e la busta

L'Agent Gate descritto sopra è la **tasca del caveau**: l'agente propone, il Seed Vault firma con la tua impronta, ogni volta. Serve quando sei presente.

La **busta** è la seconda tasca, ed è quella che rende l'agente utile di notte. È un portafoglio separato, con una chiave software creata sul telefono e cifrata con l'Android Keystore, caricato una sola volta dal tuo conto con un'impronta. **La chiave della busta non lascia mai il telefono.** L'agente non la vede: manda a Apex la transazione più l'intento dichiarato, e Apex decide.

| esito | quando | cosa succede |
|---|---|---|
| **firma silenziosa** | sotto il tetto per operazione, sotto il tetto giornaliero, sotto la soglia "chiedimi", asset e destinatari in lista, programmi noti, ritmo nei limiti | Apex firma con la chiave della busta, invia, registra nel registro e ti notifica. Nessun tocco. |
| **chiedi a me** | sopra la soglia silenziosa, un programma mai visto, un token che non è in lista, un cambio a tasso pessimo, un valore che non so stimare | la ricevuta di sempre, con il motivo, e la conferma con l'impronta del telefono |
| **rifiuto** | destinatario non in lista, asset in uscita non in lista, intento che non torna con la simulazione, ritmo da loop, agente in pausa o busta scaduta | nessuna firma; il motivo torna all'agente in parole, e finisce nel registro |

Le regole (il **collare**) vivono in `:core/AgentPolicy.kt`, puro Kotlin, con 16 test. Sono applicate sul telefono, prima di firmare, e **sulla simulazione**: quello che l'agente dichiara serve solo a essere confrontato con quello che la rete dice che succederà.

### Cosa è garantito, e cosa no

- **Vale anche se il computer dell'agente è compromesso:** l'agente non può muovere più di quello che c'è nella busta, e "Chiudi e recupera" gli toglie il potere all'istante.
- **Le regole proteggono dall'agente** (allucinazioni, prompt injection, loop), non da un telefono compromesso: le applica Apex, non la rete.
- **Niente è imposto on-chain.** Un programma che custodisce i fondi e rifiuta da solo è una feature diversa, e non la promettiamo.

### Il collegamento

Il telefono è sempre il client: interroga il ponte sul tuo computer (`mcp_server.py`, endpoint `/link/next` in long-poll con un token), decide, e riporta il verdetto su `/link/result`. Nessuna porta aperta sul telefono; sul filo passano solo transazioni non firmate, intenti e verdetti. Lo stesso codice parlerebbe a un relay ospitato, senza che nessuna chiave lo attraversi.

### Rispetto a SeekerClaw

SeekerClaw (vincitore MONOLITH, aprile 2026) ha lo stesso modello a due tasche con un burner e due tetti. Apex aggiunge: l'**intento dichiarato verificato contro la simulazione** (una prompt injection che cambia importo o destinatario è bloccata anche sotto tetto), le **liste** di programmi, asset e destinatari oltre ai tetti, il controllo del **tasso di cambio** (uno swap a tasso pessimo è un invio travestito), l'**incasso automatico** dei guadagni verso il Seed Vault, il **registro** con l'esito di ogni decisione, e nessuna IA incorporata: funziona con Claude Code, ChatGPT, Grok, uno script, o con SeekerClaw stesso.
