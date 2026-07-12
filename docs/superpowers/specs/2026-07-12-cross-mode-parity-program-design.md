# Cross-Mode Parity Program Design

## Scope

Turn the parity claims in #645 into executable contracts. The program compares complete
observable state across backtest and live-paper, expands deterministic CLI reruns across
execution tiers, and prevents production logic from introducing unreviewed wall-clock reads.

The MT5 simulator golden test has a separate evidence requirement: its fixture must come
from a real demo session and retain the source ticks, submitted orders, broker deals, symbol
metadata, and capture provenance. Synthetic data may test the fixture reader but cannot be
presented as venue-fidelity evidence.

## Shared Harness

A test-only harness compiles the same DSL source independently for each mode and feeds both
engines the same ordered ticks. It captures and compares:

- the complete attributed trade tape, including order id, timestamp, side, quantity, price,
  and realized amount;
- final per-symbol positions and average entry prices;
- realized, unrealized, and total per-strategy PnL;
- halt reason, scope, and event timestamp when a halt rule is configured.

Independent compilation is required because compiled strategies contain mutable indicator
and rule-edge state. Reusing one instance would make the second run depend on the first.

The initial matrix covers candle/indicator bracket execution, trailing orders, GTD expiry,
`CLOSE`, `RESIZE`, latches, stacks, and daily/drawdown halts. Every case must produce a
non-vacuous state transition before parity is asserted.

## Tick-Resolved Reports

`--bars --tick-fills` and full-tick replay use different command lines, so raw evidence
commands are intentionally different. The parity test compares the complete parsed JSON
report after removing only provenance fields that describe the invocation itself. All
trading, PnL, risk, accounting, book, and Monte Carlo fields remain in the comparison.

At least one committed immutable tick-day fixture must exercise this path. Generated sine
tapes remain useful for adversarial both-hit coverage but do not satisfy the real-data row.

## CLI Determinism

Each supported tier runs twice from the same immutable inputs:

- DSL full-tick paper;
- DSL full-tick MT5 simulator;
- plain bars;
- bars with tick-resolved fills;
- portfolio backtest.

The normalized JSON report and report-bundle files must match byte-for-byte. Normalization
may remove only build timestamp and output-path provenance; it may not remove computed data.

## Wall-Clock Enforcement

A source-scan test rejects direct wall-clock APIs in deterministic production packages.
Allowed reads are listed by exact file and purpose for live transport deadlines,
observability latency, persistence timing, operator commands, and the `SystemClock`
adapter. A new read fails CI until the allowlist and parity rationale are reviewed together.

## MT5 Golden Fixture

The committed fixture format contains:

- capture timestamp, account mode, server timezone, and broker/profile identifier;
- exact `InstrumentMeta` values used by the session;
- ordered bid/ask ticks;
- submitted client orders and translated venue requests;
- resulting orders/deals, including executed volume, price, retcode, commission, swap,
  fees, and position tickets.

The simulator replays the ticks and requests, then compares order/deal outcomes within
explicit price and quantity tolerances. This row remains unproven until an authentic demo
fixture is supplied and reviewed.

## Compatibility And Cost

`BacktestResult` exposes final per-strategy positions so parity checks and downstream report
consumers can inspect the same independent-leg state used for strategy PnL. The field is appended
with an empty default to preserve source compatibility for existing constructors.

Portfolio strategy ids retain their colon-delimited domain form in JSON and CSV content. Only
per-strategy equity filenames encode `:` as `%3A`; unsafe path characters remain rejected before
any report file is written.

Generated parity tapes stay small. The real-data fixture is bounded to one trading day and stored
as the existing canonical gzip CSV format (626 KiB) so CI memory and repository growth remain
predictable. Its provenance sidecar records the immutable hash and explicitly states that it is
not MT5 venue evidence.

## Implemented Evidence

- `BacktestLiveParityTest` and `DslFeatureParityTest`: independently compiled DSL through
  backtest and live-paper with complete trades, positions, PnL, and halt-state comparison.
- `TickResolvedParityTest`: complete semantic JSON equality for generated adversarial data and a
  committed 78,696-tick EURUSD day.
- `CliTierDeterminismTest`: exact JSON and raw report-bundle equality across all five CLI tiers.
- `WallClockSourcePolicyTest`: exact-file allowlist for direct production wall-clock reads.

The MT5 demo golden row remains unimplemented and unproven pending an authentic capture.
