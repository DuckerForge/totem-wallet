# Cosa fa Apex

Una mappa di tutto quello che l'app fa oggi, in italiano, senza gergo. Serve a
ritrovarsi: le funzioni sono cresciute in fretta e questo documento è l'indice.

Apex è un portafoglio Solana per il **Solana Seeker**. La sua idea in una frase:

> Prima di firmare qualsiasi cosa, vedi uno scontrino in italiano di quello che
> succederà davvero. Non "l'app dice che". Quello che la rete dice che succederà.

Il nome del package è ancora `com.clearsign.app` per ragioni storiche (il
progetto si chiamava ClearSign, poi Omni, poi Geon). Non cambia nulla per l'utente.

---

## 1. Il problema che risolve

Quando un'app ti chiede una firma, di solito vedi un nome e un pulsante
"Approva". Non vedi quanto esce dal tuo portafoglio, verso chi, né se stai
regalando a qualcuno il permesso di prendere i tuoi token quando vuole. Questo si
chiama **firma alla cieca** ed è la prima causa di soldi rubati nelle cripto.

Apex si mette in mezzo. Prende i byte veri della transazione, li fa **simulare
dalla rete**, e ti mostra il risultato: quanto esce, quanto entra, a chi, con
quale commissione e con quali rischi. Poi, e solo poi, firmi con l'impronta.

---

## 2. Le cose che vedi appena apri l'app

### Portafoglio (prima linguetta)
- **Saldo totale** in euro (o nella valuta che scegli), con la variazione del giorno.
- **Elenco dei token** con logo vero, quantità, valore e variazione a 24 ore. I
  token senza prezzo e gli NFT finiscono in una riga a parte, "altri asset", così
  gli airdrop spazzatura non ti coprono la vista.
- **Tocca una moneta** e si apre la sua scheda: quantità, valore, indirizzo del
  token, quanto rent puoi recuperare, link a Solscan.
- Tre azioni: **Swap**, **Invia**, **Ricevi**.
- **Icona di scansione** in alto a destra: serve per l'Agent Gate (punto 6).
- Card del **Seed Vault** con i tuoi conti e i saldi.

### Scontrini (seconda linguetta)
Il registro di tutto quello che hai firmato. Ogni voce ha data, controparte,
importi, commissione, tipo di operazione e la firma sulla catena. Da qui puoi:
- aprire il dettaglio di uno scontrino e vedere la **prova firmata** (punto 5);
- esportare in **CSV per Koinly e CoinTracker**, in **PDF**, o tutto in un pacchetto;
- vedere il **profitto o la perdita realizzati** per token, calcolati col metodo
  FIFO dagli scontrini registrati.

### Impostazioni (terza linguetta)
Lingua, valuta, temi, e tutte le cose di sicurezza che prima stavano in home:
salute del portafoglio, deleghe e conti, contatti fidati, demo offline.

---

## 3. Cosa succede quando firmi

Questo è il cuore dell'app. Quando una dApp chiede una firma, Apex:

1. **Decodifica** la transazione sul telefono: trasferimenti, token, swap,
   deleghe, cambi di autorità, anche le transazioni moderne con le tabelle di
   lookup.
2. **La simula** sulla rete. La simulazione è ciò che rende affidabile lo
   scontrino: non indovina, chiede alla rete cosa accadrà.
3. **Costruisce lo scontrino**: quanto esce e quanto entra per ogni token, il
   destinatario principale con un'etichetta di fiducia, la commissione, e una
   mappa dei flussi se il pagamento si divide su più indirizzi.
4. **Valuta i rischi** e te li scrive in chiaro.
5. **Ricontrolla un istante prima della firma**. Se il risultato è cambiato
   rispetto a quello che ti ha mostrato, rifiuta. È la difesa contro il trucco di
   cambiare la transazione mentre stai leggendo.
6. Tieni premuto, biometria, e firma il **Seed Vault**: la chiave privata non
   esce mai dal chip sicuro del Seeker.

### I rischi che riconosce
- Delega illimitata sui tuoi token (il classico "approva" che ti svuota dopo).
- Cambio di autorità o di proprietario del tuo conto.
- Destinatario mai visto, o indirizzo che **somiglia** a uno che conosci
  (avvelenamento della rubrica).
- Una transazione che porta via il 90% o più di un tuo saldo.
- Commissione di priorità fuori mercato rispetto a quello che paga la rete adesso.
- Firme richieste ad altre chiavi oltre alla tua.
- Transazioni che non scadono mai (durable nonce), tipiche dei kit per rubare.
- Indirizzi in una lista nera locale, o segnalati dalla community.
- Simulazione fallita: in quel caso non si firma alla cieca, si blocca.

