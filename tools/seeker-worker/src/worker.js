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
 * completo si chiude in quindici minuti.
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
 * cinquantunesima non rallenta, *lancia*. La prima versione ne faceva 34 per i
 * saldi e poi fino a quattro per ogni portafoglio in movimento: finché nessuno si
 * muoveva andava, e dal primo movimento in poi ogni esecuzione moriva prima di
 * salvare. Il cursore restava fermo e nessun acquisto arrivava mai.
 *
 * Quindi si conta. Quarantacinque, con cinque di margine per i tentativi.
 */
const BUDGET = 45;
/**
 * Le balene si leggono tutte a ogni giro; i delfini a turno.
 *
 * Non è un compromesso al ribasso: le balene sono 1.252, cioè tredici blocchi, e
 * sono quelle che vale la pena vedere subito. I 9.275 delfini ruotano e si
 * chiudono in poco più di un'ora, che per loro basta.
 *
 * Il numero è tarato sulla riserva, non scelto a occhio: con tredici blocchi di
 * delfini restavano diciannove chiamate per approfondire, cioè tre portafogli su
 * undici che si erano mossi. Leggere i saldi non serve a niente se poi non resta
 * abbastanza per guardare cosa hanno fatto.
 */
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

/** L'elenco dei portafogli, dal file che sta anche dentro l'app. */
async function roster(env) {
  const raw = await env.SEEKER.get("roster");
  if (!raw) return [];
  const out = [];
  for (const line of raw.split("\n")) {
    const l = line.trim();
    if (!l || l[0] === "#") continue;
    const p = l.split(" ");
    if (p.length < 3 || p[0].length < 32) continue;
    out.push({ a: p[0], w: p[1] === "b" });
  }
  return out;
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

async function sweep(env) {
  const all = await roster(env);
  if (!all.length) return;

  const state = JSON.parse((await env.SEEKER.get("state")) || "{}");
  const bal = state.bal || {};
  let buys = state.buys || [];
  let cursor = state.cursor || 0;
  // Mezz'ora di sovrapposizione: abbastanza da non perdere niente fra un giro e
  // l'altro, poca abbastanza da non rileggere le stesse transazioni per un'ora.
  const since = state.since || Date.now() - 2 * 3600_000;

  // Una sola riserva, spesa da tutti. Quando finisce si smette e si salva quello
  // che si è fatto: un giro parziale salvato vale infinitamente più di un giro
  // completo che muore.
  let left = BUDGET;
  const call = async (method, params) => {
    if (left <= 0) return null;
    left--;
    return rpc(env, method, params);
  };

  const chunk = (arr) => {
    const out = [];
    for (let i = 0; i < arr.length; i += 100) out.push(arr.slice(i, i + 100));
    return out;
  };
  const whaleChunks = chunk(all.filter((x) => x.w));
  const dolphinChunks = chunk(all.filter((x) => !x.w));

  const movers = [];
  const read = async (c) => {
    const res = await call("getMultipleAccounts", [
      c.map((x) => x.a),
      { encoding: "base64", dataSlice: { offset: 0, length: 0 } },
    ]);
    if (!res || !res.value) return;
    c.forEach((x, i) => {
      const lam = res.value[i] ? res.value[i].lamports : 0;
      const was = bal[x.a];
      // Alla primissima lettura non c'è un prima: si registra e basta, perché
      // inventare un movimento da un confronto che non esiste segnerebbe tutti.
      if (was !== undefined && was !== lam) movers.push(x);
      bal[x.a] = lam;
    });
  };

  for (const c of whaleChunks) await read(c);
  for (let k = 0; k < DOLPHIN_CHUNKS_PER_RUN && dolphinChunks.length; k++) {
    await read(dolphinChunks[(cursor + k) % dolphinChunks.length]);
  }
  cursor = (cursor + DOLPHIN_CHUNKS_PER_RUN) % Math.max(dolphinChunks.length, 1);

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
  const out = rank(buys, now, all.length);

  await env.SEEKER.put("crowd", JSON.stringify(out));
  await env.SEEKER.put(
    "state",
    JSON.stringify({ bal, buys, cursor, since: now - 30 * 60_000, spent: BUDGET - left, movers: movers.length }),
  );
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
