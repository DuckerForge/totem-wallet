/**
 * ORE, letto dalla catena per tutti.
 *
 * Ogni minuto il worker legge la Board, e se il giro appena chiuso non e'
 * ancora in archivio lo legge e lo aggiunge a un anello di [KEEP] giri in
 * `/clearsign/ore.json`: per ogni giro la casella uscita, se il premio si e'
 * diviso o l'ha preso uno solo, il SOL e i minatori su ogni casella. Due
 * chiamate RPC al minuto qui, zero in piu' sui telefoni, che leggono l'archivio.
 *
 * Le regole e i byte sono quelli del programma `oreV3…`, verificati il 22
 * settembre 2026 in `core/src/test/kotlin/com/clearsign/core/OreMaskTest.kt`.
 */
const PROGRAM = "oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv";
const BOARD = "BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi";
const SPLIT = "SpLiT11111111111111111111111111111111111112";
export const KEEP = 120;
/** Quanti giri arretrati si recuperano per tick, se il worker e' stato fermo. */
const CATCH_UP = 8;

// ---- base58 ---------------------------------------------------------------------

const ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

export function b58decode(s) {
  let n = 0n;
  for (const c of s) {
    const i = ALPHABET.indexOf(c);
    if (i < 0) throw new Error("base58");
    n = n * 58n + BigInt(i);
  }
  const out = [];
  while (n > 0n) { out.unshift(Number(n & 255n)); n >>= 8n; }
  let pad = 0;
  for (const c of s) { if (c === "1") pad++; else break; }
  return new Uint8Array([...new Array(pad).fill(0), ...out]);
}

export function b58encode(bytes) {
  let n = 0n;
  for (const b of bytes) n = (n << 8n) | BigInt(b);
  let s = "";
  while (n > 0n) { s = ALPHABET[Number(n % 58n)] + s; n /= 58n; }
  let pad = 0;
  for (const b of bytes) { if (b === 0) pad++; else break; }
  return "1".repeat(pad) + s;
}

// ---- le PDA, senza librerie ----------------------------------------------------------

const P = (1n << 255n) - 19n;
const D = (P - 121665n * modInv(121666n)) % P;

function modPow(b, e, m) {
  let r = 1n; b %= m;
  while (e > 0n) { if (e & 1n) r = (r * b) % m; b = (b * b) % m; e >>= 1n; }
  return r;
}
function modInv(a) { return modPow(((a % P) + P) % P, P - 2n, P); }

/** Un punto compresso sta sulla curva di ed25519? Le PDA sono i 32 byte che NON ci stanno. */
function onCurve(bytes) {
  let y = 0n;
  for (let i = 31; i >= 0; i--) y = (y << 8n) | BigInt(bytes[i]);
  const sign = y >> 255n;
  y &= (1n << 255n) - 1n;
  if (y >= P) return false;
  const y2 = (y * y) % P;
  const u = (y2 - 1n + P) % P;
  const v = (D * y2 + 1n) % P;
  const x2 = (u * modInv(v)) % P;
  let x = modPow(x2, (P + 3n) / 8n, P);
  if ((x * x - x2) % P !== 0n) x = (x * modPow(2n, (P - 1n) / 4n, P)) % P;
  if (((x * x - x2) % P + P) % P !== 0n) return false;
  if (x === 0n && sign === 1n) return false;
  return true;
}

async function sha256(bytes) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

export async function findProgramAddress(seeds, programId) {
  const prog = b58decode(programId);
  const tail = new TextEncoder().encode("ProgramDerivedAddress");
  for (let bump = 255; bump >= 0; bump--) {
    const parts = [...seeds, new Uint8Array([bump]), prog, tail];
    const len = parts.reduce((n, p) => n + p.length, 0);
    const buf = new Uint8Array(len);
    let o = 0;
    for (const p of parts) { buf.set(p, o); o += p.length; }
    const h = await sha256(buf);
    if (!onCurve(h)) return b58encode(h);
  }
  return null;
}

export function le64(id) {
  const b = new Uint8Array(8);
  let n = BigInt(id);
  for (let i = 0; i < 8; i++) { b[i] = Number(n & 255n); n >>= 8n; }
  return b;
}

export const roundAddress = (id) => findProgramAddress([new TextEncoder().encode("round"), le64(id)], PROGRAM);

// ---- i byte del giro --------------------------------------------------------------------

function u64(bytes, off) {
  let n = 0n;
  for (let i = 7; i >= 0; i--) n = (n << 8n) | BigInt(bytes[off + i]);
  return n;
}

/** Il giro chiuso, compatto. Null se i byte non sono un Round chiuso. */
export function decodeRound(bytes) {
  if (!bytes || bytes.length < 952 || bytes[0] !== 109) return null;
  const b = 8;
  const hash = bytes.slice(b + 608, b + 640);
  if (hash.every((x) => x === 0) || hash.every((x) => x === 255)) return null;
  let r = 0n;
  for (let i = 0; i < 4; i++) r ^= u64(hash, 8 * i);
  const win = Number(r % 25n);
  const top = b58encode(bytes.slice(b + 912, b + 944));
  const d = [], c = [];
  let t = 0n;
  for (let i = 0; i < 25; i++) { const v = u64(bytes, b + 8 + 8 * i); d.push(Number(v)); t += v; c.push(Number(u64(bytes, b + 408 + 8 * i))); }
  return {
    id: Number(u64(bytes, b)),
    win,
    split: top === SPLIT,
    top: top === SPLIT ? null : top,
    d, c,
    m: Number(u64(bytes, b + 904)),
    r: Number(u64(bytes, b + 688)),
    ml: Number(u64(bytes, b + 648)),
    t: Number(t),
  };
}

function fromB64(s) {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/**
 * Un tick: la Board, e i giri chiusi che mancano all'anello. [rpc] e [fbGet]/[fbPut]
 * sono quelli del worker. Torna quanti giri ha aggiunto.
 */
export async function oreTick(env, rpc, fbGet, fbPut) {
  // La derivazione delle PDA si prova su un indirizzo noto prima di fidarsi.
  const boardPda = await findProgramAddress([new TextEncoder().encode("board")], PROGRAM);
  if (boardPda !== BOARD) return -1;
  const board = await rpc(env, "getAccountInfo", [BOARD, { encoding: "base64" }]);
  const bb = board && board.value && board.value.data ? fromB64(board.value.data[0]) : null;
  if (!bb || bb.length < 40) return 0;
  const current = Number(u64(bb, 8));
  const stored = JSON.parse((await fbGet(env, "ore")) || "{}") || {};
  const rounds = Array.isArray(stored.rounds) ? stored.rounds : [];
  const last = rounds.length ? rounds[rounds.length - 1].id : current - 1 - CATCH_UP;
  const wanted = [];
  for (let id = Math.max(last + 1, current - CATCH_UP); id <= current - 1; id++) wanted.push(id);
  if (!wanted.length) return 0;
  const keys = [];
  for (const id of wanted) keys.push(await roundAddress(id));
  const got = await rpc(env, "getMultipleAccounts", [keys, { encoding: "base64" }]);
  const vals = got && Array.isArray(got.value) ? got.value : [];
  let added = 0;
  for (let i = 0; i < wanted.length; i++) {
    const acc = vals[i];
    const round = acc && acc.data ? decodeRound(fromB64(acc.data[0])) : null;
    if (!round || round.id !== wanted[i]) continue;
    rounds.push(round);
    added++;
  }
  if (!added) return 0;
  while (rounds.length > KEEP) rounds.shift();
  await fbPut(env, "ore", JSON.stringify({ at: Date.now(), current, rounds }));
  return added;
}
