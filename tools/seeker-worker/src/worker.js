/**
 * Cosa comprano i Seeker — uno scanner solo, per tutti.
 *
 * Il trucco che rende la cosa gratis: chiedere *chi si è mosso* costa una chiamata
 * ogni cento portafogli, perché uno swap cambia sempre il saldo, fosse solo per la
 * commissione. Solo chi si è mosso costa una lettura in più. Una chiamata per
 * portafoglio costerebbe cento volte tanto e non starebbe in nessun piano gratuito.
 *
 * Il piano gratuito di Cloudflare dà 50 chiamate in uscita per esecuzione, quindi
 * ogni esecuzione legge una fetta del censimento e le fette ruotano: il giro
 * completo si chiude in un'ora e mezza.
 *
 * ## Perché lo stato è in tre pezzi, e uno è binario
 *
 * Il 15/09/2026 alle 08:52 il worker ha smesso di pubblicare: `exceededCpu` a
 * **10 ms**, il limite del piano gratuito, dopo 40 ms di orologio. Non stava
 * facendo niente: stava *leggendo lo stato*, un oggetto JSON da 612 KB con
 * 10.527 saldi come chiavi, e poi riscrivendolo. Solo parse e stringify di quel
 * blob passavano i dieci millisecondi, e ogni esecuzione moriva prima della
 * prima chiamata RPC.
 *
 * Quindi niente più JSON per i saldi. `bal` è un `Float64Array` grezzo in KV
 * (84 KB, zero parsing: si legge come buffer e si indicizza), dove l'indice è la
 * posizione del portafoglio nel censimento. Il censimento è spezzato in chiavi:
 * `rw` le balene con il loro indice, `rd:<k>` un blocco di cento delfini, e ogni
 * esecuzione legge solo i blocchi che tocca. Lo stato vero e proprio (`state2`)
 * tiene solo gli acquisti, il cursore e un'impronta del pubblicato.
 *
 * Tre scritture per giro al massimo (saldi, stato, e il pubblicato solo se è
 * cambiato), con un giro ogni cinque minuti: sotto le mille scritture al giorno.
 */

const MONEY = new Set([
  "SOL",
  "So11111111111111111111111111111111111111112",
  "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
  "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
  "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn",
  "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So",
  "bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1",
]);
const USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

/** Sotto questo, l'uscita è la commissione più l'affitto del conto: un regalo riscosso, non un acquisto. */
const MIN_SOL = 0.005;
/** Tre portafogli diversi, o non esiste. Sotto tre è una persona. */
const MIN_WALLETS = 3;
/**
 * Tre giorni, non ventiquattro ore.
 *
 * Misurato sui dati veri: in un giorno questa folla fa 115 acquisti buoni su 55
 * monete diverse. Quasi ognuno compra una cosa sua, quindi quasi nessuna moneta
 * arriva a tre compratori distinti e la classifica ne mostrava due. Non era la
 * regola dei tre a essere troppo dura, era la finestra a essere troppo corta:
 * a 48 ore le monete buone diventano otto, a 72 undici.
 *
 * I dati li tenevamo gia' tre giorni (KEEP), quindi non costa una chiamata in
 * piu'. La regola dei tre portafogli diversi resta intatta: si guarda piu'
 * indietro, non si abbassa l'asticella.
 */
const WINDOW = 72 * 3600_000;
const KEEP = 3 * 24 * 3600_000;

/**
 * Il vero limite del piano gratuito: 50 chiamate in uscita per esecuzione, e la
 * cinquantunesima non rallenta, *lancia*. Quindi si conta. Quarantacinque, con
 * cinque di margine per i tentativi.
 */
const BUDGET = 45;
/** Le balene si leggono tutte a ogni giro (tredici blocchi); i delfini a turno, cinque blocchi. */
// Dodici blocchi da cento, non cinque. Con 93 blocchi un giro completo dei
// delfini scende da tre ore e dieci a un'ora e venti, e le volte al giorno
// salgono da sette a diciotto. Più su non si va: il piano gratuito dà cinquanta
// sotto-richieste per esecuzione, BUDGET ne prende 45, le balene ne occupano già
// tredici, e quello che resta serve a leggere le firme di chi si è mosso.
const DOLPHIN_CHUNKS_PER_RUN = 12;

async function rpc(env, method, params) {
  const r = await fetch(env.HELIUS_URL, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
  });
  if (!r.ok) return null;
  const j = await r.json().catch(() => null);
  return j && !j.error ? j.result : null;
}

