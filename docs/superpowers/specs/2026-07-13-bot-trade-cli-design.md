# Bot trade CLI — one-shot manual/AI trading surface

Date: 2026-07-13
Status: Draft — awaiting approval

## Why

qkt strategies today trade only through deployed `.qkt` files. There is no way for a
human operator or an external AI agent to place a discretionary order, or to query
account state and indicators, from the command line. The goal is an AI overlay:
an external agent shells out to `qkt bot ...` to analyze (equity, positions, quotes,
indicator values) and act (buy, sell, close, modify, cancel), with every action
tracked, journaled, and egressed to qkt-insights exactly like a DSL strategy trade.

## Decisions (settled during brainstorm)

1. **Topology: one-shot, fire-and-forget.** Each invocation loads config, connects to
   the broker, acts, prints the result, and exits. No daemon required. Exit code 0
   only after a broker ack; nonzero with the broker's real error otherwise.
2. **Attribution: `--as <name>`, default `manual`.** The name becomes the strategyId
   on every journaled and egressed event, so distinct AI agents (or hand trades) are
   separately trackable in qkt-insights.
3. **Compile path: args → canonical DSL → compile.** CLI args render into a canonical
   one-shot DSL action string, parsed by the existing action grammar and compiled by
   `ActionCompiler` into a `Signal`/`OrderRequest`. Manual trades are versioned
   exactly like `.qkt` files because they literally are DSL source.
4. **Command group: everything lives under `qkt bot <verb>`** so the manual/AI surface
   is namespaced and diffable from the operator CLI (`qkt deploy`, `qkt status`, ...).

## Command surface

All commands accept `--config <path>` (fallback: `QKT_CONFIG` env, then
`./qkt.config.yaml`) and `--json` (single JSON object on stdout, for AI consumption).
Trading commands additionally accept `--as <name>` (default `manual`) and `--dry-run`
(compile + validate + print the canonical DSL and resolved order, submit nothing).

### Trading

```
qkt bot buy  0.5 EXNESS:XAUUSD                              # market
qkt bot buy  0.5 EXNESS:XAUUSD --sl by:30 --tp rr:2         # bracket, venue-attached
qkt bot sell 0.5 EXNESS:XAUUSD --limit 2680 --tif DAY       # pending limit
qkt bot buy  --sizing "1% RISK" EXNESS:XAUUSD --sl by:30    # DSL sizing forms
qkt bot close EXNESS:XAUUSD [--ticket N] [--partial 0.2] [--all]
qkt bot modify --ticket N [--sl <spec>] [--tp <spec>]
qkt bot cancel --order N | --all [--symbol S]
```

- Positional sizing is raw lots. `--sizing "<dsl sizing>"` accepts any DSL sizing
  expression (`1% RISK`, `2% EQUITY`, notional); live equity is fetched from the
  broker at compile time to resolve it.
- Entry: market by default; `--limit <px>`, `--stop <px>`, `--stop-limit
  <trigger>:<limit>` for pendings. `--tif GTC|DAY|GTD` with `--expires <iso>` for GTD.
- `--sl`/`--tp` specs mirror the DSL child-price forms:
  `2610` → `AT 2610`, `by:30` → `BY 30` (points), `pct:1.5` → `PCT 1.5`,
  `rr:2` → `RR 2` (tp only).

### Query (read-only)

```
qkt bot account                              # equity, balance, margin, free margin, currency
qkt bot positions [SYMBOL]                   # venue truth: tickets, entry, sl/tp, uPnL
qkt bot orders [SYMBOL]                      # pending orders
qkt bot quote EXNESS:XAUUSD                  # bid/ask/spread/time
qkt bot bars EXNESS:XAUUSD --tf 1h --count 100
qkt bot eval "ema(21) - ema(50)" EXNESS:XAUUSD --tf 1h [--bars 500]
qkt bot history [--since 2026-07-01]         # closed deals
```

`qkt bot eval` is the AI's analysis primitive: any DSL expression (the full indicator
and expression vocabulary) evaluated one-shot over bars fetched from the broker.
Queries read venue truth directly — positions opened by deployed strategies are
visible too, which is what an overseeing agent wants.

## Compile path detail

`qkt bot buy 0.5 EXNESS:XAUUSD --sl by:30 --tp by:60` renders to canonical DSL:

