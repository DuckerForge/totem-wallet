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
const WINDOW = 24 * 3600_000;
const KEEP = 3 * 24 * 3600_000;

/**
 * Il vero limite del piano gratuito: 50 chiamate in uscita per esecuzione, e la
 * cinquantunesima non rallenta, *lancia*. Quindi si conta. Quarantacinque, con
 * cinque di margine per i tentativi.
 */
const BUDGET = 45;
/** Le balene si leggono tutte a ogni giro (tredici blocchi); i delfini a turno, cinque blocchi. */
const DOLPHIN_CHUNKS_PER_RUN = 5;

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

/** La classifica pubblicata. Pesa chi ha comprato, non quante volte. */
function rank(buys, now, followed) {
  const cut = now - WINDOW;
  const by = new Map();
  for (const b of buys) {
    if (b.at < cut || MONEY.has(b.m) || b.sol < MIN_SOL) continue;
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
    .map((b) => ({ w: b.w, t: b.b ? "b" : "d", m: b.m, at: b.at, sol: Math.round(b.sol * 1000) / 1000 }));
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
  // Mezz'ora di sovrapposizione: abbastanza da non perdere niente fra un giro e
  // l'altro, poca abbastanza da non rileggere le stesse transazioni per un'ora.
  const since = state.since || Date.now() - 2 * 3600_000;

  const chunks = [];
  for (let k = 0; k < DOLPHIN_CHUNKS_PER_RUN && rw.nd > 0; k++) {
    const raw = await env.SEEKER.get("rd:" + ((cursor + k) % rw.nd));
    if (raw) chunks.push(JSON.parse(raw));
  }
  cursor = (cursor + DOLPHIN_CHUNKS_PER_RUN) % Math.max(rw.nd, 1);

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
  const read = async (list, whale) => {
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
      if (was !== 0 && was !== lam) movers.push({ a: x[0], w: whale });
      bal[x[1]] = lam;
    });
  };

  for (let i = 0; i < rw.w.length; i += 100) await read(rw.w.slice(i, i + 100), true);
  for (const c of chunks) await read(c, false);

  // Balene per prime: se la riserva finisce, deve finire sui più piccoli.
  movers.sort((a, b) => (b.w ? 1 : 0) - (a.w ? 1 : 0));
  for (const m of movers) {
    // Una firma più qualche transazione: senza questo margine l'ultimo
    // portafoglio della lista farebbe saltare l'intera esecuzione.
    if (left < 5) break;
    const sigs = await call("getSignaturesForAddress", [m.a, { limit: 5 }]);
    for (const s of sigs || []) {
      if (left <= 1) break;
      if (s.err || !s.blockTime) continue;
      const at = s.blockTime * 1000;
      if (at <= since) continue;
      const tx = await call("getTransaction", [
        s.signature,
        { encoding: "jsonParsed", maxSupportedTransactionVersion: 0 },
      ]);
      const b = buyOf(tx, m.a, m.w, at);
      if (b) buys.push(b);
    }
  }

  const now = Date.now();
  buys = buys.filter((b) => b.at > now - KEEP);
  const out = JSON.stringify(rank(buys, now, rw.n));
  const fp = fingerprint(out);

  // Lo stato (con i saldi dentro) sempre, il pubblicato solo se è cambiato: le
  // scritture del piano gratuito sono mille al giorno e questo le tiene sotto.
  if (fp !== state.crowd) await env.SEEKER.put("crowd", out);
  await env.SEEKER.put(
    "state2",
    JSON.stringify({
      buys, cursor, since: now - 30 * 60_000, spent: BUDGET - left, movers: movers.length, crowd: fp, at: now,
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

export default {
  async scheduled(event, env, ctx) {
    ctx.waitUntil(sweep(env));
  },
  async fetch(request, env) {
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