/**
 * Un acquisto, o niente.
 *
 * "Comprato" vuol dire che sono usciti soldi ed è arrivata una moneta nella stessa
 * transazione. Servono tutte e due: una moneta che arriva da sola è un regalo, e su
 * questa folla i regali sono la maggioranza.
 */
function buyOf(tx, addr, whale, at) {
  const meta = tx && tx.meta;
  if (!meta || meta.err) return null;
  const bal = (key) => {
    const m = new Map();
    for (const o of meta[key] || []) {
      if (o.owner !== addr) continue;
      m.set(o.mint, Number((o.uiTokenAmount || {}).uiAmountString || 0));
    }
    return m;
  };
  const pre = bal("preTokenBalances");
  const post = bal("postTokenBalances");

  let sol = 0;
  const keys = ((tx.transaction || {}).message || {}).accountKeys || [];
  for (let i = 0; i < keys.length; i++) {
    const pk = typeof keys[i] === "string" ? keys[i] : keys[i].pubkey;
    if (pk !== addr) continue;
    sol = ((meta.preBalances || [])[i] - (meta.postBalances || [])[i]) / 1e9;
    break;
  }
  const usdcOut = (pre.get(USDC) || 0) - (post.get(USDC) || 0);
  const spent = sol > 0.001 ? sol : usdcOut > 1 ? usdcOut / 200 : 0;
  if (spent < MIN_SOL) return null;

  let best = null;
  for (const [mint, v] of post) {
    if (MONEY.has(mint)) continue;
    const d = v - (pre.get(mint) || 0);
    if (d > 0 && (!best || d > best.d)) best = { mint, d };
  }
  if (!best) return null;
  return { w: addr, b: whale, m: best.mint, at, sol: spent };
}

/**
 * Una vendita, o niente: una moneta che cala e soldi che arrivano nella stessa
 * transazione. Serve a chi copia un portafoglio: uscire quando esce lui.
 */
function sellOf(tx, addr, whale, at) {
  const meta = tx && tx.meta;
  if (!meta || meta.err) return null;
  const bal = (key) => {
    const m = new Map();
    for (const o of meta[key] || []) {
      if (o.owner !== addr) continue;
      m.set(o.mint, Number((o.uiTokenAmount || {}).uiAmountString || 0));
    }
    return m;
  };
  const pre = bal("preTokenBalances");
  const post = bal("postTokenBalances");
  let sol = 0;
  const keys = ((tx.transaction || {}).message || {}).accountKeys || [];
  for (let i = 0; i < keys.length; i++) {
    const pk = typeof keys[i] === "string" ? keys[i] : keys[i].pubkey;
    if (pk !== addr) continue;
    sol = ((meta.postBalances || [])[i] - (meta.preBalances || [])[i]) / 1e9;
    break;
  }
  const usdcIn = (post.get(USDC) || 0) - (pre.get(USDC) || 0);
  const got = sol > 0.001 ? sol : usdcIn > 1 ? usdcIn / 200 : 0;
  if (got < MIN_SOL) return null;
  let best = null;
  for (const [mint, v] of pre) {
    if (MONEY.has(mint)) continue;
    const d = v - (post.get(mint) || 0);
    if (d > 0 && (!best || d > best.d)) best = { mint, d };
  }
  if (!best) return null;
  return { w: addr, b: whale, m: best.mint, at, sol: got, s: 1 };
}