Un rischio grave **blocca** la firma: il pulsante non funziona.

---

## 4. Le operazioni che puoi fare tu

- **Invia** SOL o token: incolli l'indirizzo, lo scansioni o scegli un contatto,
  vedi lo scontrino, firmi.
- **Ricevi**: il tuo QR e l'indirizzo da condividere.
- **Swap** via Jupiter: scegli i due token, vedi il preventivo con rotta,
  commissione e impatto sul prezzo, poi lo scontrino simulato. Sugli swap Apex
  riconosce che il "destinatario" è il vault di un pool e non ti allarma a vuoto.
- **Brucia un token spazzatura e recupera il rent**: tocchi la moneta, "Brucia e
  recupera". Distrugge le unità e chiude il conto del token, restituendoti il suo
  deposito, circa 0,002 SOL per conto. Ti avverte se il token ha ancora valore e
  si blocca se il conto è congelato.
- **Revoca le deleghe** e **chiudi i conti vuoti** dalla scheda Deleghe e conti,
  recuperando il rent.
- **Salute del portafoglio**: un punteggio con l'elenco delle cose da sistemare.

---

## 5. Le prove: perché puoi dimostrare cosa hai visto

Ogni approvazione viene firmata da una **chiave hardware dell'app** (nel
Keystore di Android). La dichiarazione contiene data, controparte, importi,
rischi mostrati, il tuo conto e la firma della transazione. Chiunque abbia la
chiave pubblica può verificarla.

A cosa serve: se una dApp ti dice "ti avevamo avvisato", tu hai la prova
firmata di cosa ti era stato effettivamente mostrato. Ed è anche la base degli
export per le tasse.

---

## 6. Agent Gate: firmare per un'intelligenza artificiale

Questa è la funzione che ci distingue e quella che va spiegata meglio, perché
si fraintende facilmente.

**Apex non contiene nessuna intelligenza artificiale.** L'agente è software di
qualcun altro: un bot che fa trading, un assistente collegato a un portafoglio,
uno script che ribilancia di notte. Oggi, per funzionare, quel software ha
bisogno della tua chiave privata sul computer dove gira. Se quel computer viene
bucato, o se il modello sbaglia, i soldi se ne vanno.

**Con Apex l'agente non ha nessuna chiave.** Ti manda la transazione **e la
dichiarazione di cosa fa**, per esempio "scambio 0,1 SOL per 10 USDC perché sto
ribilanciando". Apex simula i byte veri e confronta la dichiarazione con
l'effetto reale. Se l'agente mente sull'importo, sul destinatario, sul token che
dovrebbe rientrare, o nasconde una delega, **la firma si blocca** e ti spiega
cosa non torna. Se dice il vero, vedi "intento agente verificato" e approvi con
l'impronta.

Apex non giudica se l'operazione sia intelligente. Verifica che l'agente stia
dicendo la verità. È una cosa diversa, e molto più difficile da aggirare.

**Come arriva la richiesta.** L'agente gira su un computer e non può raggiungere
il telefono, e non c'è nessun server in mezzo. Quindi: mostra un **QR** sullo
schermo e tu lo inquadri con l'icona di scansione, oppure ti manda il **link** e
lo tocchi sul telefono. Via cavo funziona anche, ma è solo per sviluppare.

**Come l'agente scopre com'è andata.** Leggendo la catena, non richiamando il
telefono. Nei trasferimenti Apex mette un'etichetta invisibile nella transazione
che l'agente ritrova in una sola richiesta.

**Chi può integrarsi:** chiunque. Non c'è niente di specifico per Claude, Grok o
altri: il contratto è un indirizzo web più un pezzetto di JSON. Nel repository
c'è il pacchetto Python, la riga di comando e un server MCP pronto, così un
assistente può pilotare Apex come strumento. I dettagli sono in
`docs/AGENT-GATE.md`.

**Un dettaglio di sicurezza importante:** il nome che l'agente si dà è solo una
dichiarazione, e un'app malevola potrebbe chiamarsi come vuole. Per questo Apex
mostra sempre **prima l'origine verificata** (quale app del telefono ha aperto la
richiesta, o se è arrivata da un QR o da un link) e **poi il nome dichiarato**,
marcato come non verificato.

---

## 7. Chi ti sta chiedendo la firma

