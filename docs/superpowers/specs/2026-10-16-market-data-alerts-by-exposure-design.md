# Market-data alerts by exposure

Date: 2026-10-16. Status: implemented on `feat/market-data-alert-by-exposure`.

## Problem

The live market-data gate pages Telegram (`strategy_error`, handler `MarketDataUnhealthy`) on every
unhealthy transition. On the hg20k prop book that is several pages a day, nearly all for copper:

| Broker gap (UTC, 2026-10-16) | Broker's own tick history | qkt STALE fired | qkt recovered after |
|---|---|---|---|
| 09:20:51 | 369 s without a copper quote | 09:21:52 | 360 s |
| 09:43:42 | 114 s | 09:44:43 | 53 s |
| 09:53:23 | 186 s | 09:54:24 | 126 s |
| 10:04:19 | 455 s | 10:05:20 | 420 s |

In the same window gold printed 676 ticks a minute and EURUSD 90. The gate was right every time.
The broker's copper feed really goes quiet for minutes, and the MT5 source polls
`/copy_ticks_range` with a cursor, so qkt receives the broker's own tick history. No tick can fall
between polls. The pages are true, but they don't help the operator: the book held nothing in copper,
so the safe action (suppress new copper orders) had already been taken, and there was nothing to do.

What the operator does need to hear:

1. Quotes stopped on a symbol the session holds a position or working orders in. Engine-held stops
   and time exits can't trigger without ticks.
2. Quotes stopped and stayed stopped while the venue is in session. A dead per-symbol feed silently
   stops that symbol's strategies from trading.
3. A clock skew or an outlier run. These are data-integrity faults, and they stay as loud as today.

## Design

The gate itself doesn't change. Thresholds, order suppression, PAUSED, out-of-session handling and the
insights `marketdata.stale` and `marketdata.recovered` events all stay exactly as they are. Only the
Telegram decision moves.

`MarketDataHealthAlerts` decides per episode:

- **CLOCK_SKEW or OUTLIER:** page at once, as before.
- **STALE with exposure** (non-zero position or any active order on the symbol in this session): page
  at once, naming the exposure, e.g. "market data unhealthy for X: quote age 61000ms exceeds 60000ms
  threshold; position open and 2 working order(s) on X: engine-held stops cannot trigger without
  ticks".
- **STALE without exposure:** hold the page. While the episode lasts, the engine heartbeat passes its
  age. The page goes out once, either when exposure appears or when the episode reaches
  `market_data.quiet_alert_after_ms` (default 600000, 10 minutes). Example: "market data for X: no
  quote for 10m while its venue is in session; nothing open on X here; new orders on X stay
  suppressed until quotes return".
- One page per episode. Recovery stays insights-only, as before.

The heartbeat reports silence counted in session only. The count starts at the stale alert, stops in a
scheduled break or out of session, and restarts when the venue reopens. A stale episode that runs into
the daily break or the weekend therefore never escalates on a gap the venue planned, and the
weekend's 48 hours don't page at Sunday's open.

`quiet_alert_after_ms: 0` restores the old behavior: every STALE pages at once.

### Components

- `FeedHealthJudge.isHealthy`: while a STALE episode is open, the symbol is in session and not in a
  scheduled break, it calls `onStillStale(symbol, unhealthyForMs)`. The engine heartbeat already calls
  `isHealthy` for every feed symbol, so this runs on the engine thread at the heartbeat cadence.
- `MarketDataGate` gains the `onStillStale` callback, defaulting to a no-op.
- `MarketDataGateConfig` gains `quietAlertAfterMs`, parsed from `quiet_alert_after_ms`; it must be
  zero or more.
- `MarketDataHealthAlerts` takes the threshold and an exposure lookup (`bind(pipeline)`: position
  quantity plus active orders on the symbol), and holds which symbols have been paged and which are
  held.

## Edge cases

- **Stale then flat then stale again:** each episode is decided on its own; the latches clear on
  recovery.
- **Exposure opens during a held episode** (for example a reducing or protective order that is still
  working): the next heartbeat pages.
- **The episode crosses into a scheduled break or the weekend:** the count is reset, and it restarts
  from the reopen if quotes still haven't returned.

## Related finding: the FX calendar ignores daylight saving

`FxCalendar` closes the FX week at a fixed Friday 22:00 UTC and opens it at Sunday 22:00 UTC. FX trades
on New York time (17:00 America/New_York), which is 21:00 UTC during US daylight saving. On hg20k,
2026-10-09, the gate raised STALE on NZDUSD, AUDUSD and EURUSD at 21:10 UTC, after the market had
closed for the weekend. The episode then lasted 172,527 s, until Sunday's open. This is fixed
separately, because the same calendar sets session ranges and anchors in backtests.
- **A portfolio child without exposure stays quiet** while the sibling child holding the symbol pages.
  Each child is its own session.
- **Notifier failure:** recorded as before (`recordFailure`); it never touches the gate.

## Testing

- Unit tests on `MarketDataHealthAlerts`:
  - STALE with no exposure holds the page;
  - the page goes out at the threshold;
  - the page goes out when exposure appears;
  - exposure pages at once;
  - CLOCK_SKEW and OUTLIER page at once;
  - one page per episode, with re-arming after recovery;
  - 0 restores the old behavior.
- Gate tests: `onStillStale` reports the episode age in session, and doesn't fire inside a scheduled
  break or out of session.
- Config tests: parse the new key, its default, and reject a negative value.
