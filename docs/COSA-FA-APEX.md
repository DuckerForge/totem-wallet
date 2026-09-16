# Cosa fa l'app

La mappa di tutto quello che l'app fa oggi, senza gergo. Aggiornata il 16 settembre 2026.
Serve a ritrovarsi: le funzioni sono cresciute in fretta e questo è l'indice.

È un portafoglio Solana per il **Solana Seeker**. L'idea in una frase:

> Prima di firmare qualsiasi cosa vedi uno scontrino di quello che succederà davvero.
> Non quello che l'app dichiara. Quello che la rete dice che succederà.

L'app parla **inglese di base** e passa all'italiano quando il telefono è in italiano.
Il nome è ancora provvisorio. Il package resta `com.clearsign.app` per ragioni storiche.

---

## 1. Il problema che risolve

Quando un'app ti chiede una firma vedi un nome e un pulsante. Non vedi quanto esce, verso chi,
né se stai regalando a qualcuno il permesso di prendere i tuoi token quando vuole. Questo è la
**firma alla cieca**, ed è la prima causa di soldi rubati nelle cripto.

L'app si mette in mezzo. Prende i byte veri della transazione, li fa **simulare dalla rete** e
mostra il risultato: quanto esce, quanto entra, a chi, con quale commissione, con quali rischi.
Poi, e solo poi, firmi con l'impronta.

---

## 2. Lo scontrino, che è il cuore di tutto

Vale per ogni firma: una dApp via Mobile Wallet Adapter, un invio, uno swap, un Blink, un ponte,
un ordine, e ogni mossa dell'agente.

- **Effetti veri, non dichiarazioni.** Simulazione on chain con transazioni v0, tabelle di
  lookup e programmi SPL: lo scontrino mostra le variazioni di saldo vere, anche quando il
  pagamento si divide fra più portafogli.
- **Fiducia nell'indirizzo.** Contatti tuoi, indirizzi già usati, liste nere, e il controllo dei
  **sosia**: un indirizzo che somiglia a uno tuo per i primi e gli ultimi caratteri viene
  marcato, perché è così che funziona l'avvelenamento della rubrica.
- **Rischi.** Deleghe illimitate, indirizzi di bruciatura, firmatari inattesi, programmi
  sconosciuti, transazioni in blocco.
- **Anti scambio all'ultimo istante.** I byte firmati sono gli stessi simulati: se cambiano fra
  la lettura e la firma la cosa si ferma.
- **Tieni premuto, poi l'impronta.** Niente approvazione con un tocco distratto.
- Tre stili di scontrino: scheda, carta, terminale.

---

## 3. La home

- **Saldo grande animato**, in dollari, euro o SOL a scelta, con le cifre che scorrono.
- **Tira giù per aggiornare** tutto.
- **Cerchi delle azioni personalizzabili**: scegli quali e quanti, nell'ordine che vuoi, fra
  Invia, Ricevi, Swap, Inquadra, Tocco, Regalo, Ponte, Agente e altri. Si cambiano da
  Impostazioni, sezione aspetto, con l'anteprima della home dal vivo.
- **Chip del portafoglio** in alto: copia, condividi, Solscan, impostazioni.
- **Mano sopra lo schermo**: il sensore di prossimità copre tutte le cifre, e torni con l'impronta.
- **Tocco lungo sul saldo**: modalità ospite, tutto coperto e i tasti che spendono spenti.
- **Altro**: tocco, salute, contatti, ponte, apri un link, scambio contatti, compagno.

## 4. Quello che tieni

- Token con logo, prezzo, variazione del giorno e controvalore.
- **In DeFi**: stake nativo di SOL con il nome del validatore, **SKR in staking dai Guardiani**
  letto direttamente dal programma, Jupiter Lend, posizioni Jupiter, ognuno con la **resa al giorno**.
- **P&L realizzato** per moneta, calcolato dagli scontrini col metodo del primo entrato primo uscito.
- **Scheda P&L da condividere** come immagine.

## 5. Mercato

- **Preferiti** con logo, prezzo e totale, senza doppioni, con conferma solo quando togli.
- **Campanella** sui movimenti del 5%.
- **Confronto** con una moneta scelta da te: quanto varrebbe questa con la capitalizzazione di
  quella, con la scala per due, per dieci, per cento.
- **BTC ed ETH ufficiali via ponte** su Solana, non i cloni.
- Grafico dentro ogni foglio, prezzo grosso al tocco, ricerca con prezzi.
- L'icona in basso è una freccia verde quando i mercati salgono e rossa quando scendono.