/** La classifica pubblicata. Pesa chi ha comprato, non quante volte. */
function rank(buys, now, followed) {
  const cut = now - WINDOW;
  const by = new Map();
  for (const b of buys) {
    if (b.s || b.at < cut || MONEY.has(b.m) || b.sol < MIN_SOL) continue;
    if (!by.has(b.m)) by.set(b.m, { wallets: new Map(), buys: 0, sol: 0, last: 0 });
    const g = by.get(b.m);
    g.wallets.set(b.w, b.b);
    g.buys++;
    g.sol += b.sol;
    g.last = Math.max(g.last, b.at);
  }
  const rows = [];
  for (const [mint, g] of by) {
    if (g.wallets.size < MIN_WALLETS) continue;
    let whales = 0;
    let crowd = 0;
    for (const isWhale of g.wallets.values()) {
      if (isWhale) whales++;
      crowd += isWhale ? 3 : 1;
    }
    const age = Math.min(1, Math.max(0, (now - g.last) / WINDOW));
    rows.push({
      mint, sym: "", wallets: g.wallets.size, whales, buys: g.buys,
      sol: Math.round(g.sol * 1000) / 1000, last: g.last,
      score: crowd * (1 - 0.6 * age),
    });
  }
  rows.sort((a, b) => b.score - a.score || b.wallets - a.wallets);
  // La classifica dice cosa sta succedendo; gli eventi dicono chi lo sta facendo,
  // ed è quello che si legge come una chat. Senza i secondi la scheda tace finché
  // tre persone diverse non si incontrano sulla stessa moneta, il che è raro.
  const events = buys
    .filter((b) => !MONEY.has(b.m) && b.sol >= MIN_SOL)
    .sort((a, b) => b.at - a.at)
    .slice(0, 40)
    .map((b) => ({ w: b.w, t: b.b ? "b" : "d", m: b.m, at: b.at, sol: Math.round(b.sol * 1000) / 1000, s: b.s ? 1 : 0 }));
  return { at: now, followed, window: WINDOW, rows: rows.slice(0, 20), events };
}

/** Un'impronta corta di una stringa, per sapere se il pubblicato è cambiato senza tenerlo in memoria. */
function fingerprint(s) {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h * 33) ^ s.charCodeAt(i)) >>> 0;
  return h.toString(16);
}

