# Collegare un'intelligenza artificiale ad Apex

Apex fa firmare al tuo Seeker le transazioni che un agente propone. L'agente
**non ha mai una chiave**: prepara la transazione, dichiara cosa fa, e tu approvi
con l'impronta. Se l'agente mente, Apex blocca la firma.

Ci sono due modi di collegarlo, e la differenza conta.

---

## A. Sul tuo computer (consigliato, costo zero)

Funziona con **Claude Code**. Niente server, niente da esporre su internet,
niente da fidarsi: tutto gira in locale.

**01** Installa Apex sul Seeker e apri l'app una volta, collegando il Seed Vault.
Ti serve il tuo indirizzo: lo copi dalla scheda **Ricevi**.

**02** Sul computer, nella cartella del progetto, installa l'unica dipendenza
opzionale, quella che disegna il QR:

```
pip install segno
```

**03** Apri Claude Code in quella cartella. Il file `.mcp.json` è già nel
progetto, quindi Claude Code chiede una volta di approvare il server e sei a
posto. Se preferisci registrarlo a mano:

```
claude mcp add apex -- python3 scripts/apex_agent/mcp_server.py
```

**04** Verifica che tutto risponda, senza toccare il telefono:

```
python3 scripts/apex_agent/cli.py selftest
```

Devono essere tutti verdi.

**05** Prova. Chiedi a Claude:

> Usa il wallet `<il tuo indirizzo>` su mainnet, poi manda 0,000001 SOL a
> `<un indirizzo>` come prova del gate.

Compare un QR nel terminale. Lo inquadri con Apex, dall'icona di scansione in
alto nella schermata Portafoglio. Leggi la ricevuta, tieni premuto, impronta.
Poi chiedi "com'è andata?" e Claude legge la firma dalla catena.

---

## B. Come connettore via URL (Claude, ChatGPT, Grok)

I prodotti web non possono avviare un programma sul tuo computer: accettano solo
un indirizzo. Per questo servizi come PayBox si installano incollando un URL. Lo
stesso server di Apex sa parlare anche così.

**01** Avvia il server in modalità HTTP, con un token che scegli tu:

```
export APEX_MCP_TOKEN=una-stringa-lunga-e-casuale
python3 scripts/apex_agent/mcp_server.py --http 8765
```

**02** Rendilo raggiungibile da internet. Senza pagare nulla, con un tunnel
temporaneo:

```
cloudflared tunnel --url http://localhost:8765
```

Ti restituisce un indirizzo pubblico. L'URL da incollare è quello, più il
percorso e il token:

```
https://<quello-che-ti-danno>/mcp?token=una-stringa-lunga-e-casuale
```

Se vuoi un indirizzo che non cambia, il server va messo su un hosting sempre
acceso. È l'unica parte che costa qualcosa.

**03** Aggiungilo dove ti serve.

- **Claude**: Impostazioni → Customize → Connectors → connettore personalizzato,
  nome `Apex`, incolli l'URL.
- **ChatGPT**: sul web, Impostazioni → Sicurezza e accesso → attiva la modalità
  sviluppatore. Poi Plugin → Sfoglia → `+` → nuovo plugin, nome `Apex`, URL nel
  campo della connessione, tipo "Server URL". Va fatto dal web: sull'app mobile
  la modalità sviluppatore non c'è.
- **Grok**: `grok.com/connectors` → nuovo connettore → Custom, nome `Apex`, URL
  nel campo "Server".

**04** Non c'è nessuna autorizzazione OAuth, e non è una dimenticanza: **non c'è
niente da autorizzare.** Un servizio che esegue operazioni per te deve collegarsi
a un conto, e da quel momento può agire. Apex non ha un conto a cui collegarsi:
l'autorizzazione è il tuo dito sul Seeker, ogni singola volta.

**05** Prova con questo prompt:

> Con Apex, manda 0,000001 SOL a `<un indirizzo>` dal wallet
> `<il tuo indirizzo>`, come prova.

L'assistente risponde con un link `apex://` e un QR. Se stai scrivendo dal
telefono, tocchi il link. Se sei al computer, inquadri il QR con Apex.

---

