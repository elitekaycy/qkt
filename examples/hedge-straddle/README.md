# Hedge straddle (pending mode)

A pending-order straddle for XAUUSD on Exness M5. At each configured session-hour open, places opposite-side STOP entries above and below the current price. Whichever direction breaks first becomes the live position; the other pending order auto-cancels via `OCO_ENTRY`.

## What this exercises

- `OCO_ENTRY { ... }` — entry-pair OCO submitted natively to MT5 as two pending stops linked one-cancels-other
- `NOW.hour_utc`, `NOW.minute_utc` — clock accessors for session-window gating
- `TIF GTD UNTIL NOW + 10m` — relative-deadline GTD expiry; cancellation propagates back to qkt within one poll cycle
- `POSITION.<stream>.holding_duration` — time-based exit (Phase 23 accessor)
- Per-leg `BRACKET` — SL and TP attach to whichever leg fills, MT5-native

## Strategy logic

1. **Pre-session pending placement.** Five minutes before each session hour, place `BUY STOP` 5 points above the current price and `SELL STOP` 5 points below. Both legs share a 10-minute GTD expiry.
2. **Whichever side breaks wins.** The first leg to trigger fills as the live position; its bracket (180-point SL, 150-point TP) attaches automatically. The other pending order is cancelled.
3. **Winner timeout.** If the position is still open 2 hours after the fill, close it at market regardless of P&L. Prevents drift into overnight or off-session moves.

Session hours configured: 06–07, 12–15 UTC (London + NY opens).

## What's *not* here yet

- **Stacking.** The production hedge-straddle adds 3 independent micro-trades when the winner shows conviction. Per the pa-quant analysis, stacking boosts 6-month P&L by ~148% (`$1,478 → $3,673`). qkt's existing `STACK` clause uses shared brackets and sequential triggering, which can't model the per-stack-bracket simultaneous-fire pattern hedge-straddle needs. Phase 27 (in progress) ships this — see `docs/superpowers/specs/2026-05-12-phase27-conditional-bracketed-stacks-design.md`.
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

**Stacking is still ahead** (Phase 27). Expect P&L tracking the no-stack profile in the table below until Phase 27 lands.

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

The qkt port omits stacks until Phase 27 ships. The analysis shows stacking roughly doubles 6-month P&L; expect the table above to track the no-stack profile until then.

## References

- Phase 26a — DSL surface (`OCO_ENTRY`, `NOW.<field>`): `docs/phases/phase-26a-pending-oco-and-clock.md`
- Phase 26b — MT5 native pending translation: `docs/phases/phase-26b-mt5-pending-family.md`
- Phase 26c — Pending fill-event lifecycle: `docs/phases/phase-26c-pending-fill-lifecycle.md`
- Phase 26d — `/orders` poller, PERCENT, modify: `docs/phases/phase-26d-orders-percent-modify.md`
- Phase 27 spec (stacks): `docs/superpowers/specs/2026-05-12-phase27-conditional-bracketed-stacks-design.md`
- Production source: `../fxquant/pa-quant/src/strategies/hedge-straddle/`
- README in pa-quant: `../fxquant/pa-quant/src/strategies/hedge-straddle/README.md`