async function sweep(env) {
  // Il censimento, a pezzi: le balene con il loro indice, e i cinque blocchi di
  // delfini che toccano a questo giro. Il resto non si legge nemmeno.
  const rwRaw = await env.SEEKER.get("rw");
  if (!rwRaw) return;
  const rw = JSON.parse(rwRaw);            // { n, nd, w: [[addr, idx], ...] }
  const state = JSON.parse((await env.SEEKER.get("state2")) || "{}");
  let buys = state.buys || [];
  let cursor = state.cursor || 0;
  // Da quando guardare le firme, per ogni portafoglio, e non una soglia sola.
  //
  // Era una sola: mezz'ora prima del giro, riscritta a ogni giro. Per le balene
  // andava bene, perché si rileggono ogni dieci minuti. Per i delfini era una
  // trappola: il loro blocco torna sotto gli occhi ogni tre ore, quindi quando
  // uno risultava "mosso" le sue firme erano quasi sempre più vecchie di mezz'ora
  // e venivano buttate tutte, mentre il suo saldo era già stato sovrascritto e
  // quel movimento non si poteva più ritrovare. Sparivano così quasi tutti gli
  // acquisti dei nove Seeker su dieci che non sono balene, ed è il motivo per cui
  // la classifica diceva "tredici su quattordici sono balene": non comprano di
  // più, li guardavamo soltanto noi.
  //
  // Adesso ogni blocco ricorda quando è stato letto l'ultima volta, e la soglia
  // di un portafoglio è quella del suo blocco. Le balene tengono la loro.
  const seen = state.seen || {};
  const nowStart = Date.now();
  const FALLBACK = nowStart - 2 * 3600_000;
  const whaleSince = state.since || FALLBACK;

  const chunks = [];
  const chunkIds = [];
  for (let k = 0; k < DOLPHIN_CHUNKS_PER_RUN && rw.nd > 0; k++) {
    const id = (cursor + k) % rw.nd;
    const raw = await env.SEEKER.get("rd:" + id);
    if (raw) { chunks.push(JSON.parse(raw)); chunkIds.push(id); }
  }
  cursor = (cursor + DOLPHIN_CHUNKS_PER_RUN) % Math.max(rw.nd, 1);
  // Quanto tempo fa questo blocco è stato guardato l'ultima volta. Alla prima
  // passata non lo sappiamo, e due ore è un compromesso onesto: abbastanza
  // indietro da prendere qualcosa, non tanto da rileggere mezza giornata.
  const chunkSince = new Map();
  chunkIds.forEach((id) => chunkSince.set(id, seen[id] || FALLBACK));

  // I saldi: un array di numeri, dentro lo stato come base64. Prima stavano in
  // una chiave a parte, letta e scritta a ogni giro: due scritture per giro,
  // 288 giri al giorno, e il piano gratuito (mille scritture) era a metà alle
  // dieci di sera. Una chiave sola, una scrittura sola.
  let buf = state.bal ? b64ToBuf(state.bal) : await env.SEEKER.get("bal", "arrayBuffer");
  const bal = buf && buf.byteLength === rw.n * 8 ? new Float64Array(buf) : new Float64Array(rw.n);

  // Una sola riserva, spesa da tutti. Quando finisce si smette e si salva quello
  // che si è fatto: un giro parziale salvato vale infinitamente più di un giro
  // completo che muore.
  let left = BUDGET;
  const call = async (method, params) => {
    if (left <= 0) return null;
    left--;
    return rpc(env, method, params);
  };

  const movers = [];
  const read = async (list, whale, since) => {
    // list: [[addr, idx], ...] di al più cento
    const res = await call("getMultipleAccounts", [
      list.map((x) => x[0]),
      { encoding: "base64", dataSlice: { offset: 0, length: 0 } },
    ]);
    if (!res || !res.value) return;
    list.forEach((x, i) => {
      const lam = res.value[i] ? res.value[i].lamports : 0;
      const was = bal[x[1]];
      // Alla primissima lettura non c'è un prima: si registra e basta, perché
      // inventare un movimento da un confronto che non esiste segnerebbe tutti.
      // La soglia viaggia con il portafoglio: quella del suo blocco per un
      // delfino, quella del giro per una balena.
      if (was !== 0 && was !== lam) movers.push({ a: x[0], w: whale, since });
      bal[x[1]] = lam;
    });
  };

  for (let i = 0; i < rw.w.length; i += 100) await read(rw.w.slice(i, i + 100), true, whaleSince);
  for (let k = 0; k < chunks.length; k++) await read(chunks[k], false, chunkSince.get(chunkIds[k]) || FALLBACK);

  // Balene per prime: se la riserva finisce, deve finire sui più piccoli.
  movers.sort((a, b) => (b.w ? 1 : 0) - (a.w ? 1 : 0));
  for (const m of movers) {
    // Una firma più qualche transazione: senza questo margine l'ultimo
    // portafoglio della lista farebbe saltare l'intera esecuzione.
    if (left < 5) break;
    // Dieci firme e non cinque: un portafoglio che si è mosso dopo tre ore ne ha
    // spesso più di cinque, e tagliare a cinque buttava via proprio le più vecchie,
    // cioè quelle che spiegano il movimento che ci ha fatto guardare.
    const sigs = await call("getSignaturesForAddress", [m.a, { limit: 10 }]);
    for (const s of sigs || []) {
      if (left <= 1) break;
      if (s.err || !s.blockTime) continue;
      const at = s.blockTime * 1000;
      if (at <= m.since) continue;
      const tx = await call("getTransaction", [
        s.signature,
        { encoding: "jsonParsed", maxSupportedTransactionVersion: 0 },
      ]);
      const b = buyOf(tx, m.a, m.w, at) || sellOf(tx, m.a, m.w, at);
      if (b) buys.push(b);
    }
  }

  const now = Date.now();
  buys = buys.filter((b) => b.at > now - KEEP);
  const out = JSON.stringify(rank(buys, now, rw.n));
  const fp = fingerprint(out);

  // Lo stato (con i saldi dentro) sempre, il pubblicato solo se è cambiato: le
  // scritture del piano gratuito sono mille al giorno e questo le tiene sotto.
  // In KV per chi passa dal worker, e in archivio perche' i telefoni lo leggano
  // senza svegliare il worker. Mille telefoni che chiedono la classifica ogni
  // sei minuti sono 240.000 invocazioni al giorno e il piano gratuito ne da'
  // centomila: la stessa lettura sull'archivio non costa nessuna invocazione e
  // nessuna scrittura, perche' Firebase conta lo spazio e il traffico.
  if (fp !== state.crowd) {
    await env.SEEKER.put("crowd", out);
    if (fbOn(env)) await fbPut(env, "crowd", out).catch(() => {});
  }
  await env.SEEKER.put(
    "state2",
    JSON.stringify({
      buys, cursor, since: now - 30 * 60_000, spent: BUDGET - left, movers: movers.length, crowd: fp, at: now,
      // Quando abbiamo guardato ciascun blocco, così il prossimo giro che lo
      // riprende sa da dove leggere le firme invece di buttarle.
      seen: Object.assign({}, seen, Object.fromEntries(chunkIds.map((id) => [id, now]))),
      bal: bufToB64(bal.buffer),
    }),
  );
}