```
BUY EXNESS:XAUUSD SIZING 0.5 BRACKET { STOP LOSS BY 30, TAKE PROFIT BY 60 }
```

Flow: renderer → `Dsl.parseAction(text)` (new rule-less parser entry point reusing the
existing action grammar; grammar itself unchanged) → `ActionCompiler` with a one-shot
context (live equity, instrument metadata) → `Signal.Submit(OrderRequest.Bracket(...))`
→ pre-trade validation → broker submit (venue-attached SL/TP) → ack → egress/journal
→ exit.

The canonical text, its sha256, the qkt version, the `--as` id, and the raw argv are
recorded on every trade. Identity model matches deployed strategies: name + source
sha256 + version.

## Fail-closed surface

One-shot mode rejects, with an error that says why and points at deploying a `.qkt`
instead, anything that needs a persistent engine to manage:

- Trailing stops (all forms — MT5 trailing is client-side, nothing stays alive to run it)
- Armed trails, scale-out, time-exit, STACK, LATCH
- Brackets on brokers without venue-attached SL/TP support (no engine decomposition)

## Tracking and egress

- **Insights**: strategyId = `--as` name. Each trading command emits a `bot.command`
  envelope (canonical DSL, sha256, qkt version, argv) — the one-shot analogue of
  `strategy.started` — plus the standard order envelopes (submitted / accepted /
  rejected) through the existing `InsightsSink`, flushed before process exit.
- **Journal**: `OrderJournal` append under `<stateRoot>/bot/<as>/`.
- **Documented divergence**: the process exits after ack. A later fill of a pending
  limit is not egressed by this process; venue-truth pollers (qkt-insights broker
  state poller) still observe it.

## Risk posture

One-shot pre-trade subset: instrument validation, volume quantization, min/max volume,
quote-currency guard, and config-driven caps. Session-stateful risk (daily drawdown,
halt state, book de-risk) cannot apply — there is no session. This is a documented
divergence from deployed-strategy risk.

Config gains an optional `botRisk` block (per `--as` name or default): max lots per
order, max open exposure per symbol, allowed symbols. Absent block → validation-only.

## Code layout

- `com.qkt.trade` — `TradeSession` (one-shot lifecycle: config → broker connect →
  act → ack → egress → teardown), the args→canonical-DSL renderer, `--sl/--tp` spec
  parsing, pre-trade validation subset.
- `com.qkt.cli.bot` — the `bot` command group: a sub-dispatcher registered under
  `bot` in `Main.kt`'s `when`, one command class per verb, following the existing
  `Args`/`Config` conventions.
- `com.qkt.dsl` — `Dsl.parseAction(text)` entry point (small parser addition).

## Documentation structure

- This spec + an implementation plan under `docs/superpowers/`.
- Phase changelog after merge (`docs/phases/`), per the standard lifecycle.
- `docs/bot-cli.md` — user-facing cookbook: every command, flag reference, JSON output
  schemas, exit codes, error codes, the fail-closed list, and the risk divergences.
- `examples/ai-overlay/` — the getting-started template: a ready-to-paste AI
  system-prompt describing the command surface and schemas (always `--json`, check
  `qkt bot account` before sizing, ...) plus a starter `qkt.config.yaml`.

## Prerequisites

- MT5 gateway reachable and the broker configured in `qkt.config.yaml` (symbol prefix
  must resolve against the configured brokers, fail-closed as in `LiveSession`).
- `insights` config present if egress is wanted (absent → journal only, no egress).
- Instruments configured for the symbols traded (quantization, quote-currency guard).

## Testing

- Renderer tests: args → exact canonical DSL text (table-driven).
- Parser tests for `parseAction` (valid actions, rejected rule-only constructs).
- Compile tests: canonical DSL → expected `OrderRequest` shape (AstCompiler-test style).
- Validation tests: fail-closed list, quantization, `botRisk` caps.
- E2E against the mock broker: buy/sell/close/modify/cancel, ack semantics, exit codes,
  `--json` schema stability.
- Authentic MT5 demo golden capture for buy → close round-trip (existing pattern).

## Out of scope

- Engine-managed order types from the CLI (trailing, stack, latch) — deploy a `.qkt`.
- A daemon order-entry route (`POST /order`) — possible future extension if
  engine-managed manual trades are ever wanted.
- Authentication on any surface (one-shot talks broker-direct, not HTTP).
