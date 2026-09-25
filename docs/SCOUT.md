# Scout — what Seeker owners actually buy

Every Seeker ships with a **Genesis Token**: a Token-2022 NFT, minted once per phone and
**non transferable**. One token is one device, and because nobody can move it, the set of
holders can only grow. Census it once and the answer stays true.

So we did, on 14 September 2026. Group `GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te`.

| | |
|---|---|
| Seekers on chain | **120,520** |
| holding at least 1 SOL | **10,527** (8.7%) |
| whales, 10 SOL or more | **1,252** |
| 100 SOL or more | 134 · 500 SOL or more: 25 |
| the median Seeker | **0.032 SOL**, about seven dollars |

The number that reframed the product: **they are not farming.** In a sample of 250 wallets
under 1 SOL, none had ever transacted, 11% had moved in the last week, and **89% were
still**, a median of **209 days** since their last move. People switched the phone on,
claimed the airdrops, and stopped.

An honest limit, stated because it changes how you read the rest: we look at the Seed Vault
wallet. Someone who owns a Seeker but trades from Phantom looks dead to us and is not. The
cohort is right for anything tied to *that* wallet holding *that* token, and wrong for
anything else.

## One scanner, everybody reads

The obvious design, every phone scans for itself, collapses at the second user: a hundred
users is a hundred identical scans and a one-million-credit key gone by lunchtime.

```
Cloudflare Worker (cron, every 10 min) ──> KV + archive ──> every phone reads a small file
```

Finding who moved costs **one call per hundred wallets**, because a swap always shifts the
balance, if only by the fee. Only the wallets that moved cost a second lookup. Asking per
wallet would cost a hundred times more, and that single choice is why this runs inside a
free tier: roughly 670,000 Helius credits a month out of a million, and two KV writes per
run out of a thousand a day.

The engineering notes, including why the state is split into three pieces and what killed
it twice, live in [`../tools/seeker-worker/README.md`](../tools/seeker-worker/README.md).

## Three rules we do not relax

**Purchases only, never holdings.** A leaderboard of what this crowd *holds* returns
airdrops worth zero. Of the 34 tokens they hold, 19 are worth nothing at all.

**Three distinct wallets, or the coin does not exist.** Across 60 whales in a week the best
looking "trend" was one person buying the same coin ten times.

**A floor of 0.005 SOL.** Thirteen of ninety-nine purchases fell below it, and every one was
an airdrop claim, where the only SOL leaving the wallet is the fee and the account rent. A
higher floor of 0.02 erased the real signal too.

A fourth rule, learned from a whale wallet holding three different mints the token registry
all calls "USDC", two of them counterfeit: **a symbol is never proof of what a token is.**
Nothing gets a name on screen without a price behind it.

## What the phone shows

A live feed of purchases as they land, a ranking of coins that cleared the three-wallet
rule in the last 72 hours, what the cohort holds with the worthless half greyed out, and
the whales. Every row leads to a swap that opens the same receipt as any other transaction.

Copy trading, where it exists, produces **candidates and not orders**: two or more whales on
the same coin within an hour puts it in front of the agent's gates, which still decide. A
fifteen minute old memecoin signal, followed blindly, usually buys the exit of whoever was
early.