function bufToB64(buf) {
  const bytes = new Uint8Array(buf);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 8192) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
  return btoa(bin);
}

function b64ToBuf(b64) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

const TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
const TOKEN22 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";

/**
 * Dove finiscono i portafogli letti, e perché non nel KV.
 *
 * Stavano in KV con mezz'ora di scadenza, e c'era un difetto di forma: **ogni
 * portafoglio aperto a freddo costava una scrittura**, e il piano gratuito ne dà
 * mille al giorno in tutto. Il cron se ne prende già circa 430. Restavano meno di
 * 600 aperture al giorno, dopo le quali il worker non poteva più scrivere
 * *niente*, nemmeno la classifica: le letture degli utenti avrebbero spento la
 * scansione.
 *
 * E la mezz'ora funzionava solo se la gente arrivava tutta insieme. Metà utenti
 * la mattina e metà la sera vuol dire che nessuno trova mai niente di caldo, e
 * ognuno paga per intero.
 *
 * Quindi non è più una cache, è un archivio. Sta su Firebase, dove il piano
 * gratuito conta lo spazio e il traffico e **non le scritture**: diecimila
 * portafogli con le loro monete sono sette megabyte su un giga. Uno legge una
 * volta, e tutti gli altri lo trovano già lì per mezza giornata, a qualsiasi ora.
 *
 * Tutto sotto `/clearsign`. Gli altri progetti che vivono in quel database non
 * si toccano, né in lettura né in scrittura.
 *
 * Senza `FB_URL` e `FB_SECRET` il worker si comporta esattamente come prima, con
 * il KV e la mezz'ora: così si può mettere in produzione prima del segreto.
 */
const FRESH = 30 * 60_000;
const STALE = 12 * 3600_000;
/** Quanto vale una lettura in KV, quando Firebase non c'è. */
const HOLD_TTL = 30 * 60;

const fbOn = (env) => !!(env.FB_URL && env.FB_SECRET);
const fbUrl = (env, path) =>
  env.FB_URL.replace(/\/$/, "") + "/clearsign/" + path + ".json?auth=" + env.FB_SECRET;

async function fbGet(env, path) {
  const r = await fetch(fbUrl(env, path));
  if (!r.ok) return null;
  const t = await r.text();
  return t && t !== "null" ? t : null;
}

async function fbPut(env, path, body) {
  const r = await fetch(fbUrl(env, path), { method: "PUT", body });
  return r.ok;
}

/**
 * Il verdetto sul web di una moneta, scritto dai telefoni e letto da tutti.
 *
 * Il telefono paga la ricerca una volta e la lascia qui. Il prossimo la trova.
 * Mille utenti pagano due ricerche per moneta invece di mille: la regola che
 * rende possibile condividerle sta sul telefono, in `CoinCheck.Shared`, ed è
 * che uno STOP vale da chiunque mentre un «pulita» vale solo da due
 * installazioni diverse.
 *
 * Qui dentro c'è solo il buttafuori, e fa tre cose:
 *
 *  * una riga per installazione, così chi scrive mille volte resta uno;
 *  * al massimo [CC_MAX] righe per moneta, le più recenti, così una moneta non
 *    diventa un archivio;
 *  * niente testo lungo e niente campi inventati.
 *
 * Non tocca il KV: le scritture KV del piano gratuito sono mille al giorno e se
 * le mangia già la scansione. Firebase conta lo spazio, non le scritture.
 */
const CC_MAX = 6;
const CC_KEEP_MS = 7 * 24 * 3600_000;

async function ccPut(env, mint, body) {
  const id = String(body.i || "").replace(/[^A-Za-z0-9]/g, "").slice(0, 32);
  if (!id) return false;
  const stop = body.s === 1 || body.s === "1";
  const why = String(body.w || "").slice(0, 120);
  const now = Date.now();

  const row = JSON.parse((await fbGet(env, "coin/" + mint)) || "{}") || {};
  const v = row.v && typeof row.v === "object" ? row.v : {};
  v[id] = { s: stop ? 1 : 0, w: stop ? why : "", at: now };

  // Le più recenti, e via quelle scadute: la riga resta piccola da sola.
  const live = Object.entries(v)
    .filter(([, e]) => e && now - (e.at || 0) < CC_KEEP_MS)
    .sort((a, b) => (b[1].at || 0) - (a[1].at || 0))
    .slice(0, CC_MAX);

  return fbPut(env, "coin/" + mint, JSON.stringify({ at: now, v: Object.fromEntries(live) }));
}