Per ogni dApp che si collega, Apex mostra una scheda con:
- il **logo vero** dell'app, preso dal pacchetto installato sul telefono, non una
  favicon che si può falsificare;
- se viene dal **dApp Store**, da Google Play, o se è stata **installata a mano**
  (sideload), cioè da un file APK senza nessun controllo di uno store;
- voto, numero di recensioni, publisher e se è verificato, dal catalogo pubblico
  Seeker Tracker;
- versione, quando l'hai installata e quando è stata aggiornata;
- quante volte hai già firmato per lei e da quando.

---

## 8. Superfici fuori dall'app

- **Widget Salute wallet** sulla schermata home: anello con il punteggio, saldo e
  controvalore, variazione del giorno, rent da recuperare, l'avviso principale, e
  tre scorciatoie per Invia, Ricevi e Swap. Ha un pulsante per aggiornarsi da
  solo e si ridimensiona da due celle in su.
- **Companion flottante**: una bolla che sta sopra tutte le app con l'anello
  della salute del portafoglio. La trascini dove vuoi, la tocchi e si apre un
  pannellino. Va accesa dalle Impostazioni e richiede il permesso di Android.
- **Watchtower**: un controllo periodico in background che ti avvisa se compare
  una delega illimitata o se un indirizzo con cui hai avuto a che fare finisce
  in una lista nera.

---

## 9. Aspetto

- Sei temi: Halo (gratis), Solana, Seeker, Aurora, Ember, Phosphor.
- **Editor del tema**: scegli i colori di accento, secondario e sfondo con dei
  cursori, la dimensione del testo di tutta l'app, l'arrotondamento degli angoli,
  la grana e lo stile dello scontrino. Anteprima dal vivo.
- **Effetto CRT / vecchia TV** sul tema terminale: scanline, banda che scorre,
  disturbo. Si accende e si spegne.
- Tre stili di scontrino: card, carta, terminale.
- Tutto è tradotto: inglese e italiano, con la lingua dell'app selezionabile.

---

## 10. Cosa Apex non fa

Detto chiaro, per non creare aspettative:

- Non custodisce chiavi: sono nel Seed Vault del Seeker, Apex non le vede mai.
- Non ha un server. Nessun account, nessun tracciamento. Escono solo le chiamate
  necessarie a leggere la catena e i prezzi.
- Non ti dice se un investimento è buono. Ti dice cosa firma la transazione.
- Non ha un'intelligenza artificiale dentro.
- I trasferimenti di token dall'Agent Gate non sono ancora supportati: da lì si
  fanno SOL e swap.

---

## 11. Per chi sviluppa

| Dove | Cosa c'è |
|---|---|
| `:core` | Il motore puro: rischi, scontrini, fiducia negli indirizzi, controllo dell'intento degli agenti. Senza Android, coperto da test. |
| `:app` | L'app: interfaccia, Seed Vault, Mobile Wallet Adapter, chiamate alla rete, registro, export, widget, companion. |
| `:testdapp` | Una finta dApp con quindici scenari di attacco, più i due dell'Agent Gate, per provare che i blocchi funzionano. |
| `scripts/apex_agent` | La metà su PC dell'Agent Gate: pacchetto Python, riga di comando, server MCP. |
| `reputation/` | Il programma on-chain per la reputazione delle dApp. Compila, non è ancora pubblicato. |

Altri documenti: `AGENT-GATE.md` per il protocollo degli agenti,
`HACKATHON-PLAN.md` per il piano di candidatura, `HOW-IT-WORKS.md` per il giro
tecnico completo, `PITCH.md` e `DEMO-SCRIPT.md` per la presentazione.


## La busta agente: due tasche

Il Seed Vault è il caveau: firma solo con la tua impronta, sempre. La **busta** è una seconda tasca, piccola, che l'agente usa da solo. La crei dal tuo conto con un'impronta, decidi quanto metterci e per quanti giorni, e scegli un profilo. Da lì l'agente (Claude, ChatGPT, Grok, uno script) fa swap e pagamenti dentro la busta **senza chiederti niente**, finché resta dentro le regole: tetto per operazione, tetto al giorno, solo asset e destinatari che hai ammesso, un ritmo massimo. Sopra la soglia ti chiede l'impronta. Fuori dalle regole rifiuta e spiega perché.

La chiave della busta non esce mai dal telefono. I guadagni sopra quanto hai caricato tornano da soli nel Seed Vault. "Chiudi e recupera" toglie tutto all'agente in un tocco. Ogni decisione, silenziosa, confermata o rifiutata, finisce nel registro.