## 6. L'agente, e la paghetta

Non c'è nessuna intelligenza artificiale dentro l'app per operare: c'è un motore di regole.

- **La paghetta** è un portafoglio piccolo, separato dal tuo, creato con una firma tua. La tua
  seed non la vede mai. Decidi quanto, per quanti giorni, quante monete alla volta.
- **Il primo minuto** spiega i tre passi prima che tu faccia la prima paghetta.
- **Una corsia sola**, prudente: liquidità sopra i 50.000 dollari, niente sniping.
- **I cancelli**: autorità di conio e congelamento, numero di detentori, liquidità, vendibilità,
  trappole, più **Rugcheck** e lo scudo di Jupiter. Ciò che non si sa non blocca mai: solo un no
  chiaro ferma una moneta, e la traccia dice quale.
- **Le mosse**: compra una fetta, vende all'obiettivo o allo stop, recupera il rent dopo ogni
  vendita, chiude tutto da sola alla scadenza e ti rimanda i soldi.
- **Il collare**: tetto per mossa, tetto al giorno, programmi ammessi, destinatari ammessi,
  ritmo massimo, interruttore di spegnimento. Sopra i limiti chiede l'impronta. Fuori dalle
  regole rifiuta e dice perché.
- **Copia chi segui**: metti la stella su un portafoglio in Scout e l'agente compra e vende con lui.
- **Se la vendita vale meno del preventivo** non blocca in silenzio: dice il numero vero e
  chiede se vendere lo stesso.
- **Il conto alla chiusura** in chiaro: quanto hai messo, quanto è tornato, quanto hai guadagnato.

### Guardalo lavorare

Una schermata dal vivo: anelli del timer per il prossimo sguardo, grafici delle posizioni aperte
con entrata, obiettivo e stop disegnati, i ragionamenti battuti mentre arrivano, una voce che
dice cosa fa. Gira il telefono e i grafici vanno a due per riga. Sotto i pensieri puoi scrivergli
una domanda. I tasti: vendi, compra ancora, vendi tutto, vendi e cerca altro.

### Le notifiche

Portano un'immagine che dice a colpo d'occhio dove sta ogni moneta fra lo stop e l'obiettivo,
il logo della moneta, i tasti vendi ora e ferma, e un ritmo di vibrazione diverso per sale,
scende e fermati.

## 7. Scout: cosa fanno gli altri Seeker

Un censimento di oltre diecimila portafogli Seeker, tenuto da un piccolo servizio su Cloudflare.
Dice cosa stanno comprando adesso, cosa tengono, e chi ha venduto. Una scoperta di oggi: **metà
dei Seeker censiti tiene SKR in staking dai Guardiani**, in media quasi quarantamila SKR a testa,
soldi che nessun saldo di portafoglio mostra.

## 8. Salute del portafoglio

- Punteggio, deleghe attive, conti congelati, conti vuoti da chiudere con il rent da recuperare.
- **Soldi dimenticati**: le commissioni di liquidità mai riscosse su **Orca**, **Raydium** e
  **Meteora**, lette direttamente dalla catena senza chiavi né servizi. Dice almeno quanto ti
  spetta e ti manda alla pagina della piattaforma per riscuotere.
- **Widget** sulla schermata home e **compagno** flottante sopra le altre app, tutti e due con
  una pagina dedicata dove scegli faccia, righe e grandezza.
- **Watchtower**: un controllo periodico che avvisa se compare una delega illimitata o se un
  indirizzo che hai usato finisce in una lista nera.

## 9. Le cose che solo un Seeker può fare

- **Fatti pagare col tocco**: il telefono si comporta come una carta, l'altro lo avvicina e paga.
- **Adesivo NFC**: scrivi una richiesta di pagamento su un adesivo da pochi centesimi. Chiunque
  lo tocca con qualsiasi portafoglio apre il pagamento. Si può bloccare così nessuno lo riscrive.
- **Scambio contatti col tocco**: due telefoni si toccano e si salvano a vicenda come contatto
  verificato, firmato dalla chiave di attestazione del telefono. Da lì l'avvelenamento della
  rubrica non ha appiglio.
- **Prova di pagamento**: dopo un invio, un QR firmato da questo telefono. Chi lo inquadra vede
  importo, destinatario e firma, senza aspettare l'explorer.
- **Modalità ospite**: cifre coperte e tasti che spendono spenti, si esce con l'impronta.

## 10. Scambi, ponti e link