/** L'ora scritta dentro il corpo, o zero se il corpo non si legge. */
function ageOf(body) {
  if (!body) return Infinity;
  try { return Date.now() - (JSON.parse(body).at || 0); } catch { return Infinity; }
}

/**
 * Cosa tiene un portafoglio, letto dalla catena.
 *
 * Due chiamate per i conti token, una ogni cinquanta monete per i prezzi. È il
 * costo pieno, quello che l'archivio serve a non pagare due volte.
 */
async function readHoldings(env, addr) {
  const amounts = new Map();
  for (const program of [TOKEN, TOKEN22]) {
    const res = await rpc(env, "getTokenAccountsByOwner", [
      addr, { programId: program }, { encoding: "jsonParsed" },
    ]);
    for (const a of (res && res.value) || []) {
      const d = a.account && a.account.data;
      const info = d && d.parsed && d.parsed.info;
      if (!info) continue;
      const ui = info.tokenAmount && info.tokenAmount.uiAmount;
      if (!ui) continue;
      amounts.set(info.mint, (amounts.get(info.mint) || 0) + ui);
    }
  }
  const mints = [...amounts.keys()];
  const px = {};
  // I prezzi vengono da Jupiter, che non chiede chiavi, cinquanta alla volta.
  for (let i = 0; i < mints.length && i < 150; i += 50) {
    const r = await fetch("https://lite-api.jup.ag/price/v3?ids=" + mints.slice(i, i + 50).join(","));
    const j = r.ok ? await r.json().catch(() => null) : null;
    for (const m in j || {}) if (j[m] && j[m].usdPrice) px[m] = j[m].usdPrice;
  }
  const priced = [];
  let unpriced = 0;
  for (const [mint, amount] of amounts) {
    const usd = (px[mint] || 0) * amount;
    if (usd >= 1) priced.push({ m: mint, q: amount, u: Math.round(usd * 100) / 100 });
    else unpriced++;
  }
  priced.sort((a, b) => b.u - a.u);
  return JSON.stringify({ at: Date.now(), top: priced.slice(0, 12), unpriced });
}

/** Rilegge dalla catena e mette da parte. */
async function refresh(env, addr) {
  const body = await readHoldings(env, addr).catch(() => null);
  if (body) await fbPut(env, "holdings/" + addr, body).catch(() => {});
  return body;
}

/**
 * Cosa tiene un portafoglio, condiviso con tutti.
 *
 * Se ce l'abbiamo e ha meno di mezza giornata si risponde subito con quello. Se
 * ha passato la mezz'ora si rilegge dopo aver risposto, così chi ha chiesto non
 * aspetta e il prossimo trova roba fresca. Si va sulla catena solo quando non
 * c'è niente, o quando quel niente ha mezza giornata.
 */
async function holdingsOf(env, addr, ctx) {
  if (!fbOn(env)) {
    const key = "h:" + addr;
    const cached = await env.SEEKER.get(key);
    if (cached) return cached;
    const body = await readHoldings(env, addr);
    await env.SEEKER.put(key, body, { expirationTtl: HOLD_TTL });
    return body;
  }
  const stored = await fbGet(env, "holdings/" + addr).catch(() => null);
  const age = ageOf(stored);
  if (stored && age < STALE) {
    if (age > FRESH && ctx) ctx.waitUntil(refresh(env, addr).catch(() => {}));
    return stored;
  }
  return (await refresh(env, addr)) || stored;
}

/**
 * I portafogli che stanno per essere aperti, letti prima che qualcuno chieda.
 *
 * La gente non apre indirizzi a caso: apre le facce che vede, e le facce sono
 * quelle della diretta, uguali per tutti. Sono una trentina, non diecimila.
 * Leggerle qui vuol dire che la prima persona della giornata non paga niente,
 * e che l'ora in cui arriva smette di contare.
 *
 * Gira in un'esecuzione sua, sfasata di cinque minuti dalla scansione, perché
 * il piano gratuito dà cinquanta chiamate in uscita per esecuzione e queste non
 * devono togliere niente al censimento.
 */
