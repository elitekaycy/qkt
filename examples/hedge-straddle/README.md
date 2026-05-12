# Hedge straddle (pending mode)

A pending-order straddle for XAUUSD on Exness M5. At each configured session-hour open, places opposite-side STOP entries above and below the current price. Whichever direction breaks first becomes the live position; the other pending order auto-cancels via `OCO_ENTRY`.

## What this exercises

- `OCO_ENTRY { ... }` — entry-pair OCO submitted natively to MT5 as two pending stops linked one-cancels-other
- `NOW.hour_utc`, `NOW.minute_utc` — clock accessors for session-window gating
- `TIF GTD UNTIL NOW + 10m` — relative-deadline GTD expiry; cancellation propagates back to qkt within one poll cycle
- `POSITION.<stream>.holding_duration` — time-based exit (Phase 23 accessor)
- Per-leg `BRACKET` — SL and TP attach to whichever leg fills, MT5-native
- `STACK_AT MFE >= N WITHIN M ... BRACKET { ... }` — conditional stacks per side, three tiers each (Phase 27)

## Strategy logic

1. **Pre-session pending placement.** Five minutes before each session hour, place `BUY STOP` 5 points above the current price and `SELL STOP` 5 points below. Both legs share a 10-minute GTD expiry. Each leg carries three `STACK_AT` clauses that fire after the leg goes live.
2. **Whichever side breaks wins.** The first leg to trigger fills as the live position; its bracket (180-point SL, 150-point TP) attaches automatically. The other pending order is cancelled.
3. **Stacking the winner.** Once the winner is live, MFE is tracked from the fill price. As MFE crosses 10/20/30 points within 30/60/90 minutes respectively, three independent stack orders fire — each with its own 2/20 bracket, sized at 0.06 lots. Stacks track as independent legs and close on their own brackets, not the winner's.
4. **Winner timeout.** If the position is still open 2 hours after the fill, close it at market regardless of P&L. Prevents drift into overnight or off-session moves.

Session hours configured: 06–07, 12–15 UTC (London + NY opens).

## Stacking semantics

Each side's three `STACK_AT` clauses are independent:
- **Threshold + window.** A clause fires once when MFE first reaches its threshold, provided the elapsed time since the parent leg opened is under the window. If the window expires before the threshold is reached, the clause is abandoned for this lifecycle.
- **Independent bracket.** Each stack leg gets its own SL/TP bracket from the `BRACKET { ... }` block — closing the parent does not close stacks, and a stack hitting its own TP does not affect the parent or other stacks.
- **Per-side independence.** The BUY side's stacks attach only to a BUY winner; SELL side's stacks only to a SELL winner. Since `OCO_ENTRY` cancels the losing side before it fills, only one side's stacks ever activate per session.

## What's *not* here yet

- **Whipsaw filter (`cutClosePips`).** The production strategy requires the candle to close at least 20 points from entry before considering a breach valid, filtering out wicks that immediately retrace. Easily added as a `WHEN` condition once you have intrabar data.
- **Win-rate circuit breaker.** Daemon-level concern, not a strategy DSL feature.
- **Adaptive ATR thresholds.** Easily added: replace fixed `5` with `atr(gold, 14) * 0.5` etc.

## Running

### Parse

```bash
qkt parse examples/hedge-straddle/hedge-straddle.qkt
```

Should succeed without errors. This is what the smoke test exercises in CI.

### Backtest

Requires Exness XAUUSD M5 data in `~/.qkt/data/` (or pass `--data-root`). Use a `qkt fetch` workflow to populate:

```bash
qkt backtest examples/hedge-straddle/hedge-straddle.qkt \
    --from 2025-01-01 --to 2025-12-31 \
    --json
```

### Docker

The Dockerfile bundles `qkt` and runs against mounted strategy and data directories:

```bash
docker run --rm \
  --entrypoint qkt \
  -v "$HOME/.qkt/data:/data:ro" \
  -v "$(pwd)/examples/hedge-straddle:/strategies:ro" \
  qkt:latest \
  backtest /strategies/hedge-straddle.qkt \
    --from 2025-01-01 --to 2025-12-31 \
    --data-root /data \
    --json
```

### Live on MT5 (Exness)

Live trading via MT5 is supported as of Phase 26b/c/d:
- **Phase 26b** — native MT5 translation for `BUY_STOP` / `SELL_STOP` / `BUY_STOP_LIMIT` / `SELL_STOP_LIMIT` / trailing stops + OCO group tagging
- **Phase 26c** — position poller detects pending → position transitions; OCO sibling cancel-on-fill propagates within one poll cycle
- **Phase 26d** — `/orders` poller detects GTD-expired and externally-cancelled pendings; PERCENT trailing mode; order modification

The production scaffold at `~/Desktop/personal/qkt-strategies-live/` has docker-compose + `.env.example` + a `deploy.sh` helper. See its README for the go-live checklist (credentials, prereq check, paper-mode validation, lot-sizing tier-up).

**Phase 27 status:** the qkt strategy file now carries the three-tier `STACK_AT` clauses per side. End-to-end stack lifecycle (engine construction on parent fill, MFE-driven tier firing, parent-bracket close detection) works for the PaperBroker backtest path. Tracker-side stack-leg routing (so the `LegBook` reflects PRIMARY + STACK legs distinctly) is a follow-up; until it lands, stack fills currently average into the primary leg in the position view.

## Expected performance

Per the pa-quant backtest (6 years XAUUSD M5, 1.0 pip spread simulation, `cutClosePips: 20` whipsaw filter enabled):

| Year | Trades | WR % | Net Pips | Net P&L (0.01L) |
|------|--------|------|----------|-----------------|
| 2020 | 1,385 | 32.6% | −3,940 | −$39 |
| 2021 | 1,427 | 32.4% | −2,843 | −$28 |
| 2022 | 1,509 | 34.4% | +5,047 | +$50 |
| 2023 | 1,451 | 32.2% | −1,741 | −$17 |
| 2024 | 1,478 | 34.1% | +3,471 | +$35 |
| 2025 | 1,511 | 39.6% | +369,580 | +$3,696 |
| **Total** | **8,761** | **34.2%** | **+369,574** | **+$3,697** |

2025 dominates because of the gold bull-market regime — large directional sessions resolve cleanly. The strategy is regime-sensitive; ranging years lose small amounts.

The table above is the no-stack profile. Per the pa-quant analysis, the three-tier stacking adds roughly +148% on top (6-month P&L `$1,478 → $3,673`). qkt's stack-bearing port should approach those numbers once the tracker-side routing lands.

## References

- Phase 26a — DSL surface (`OCO_ENTRY`, `NOW.<field>`): `docs/phases/phase-26a-pending-oco-and-clock.md`
- Phase 26b — MT5 native pending translation: `docs/phases/phase-26b-mt5-pending-family.md`
- Phase 26c — Pending fill-event lifecycle: `docs/phases/phase-26c-pending-fill-lifecycle.md`
- Phase 26d — `/orders` poller, PERCENT, modify: `docs/phases/phase-26d-orders-percent-modify.md`
- Phase 27 spec (stacks): `docs/superpowers/specs/2026-05-12-phase27-conditional-bracketed-stacks-design.md`
- Production source: `../fxquant/pa-quant/src/strategies/hedge-straddle/`
- README in pa-quant: `../fxquant/pa-quant/src/strategies/hedge-straddle/README.md`