- **Swap** con **Jupiter Ultra**: rotta scelta, slippage stimato dalla volatilità della moneta,
  commissione di priorità già messa, e le transazioni mandate in privato fuori dalla portata dei
  bot che leggono la coda pubblica. Se Ultra non risponde ripiega sulla vecchia via.
- **Ordini sulla catena** con Jupiter Trigger: obiettivo di vendita che vive anche a telefono spento.
- **Ponte** con RocketX: oltre duecento catene, senza account, con lo scontrino prima della firma
  e una riga onesta su cosa dà e cosa non dà in fatto di riservatezza.
- **Blinks**: un link di azione Solana preso da X, da un QR o incollato diventa una scheda con i
  suoi tasti, e la transazione passa dallo stesso scontrino. Il registro di Dialect viene
  consultato: un host in lista nera è rifiutato.
- **Invio** con memo facoltativo, controllo dei sosia e rubrica.
- **Regalo**: un portafoglino usa e getta dentro un link, per dare cripto a chi non ne ha.

## 11. Agent Gate: firmare per un'intelligenza artificiale

L'agente è software di qualcun altro e **non ha nessuna chiave**. Ti manda la transazione e la
dichiarazione di cosa fa. L'app simula i byte veri e confronta la dichiarazione con l'effetto.
Se l'agente mente sull'importo, sul destinatario, sul token che dovrebbe rientrare, o nasconde
una delega, **la firma si blocca** e spiega cosa non torna. Non giudica se l'operazione sia
intelligente: verifica che l'agente stia dicendo la verità.

La richiesta arriva con un QR o con un link. L'agente scopre com'è andata leggendo la catena.
Chiunque si può integrare: c'è il pacchetto Python, la riga di comando e un server MCP in
`scripts/apex_agent`. I dettagli in `docs/AGENT-GATE.md`.

Il nome che l'agente si dà è solo una dichiarazione, quindi l'app mostra **prima l'origine
verificata** e poi il nome dichiarato, marcato come non verificato.

## 12. Chi ti sta chiedendo la firma

Per ogni dApp: il logo vero preso dal pacchetto installato, se viene dal dApp Store, da Google
Play o da un file installato a mano, voto e publisher dal catalogo Seeker Tracker, versione e
date, e quante volte hai già firmato per lei.

## 13. Registro e prove

Ogni firma finisce in un registro con il controvalore del momento. Esportabile in CSV e JSON per
il commercialista. Ogni riga può portare una **prova firmata dal telefono**, verificabile da
chiunque con la chiave pubblica dentro il pacchetto.

## 14. Aspetto

Sei temi, editor dei colori con anteprima, dimensione del testo, arrotondamento, grana, effetto
televisore vecchio sul tema terminale, tre stili di scontrino. Inglese e italiano.

## 15. Cosa non fa

- Non custodisce chiavi: stanno nel Seed Vault, l'app non le vede mai.
- Non ha un server, non ha account, non traccia. Escono solo le chiamate per leggere la catena,
  i prezzi e il censimento.
- Non dice se un investimento è buono. Dice cosa firma la transazione.
- Non riscuote da sola le commissioni di liquidità: manda alla piattaforma, perché servirebbero
  le istruzioni di altri due programmi e uno scontrino che nessuno potrebbe controllare.
- Lo stop loss sulla catena non c'è: Jupiter lo dà solo passando da un conto custodito da terzi.

## 16. Per chi sviluppa

| Dove | Cosa c'è |
|---|---|
| `:core` | Il motore puro: rischi, scontrini, fiducia negli indirizzi, sosia, regole dell'agente, protocollo del tocco, segnali di Scout. Senza Android, coperto da test. |
| `:app` | L'app: interfaccia, Seed Vault, Mobile Wallet Adapter, simulazione, ciclo dell'agente, Jupiter, RocketX, Rugcheck, NFC, Blinks, prove, compagno, registro, export. |
| `:testdapp` | Una finta dApp con quindici scenari di attacco, più i due dell'Agent Gate. |
| `tools/seeker-worker` | Il servizio Cloudflare dietro Scout, più lo script che conta gli SKR in staking. |
| `scripts/apex_agent` | La metà su PC dell'Agent Gate: pacchetto Python, riga di comando, server MCP. |
| `web/apex` | La pagina pubblica, specchiata su GitHub Pages. |
| `reputation/` | Il programma on chain per la reputazione delle dApp. Compila, non è pubblicato. |

Altri documenti: `PIANO-2026-09-16.md` per cosa resta da fare e le prove una a una,
`AGENT-GATE.md` per il protocollo degli agenti, `HOW-IT-WORKS.md` per il giro tecnico,
`DEMO-SCRIPT.md` per il video.
