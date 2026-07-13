# MT5 live/history feed audit design

## Scope

Issue #54 requires measured live-feed accuracy evidence. The production deployment uses
the Exness MT5 gateway for both strategy prices and execution; TradingView is not in that
path and rejects the production VPS. The audit command therefore needs an equivalent
MT5 live-versus-history reference mode while preserving its existing TradingView mode.

## Capture and comparison

`qkt audit-ticks --reference mt5-history` polls `symbol_info_tick` at the configured
cadence. Each sample retains the MT5 `time_msc`, bid, ask, and local observation time.
After the capture window, the command waits five seconds by default before reading the
same UTC window through `copy_ticks_range`.

The MT5 symbol defaults to the suffix of `--symbol`, so both `AUDUSD` and
`EXNESS:AUDUSD` resolve through the selected profile. `--mt5-symbol` provides an explicit
base symbol when a TradingView identifier and MT5 identifier differ.

The comparator:

- excludes the initial cached quote when its venue timestamp predates the audit window;
- deduplicates repeated polls by `(time_msc, bid, ask)`;
- requires every observed in-window live quote to reappear with the same timestamp and
  bid/ask in raw history;
- reports timestamp matches, exact price matches, mismatches, missing ticks, quote-age
  distribution, and spread bounds;
- exits non-zero if no in-window tick was observed, any live quote fails exact replay,
  or an observed quote is crossed or nonpositive.

The settlement delay is necessary because the terminal can expose a new live quote just
before the history endpoint has committed it. It is configurable with `--settle-ms` for
diagnostics, but the default is the operator path.

## Evidence

`--json` and `--out` produce a versioned JSON artifact with UTC boundaries and all
comparison counts. A successful short run proves path integrity, not liquid-hours market
quality. Closing #54 still requires a representative market-hours capture whose artifact
and contextual bounds are recorded in `docs/operations/tick-feed-audit.md`.

## Risk

The new endpoint is read-only and used only by an operator CLI. It does not alter the
engine, broker placement path, or live feed. The existing TradingView comparison remains
the default for backwards compatibility.