/**
 * Le costanti della catena, una volta per tutti.
 *
 * Epoca, slot, inflazione e la commissione di priorita' mediana: numeri uguali
 * per chiunque, che ogni telefono chiedeva al suo nodo a ogni portafoglio
 * aperto e a ogni scontrino. Tre chiamate ogni dieci minuti qui, zero sui
 * telefoni che leggono `chain` dall'archivio finche' e' fresco. Se questa
 * scrittura manca, il telefono torna a chiederle alla catena da solo.
 */
async function publishChain(env) {
  const [epoch, infl, fees] = await Promise.all([
    rpc(env, "getEpochInfo", []),
    rpc(env, "getInflationRate", []),
    rpc(env, "getRecentPrioritizationFees", []),
  ]);
  if (!epoch || !epoch.epoch) return;
  const sorted = (Array.isArray(fees) ? fees : [])
    .map((f) => Number(f.prioritizationFee))
    .filter((n) => Number.isFinite(n) && n >= 0)
    .sort((a, b) => a - b);
  const fee = sorted.length ? sorted[sorted.length >> 1] : null;
  const out = {
    at: Date.now(),
    epoch: epoch.epoch,
    slot: epoch.absoluteSlot || null,
    inflation: infl && Number.isFinite(infl.validator) ? infl.validator : null,
    fee,
  };
  await fbPut(env, "chain", JSON.stringify(out));
}

async function warm(env) {
  if (!fbOn(env)) return;
  await publishChain(env).catch(() => {});
  const raw = await env.SEEKER.get("crowd");
  if (!raw) return;
  const crowd = JSON.parse(raw);
  const seen = new Set();
  let looked = 0;
  let done = 0;
  for (const e of crowd.events || []) {
    if (looked >= WARM_LOOK || done >= WARM_MAX) break;
    if (seen.has(e.w)) continue;
    seen.add(e.w);
    looked++;
    const stored = await fbGet(env, "holdings/" + e.w).catch(() => null);
    if (ageOf(stored) < FRESH) continue;
    done++;
    await refresh(env, e.w).catch(() => {});
  }
}

/** Quante facce guardare e quante rileggerne, per stare sotto le cinquanta chiamate. */
const WARM_LOOK = 12;
const WARM_MAX = 5;
/** La sveglia che scalda invece di scansionare, sfasata di cinque minuti. */
const WARM_CRON = "5-59/10 * * * *";

/**
 * Nome, simbolo, decimali e icona di una moneta: uno chiede, tutti sanno.
 *
 * Sono dati che non cambiano mai, e finora ogni telefono li chiedeva a Jupiter
 * per conto suo. Nessuno paga in denaro, si paga in limiti di frequenza, ed e'
 * la stessa quota che serve a mostrare un grafico o un prezzo mentre qualcuno
 * sta guardando.
 *
 * Una lettura per moneta dall'archivio, una chiamata sola a Jupiter per tutte
 * quelle che mancano, e una scrittura sola per rimetterle a posto. La prima
 * persona che apre una moneta la paga; tutte le altre no, per sempre.
 */
const TOK_MAX = 20;

async function tokensOf(env, mints) {
  const want = mints.slice(0, TOK_MAX);
  const out = {};
  const missing = [];
  for (const m of want) {
    const got = fbOn(env) ? await fbGet(env, "tokens/" + m).catch(() => null) : null;
    if (got) { try { out[m] = JSON.parse(got); } catch (_) { missing.push(m); } }
    else missing.push(m);
  }
  if (missing.length) {
    const r = await fetch("https://lite-api.jup.ag/tokens/v2/search?query=" + missing.join(","));
    const list = r.ok ? await r.json().catch(() => null) : null;
    const fresh = {};
    for (const t of list || []) {
      if (!t || !t.id) continue;
      // Solo quello che non cambia. I prezzi non stanno qui: un prezzo di
      // mezz'ora fa e' peggio di nessun prezzo.
      fresh[t.id] = { s: t.symbol || "", n: t.name || "", d: t.decimals ?? 0, i: t.icon || null };
      out[t.id] = fresh[t.id];
    }
    if (fbOn(env) && Object.keys(fresh).length) {
      await fetch(fbUrl(env, "tokens"), { method: "PATCH", body: JSON.stringify(fresh) }).catch(() => {});
    }
  }
  return out;
}

