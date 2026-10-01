# Phase 43.2: option chain snapshots — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A point-in-time record of an option chain that backtests read and live builds forward:
`chains/<VENUE>/<ROOT>/<YYYY-MM-DD>.csv.gz`, one row per contract per snapshot instant, from two free
Deribit sources — historical trades (sparse: mark, IV and index at each trade, no quotes) and the live
book summary (dense: bid, ask, mark, mark IV, the expiry's forward, rate).

**Architecture:** `ChainQuote(atMs, contract, bid?, ask?, mark, markIv?, underlying, rate?, markAgeMs,
source)` rows; a `ChainSnapshot` is every row at one instant. `ChainSnapshotStore` writes and reads
days (gzip CSV with a fixed header; rows sorted by instant then contract) and answers
`latestAtOrBefore(root, t)` without look-ahead. `TradeChainBuilder` folds trades into snapshots at a
fixed interval: a contract appears once it has traded and is not expired, carrying the mark/IV/index
of its last trade at or before the instant and that trade's age. `DeribitTradeHistory` pages
`get_last_trades_by_currency_and_time` (asc, `has_more`, dedupe by `trade_id`), filtered to the root.
`DeribitBookSnapshot` turns one `get_book_summary_by_currency` response into a snapshot (age 0).
CLI: `qkt fetch DERIBIT:<ROOT> --chains --from … --to … [--every 1h]` and `qkt chains snapshot
DERIBIT:<ROOT>` (one live snapshot, for a scheduler).

**Spec:** §6.3; research `docs/research/2026-10-01-deribit-options-free-data.md` §4–6

## Global constraints

- Nothing outside the options code paths changes; CFD/futures pins green.
- No look-ahead: a snapshot at `t` uses only trades with timestamp ≤ `t`; `latestAtOrBefore` never returns a later snapshot.
- Rows only for catalogued contracts of the root; unknown names are counted and reported, not guessed.
- Decimals as exact literals; timestamps epoch ms UTC; files ≤ 200 lines; KDoc; commits via the helper.

## Review focus

- A contract that has not traded yet at an instant is absent (never zero-priced); an expired one disappears at its expiry.
- Trades arriving out of order or duplicated across page boundaries yield the same snapshots as a clean feed.
- A day file read back equals what was written (round trip, gzip); a missing day reads as empty, a corrupt one fails naming the file.
- Live snapshot rows with one-sided books keep the missing side null.
- Index/underlying taken from the instrument's own trade (trades) or its expiry's forward (book), never mixed silently.

---

### Task 1: ChainQuote, ChainSnapshot and ChainSnapshotStore
### Task 2: TradeChainBuilder (pure; interval, staleness via markAgeMs, expiry cut)
### Task 3: DeribitTradeHistory and `qkt fetch <ROOT> --chains`
### Task 4: DeribitBookSnapshot and `qkt chains snapshot <ROOT>`
Each task: failing tests first (recorded responses with provenance for the Deribit pieces), then code,
then commit; a real fetch of a recent BTC_USDC week recorded in the research note.
