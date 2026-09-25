#!/usr/bin/env node
// Il guardiano di Scout. Gira FUORI da Cloudflare, di proposito.
//
// Scout e' morto due volte in silenzio: il 15/09/2026 di CPU (`exceededCpu` a 10 ms
// leggendo 612 KB di JSON) e il 16/09 con le mille scritture KV al giorno esaurite
// dalle letture degli utenti. Le due volte il primo segnale e' stato accorgersene
// guardando.
//
// Perche' fuori: uno dei modi in cui il worker muore e' Cloudflare che smette di
// invocarlo, cron compresi, quando l'account supera le centomila richieste al
// giorno. Un allarme dentro il worker morirebbe con lui, zitto, come le altre due
// volte. Questo vive su GitHub Actions, che e' un altro sistema e un altro budget.
//
// E guarda il sintomo, non le cause: "Scout si e' aggiornato di recente, si' o no".
// Quella domanda prende tutti e tre i guasti noti, e anche il quarto che non
// abbiamo previsto.
//
// Uso: ARCHIVE_URL=https://<db>.firebasedatabase.app node watchman.mjs

const base = (process.env.ARCHIVE_URL || "").replace(/\/+$/, "");
if (!base) {
  console.log("::error::manca ARCHIVE_URL. Su GitHub e' un secret del repo, in locale una variabile d'ambiente.");
  process.exit(1);
}

const num = (name, fallback) => {
  const v = Number(process.env[name]);
  return Number.isFinite(v) && v > 0 ? v : fallback;
};

// Soglie. Si superano da fuori per provare che l'allarme funziona davvero: un
// workflow rosso che non manda la mail non serve a niente.
const STALE_MIN = num("WATCHMAN_STALE_MIN", 25);   // due giri persi su un cron da 10 minuti
const CROWD_STALE_H = num("WATCHMAN_CROWD_STALE_H", 6);
const WRITES_MAX = num("WATCHMAN_WRITES_MAX", 750); // su mille al giorno, il cron ne usa ~430
const STARVED_MAX = num("WATCHMAN_STARVED_MAX", 3);

async function read(path) {
  const r = await fetch(`${base}/clearsign/${path}.json`, { signal: AbortSignal.timeout(20_000) });
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  const t = (await r.text()).trim();
  return t && t !== "null" ? JSON.parse(t) : null;
}

const minutes = (ms) => Math.round(ms / 60_000);
const problems = [];
const say = (m) => problems.push(m);

let health, crowd;
try {
  [health, crowd] = await Promise.all([read("health"), read("crowd")]);
} catch (e) {
  // L'archivio non risponde. Non e' il worker, ma e' comunque cieco: senza questa
  // lettura non si sa niente, e non saperlo e' il problema che stiamo risolvendo.
  console.log(`::error::archivio non raggiungibile (${e.message}). Scout potrebbe essere fermo e non si vede.`);
  process.exit(1);
}

const now = Date.now();

if (!health) {
  say(
    "nessun bigliettino in /clearsign/health: il worker non lo scrive. " +
      "Se hai appena aggiunto la sentinella, manca `npx wrangler deploy`. " +
      "Se girava e ora non c'e' piu', la scansione e' ferma.",
  );
} else {
  const age = now - (health.at || 0);
  if (age > STALE_MIN * 60_000) {
    say(
      `Scout e' fermo da ${minutes(age)} minuti (soglia ${STALE_MIN}). ` +
        "Guarda `npx wrangler tail`: se non dice niente, l'account ha finito le richieste del giorno.",
    );
  }
  if ((health.w || 0) > WRITES_MAX) {
    say(
      `scritture KV oggi ${health.w} su mille (soglia ${WRITES_MAX}). ` +
        "Il cron da solo ne usa ~430: se sono salite, qualcosa scrive che non dovrebbe. E' il guasto del 16/09.",
    );
  }
  if ((health.s || 0) >= STARVED_MAX) {
    say(
      `${health.s} giri di fila hanno finito le 45 chiamate in uscita. ` +
        "La scansione arranca: i delfini girano piu' lenti di quanto dovrebbero.",
    );
  }
}

if (!crowd || !crowd.at) {
  say("nessuna classifica pubblicata in /clearsign/crowd.");
} else {
  const age = now - crowd.at;
  if (age > CROWD_STALE_H * 3600_000) {
    say(`la classifica pubblicata ha ${Math.round(age / 3600_000)} ore (soglia ${CROWD_STALE_H}).`);
  }
}

if (problems.length) {
  for (const p of problems) console.log(`::error::${p}`);
  process.exit(1);
}

const rows = Array.isArray(crowd.rows) ? crowd.rows.length : 0;
console.log(
  `Scout sta bene. Ultimo giro ${minutes(now - health.at)} min fa, ` +
    `${health.w} scritture KV oggi, ${health.spent}/45 chiamate nel giro, ${rows} monete in classifica.`,
);