/**
 * Il ponte, con la chiave che resta qui.
 *
 * La chiave RocketX viaggiava dentro l'APK: chiunque lo apre la tira fuori e
 * spende la quota di qualcun altro, o se la fa revocare. Tenendola qui il
 * telefono non ne ha bisogno.
 *
 * **Lo scambio, detto**: questo servizio finisce in mezzo alla risposta che
 * contiene l'indirizzo di deposito, cioe' entra nella lista di chi potrebbe
 * sostituirlo. La lista prima conteneva solo RocketX. E' infrastruttura nostra,
 * ma e' una superficie sui soldi al posto di una sulla quota, ed e' una scelta
 * di chi mette i soldi: il telefono passa di qui solo se non ha una chiave sua.
 *
 * Le strade ammesse sono cinque e basta: questo non e' un proxy aperto.
 */
const RX_HOST = "https://api.rocketx.exchange/v1";
const RX_PATHS = ["/configs", "/tokens", "/quotation", "/swap", "/status"];

async function rocketx(env, path, method, body) {
  if (!env.RX_KEY) return null;
  const clean = "/" + path.replace(/^\/+/, "");
  if (!RX_PATHS.some((p) => clean === p || clean.startsWith(p + "?") || clean.startsWith(p + "/"))) return null;
  const r = await fetch(RX_HOST + clean, {
    method,
    // RocketX sta dietro al filtro anti-bot di Cloudflare, che risponde 403
    // "error 1010" a chi non si presenta con un User-Agent. Un worker di suo
    // non ne manda nessuno, e sarebbe bloccato prima ancora di mostrare la
    // chiave.
    headers: {
      "x-api-key": env.RX_KEY,
      "Accept": "application/json",
      "User-Agent": "Mozilla/5.0 (Linux; Android 14) ClearSign/1.0",
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body || undefined,
  });
  return { status: r.status, text: await r.text() };
}

export default {
  // Due sveglie: la scansione ai dieci, e cinque minuti dopo le facce da
  // scaldare. Separate perché le cinquanta chiamate in uscita del piano
  // gratuito si contano per esecuzione, e la scansione le vuole tutte.
  async scheduled(event, env, ctx) {
    ctx.waitUntil(event.cron === WARM_CRON ? warm(env) : sweep(env));
  },
  async fetch(request, env, ctx) {
    // Un portafoglio solo, per chi ha aperto la pagina di una persona.
    const url = new URL(request.url);
    const who = url.searchParams.get("w");
    if (who && /^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(who)) {
      const body = await holdingsOf(env, who, ctx).catch(() => null);
      return new Response(body || '{"at":0,"top":[],"unpriced":0}', {
        headers: {
          "content-type": "application/json; charset=utf-8",
          "cache-control": "public, max-age=900",
          "access-control-allow-origin": "*",
        },
      });
    }
    // Il ponte, quando il telefono non ha una chiave sua.
    const rx = url.searchParams.get("rx");
    if (rx) {
      const out = await rocketx(env, rx, request.method, request.method === "POST" ? await request.text() : null).catch(() => null);
      if (!out) return new Response('{"error":"no"}', { status: 502, headers: { "content-type": "application/json" } });
      return new Response(out.text, {
        status: out.status,
        headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" },
      });
    }

    // Il verdetto sul web di una moneta. Solo scrittura: il telefono legge
    // l'archivio da sé, senza chiave e senza svegliare questo worker.
    const cc = url.searchParams.get("cc");
    if (cc && /^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(cc)) {
      if (request.method !== "POST") return new Response('{"error":"post"}', { status: 405, headers: { "content-type": "application/json" } });
      if (!fbOn(env)) return new Response('{"ok":false}', { headers: { "content-type": "application/json" } });
      const body = await request.json().catch(() => null);
      const ok = body ? await ccPut(env, cc, body).catch(() => false) : false;
      return new Response(JSON.stringify({ ok }), {
        headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" },
      });
    }

    // Le monete: nome, simbolo, decimali, icona. Niente prezzi.
    const toks = url.searchParams.get("t");
    if (toks) {
      const mints = toks.split(",").filter((m) => /^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(m));
      const got = mints.length ? await tokensOf(env, mints).catch(() => ({})) : {};
      return new Response(JSON.stringify(got), {
        headers: {
          "content-type": "application/json; charset=utf-8",
          "cache-control": "public, max-age=86400",
          "access-control-allow-origin": "*",
        },
      });
    }
    const body = (await env.SEEKER.get("crowd")) || '{"at":0,"followed":0,"rows":[]}';
    return new Response(body, {
      headers: {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "public, max-age=300",
        "access-control-allow-origin": "*",
      },
    });
  },
};