## Cosa passa su quel canale, e cosa no

| | Passa | Non passa |
|---|---|---|
| Transazione da firmare | sì | |
| Dichiarazione di cosa fa | sì | |
| Il tuo indirizzo pubblico | sì | |
| Chiave privata | | **mai** |
| Seed phrase | | **mai** |
| Potere di firmare | | **mai** |

Il rischio peggiore, se qualcuno scopre il tuo URL, è che ti faccia comparire una
richiesta di firma sul telefono. La leggi e la rifiuti. Per questo il token serve
comunque: senza, chiunque conosca l'indirizzo può disturbarti.

---

## Se qualcosa non va

**Il QR non si inquadra.** Gli swap producono un codice più denso dei
trasferimenti. Allontana un po' il telefono, o alza la luminosità dello schermo.
In alternativa fatti mandare il link e toccalo sul telefono.

**"Blockhash not found" quando firmi.** Una transazione Solana scade in circa 90
secondi. Se hai aspettato troppo, richiedi la proposta e riscansiona.

**"Nessun wallet collegato in Apex".** Apri l'app e collega il Seed Vault almeno
una volta, oppure passa il tuo indirizzo nella richiesta.

**L'agente dichiara una cosa e Apex ne mostra un'altra.** È il sistema che
funziona. Rifiuta e guarda cosa dice la riga rossa: ti dice esattamente quale
discrepanza ha trovato.

---

## Per chi sviluppa un agente

Non serve nessuno di questi prodotti: il contratto è un URL e un JSON, descritto
in `AGENT-GATE.md`. Gli strumenti esposti sono `apex_status`,
`apex_set_wallet`, `apex_build_transfer`, `apex_build_swap`, `apex_propose`,
`apex_await_outcome`, `apex_send_via_adb`.

Un dettaglio pensato per la sicurezza: il nome dell'agente **non lo scrive il
modello**. Lo prende il server dalla stretta di mano del protocollo, quindi
compare "chatgpt 1.0 via apex-mcp" o "claude-code 2.1 via apex-mcp" e un modello
non può spacciarsi per un altro dall'interno di una chiamata. Al modello resta la
motivazione, che il telefono mostra parola per parola.


## Collegare la busta: l'agente che lavora da solo

Tre passi. Il ponte deve restare acceso sul computer, sulla stessa rete Wi-Fi del Seeker (o via cavo con `adb reverse tcp:8765 tcp:8765`).

1. **Avvia il ponte.** Con Claude Code basta il `.mcp.json` del repo: il server apre da solo la porta 8765 per il telefono. Da terminale, o per ChatGPT e Grok:
   ```
   APEX_MCP_TOKEN=una-frase-segreta python3 scripts/apex_agent/mcp_server.py --http 8765
   ```
2. **Crea la busta** in Apex → Agent → *Crea una busta*: scegli quanto metterci, per quanti giorni, il profilo (Prudente o Trader) e l'incasso automatico. Un'impronta, e la busta è carica.
3. **Collega l'agente.** Chiedi a Claude di chiamare `apex_agent_pair` (o `python3 scripts/apex_agent/cli.py pair`), e inquadra il QR da Apex → Agent → *Collega un agente*. La card dice "Collegato a claude-code" e nella barra delle notifiche compare il collegamento con **Pausa** e **Revoca**.

Da quel momento:

- "cambia 0,01 SOL in USDC" → `apex_agent_swap` → sotto le regole **firmato in silenzio**, e ti arriva la notifica;
- "manda 0,001 SOL a Luca" → `apex_agent_send` → Luca deve essere fra i contatti ammessi, altrimenti **rifiutato** con il motivo;
- sopra la soglia silenziosa il telefono **ti chiede**: ricevuta, motivo, impronta.

Gli strumenti che l'assistente vede: `apex_agent_status` (cosa può e non può fare, quanto resta), `apex_agent_pair`, `apex_agent_send`, `apex_agent_swap`. Per il conto principale restano `apex_build_*` e `apex_propose`: lì firma sempre il Seed Vault.

**La chiave della busta non esce mai dal telefono.** Non c'è niente da importare sul computer: se un servizio ti chiede la chiave, non è questo.
