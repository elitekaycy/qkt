# Exhaustive Live Parity Validation Design

## Purpose

Prove, with retained and reviewable evidence, which parts of qkt behave correctly across
deterministic replay, bar replay, the live engine, the local MT5 gateway, reporting, journals,
and QKT Insights. A green build alone is not production-readiness evidence.

This program runs before adapting or promoting external strategy books. Strategy promotion is
the final stage, after the engine, DSL, indicators, execution lifecycle, accounting, portfolio
isolation, and Insights attribution have passed their required evidence rows.

## Scope

The validation catalog covers:

- every registered DSL indicator and numeric function;
- expressions, state references, schedules, sequences, sessions, baskets, and `FOR EACH`;
- every normalized `OrderRequest`, time-in-force value, cancellation path, and lifecycle event;
- sizing, risk, positions, equity, realized and unrealized PnL, commissions, fees, spread, and swap;
- reports, evidence manifests, engine journals, transport journals, and Insights envelopes;
- single strategies, multi-strategy portfolios, multiple symbols, and multiple timeframes;
- fresh deployment, already-deployed state, restart, disconnect, recovery, and reconciliation.

The catalog is exhaustive over the supported language and runtime surface, not over every possible
numeric parameter tuple. Interaction coverage uses boundary cases, equivalence partitions, and
pairwise combinations, with full combinations for high-risk stateful features such as OCO/OTO,
brackets, partial fills, expiry, cancellation, restart, and attribution.

## Evidence Axes

Every catalog row declares each axis as `required`, `not_applicable`, or `gap`. A
`not_applicable` row requires a reason. A `gap` is an explicit incomplete result and cannot be used
to approve promotion.

| Axis | Required evidence |
| --- | --- |
| `oracle` | Expected numeric or state result from a hand calculation, retained fixture, or independent implementation |
| `dsl` | Real DSL compiles and produces a non-vacuous decision using the capability |
| `ticks` | Deterministic raw-tick replay records decisions, orders, fills, accounting, and final state |
| `bars` | Ordinary OHLCV bar replay records the same observable fields within its documented information limits |
| `tickResolvedBars` | Bars built from the raw tape plus `--tick-fills` match full-tick semantic output |
| `livePaper` | The shared live engine consumes the same ordered events and matches supported replay semantics |
| `mt5Demo` | A bounded local-gateway scenario verifies translation and actual demo-account lifecycle behavior |
| `reports` | Result bundle, costs, PnL, rejections, positions, and evidence manifest are complete and internally consistent |
| `journal` | Engine and transport journals contain the expected ordered events and correlation identifiers |
| `insights` | QKT Insights receives the exact event cardinality with correct strategy and book attribution |
| `portfolio` | Multiple children remain isolated while book totals reconcile to their children and broker account |

Parser-only failures, registry-construction tests, or empty trade tapes do not satisfy behavioral
axes. Parity tests must independently compile mutable strategies per mode and must prove the tested
feature became ready, changed a decision, and caused the expected state transition.

## Tick And Bar Matrix

Bars are a first-class validation path. Each applicable strategy scenario runs against:

1. the immutable raw tick tape;
2. OHLCV bars independently reconstructed from that tape;
3. ordinary bar replay;
4. bar replay with tick-resolved fills;
5. the live engine fed the same ordered ticks; and
6. live candles compared with the independently reconstructed bars.

The required timeframe set starts at every timeframe accepted by the DSL and includes at least 1m
and 5m for live demo exercises. Sub-minute testing is required only where both the DSL and source
advertise support; unsupported values must fail explicitly rather than being rounded silently.

Adversarial fixtures cover exact boundaries, warm-up, missing intervals, gaps, duplicate ticks,
out-of-order ticks, simultaneous timestamps, session and day rollover, daylight-saving transitions,
multiple symbols, and simultaneous timeframes. Cross-symbol ordering is stable and retained.

Ordinary OHLC bars cannot reveal the order in which an intrabar high and low occurred. Results that
depend on that order are not expected to equal full-tick replay. Such cases must be classified as an
information limitation, exercise the documented fill policy, and use tick-resolved bars for exact
semantic comparison. The report must never present plain-bar equality as tick-level fidelity.

## Independent Oracles

Indicator tests do not prove correctness by comparing two paths that call the same indicator
instance or algorithm. Each indicator family has fixed inputs and independently derived readiness
and output values. Multi-output families verify every exposed line. Stateful and session indicators
also verify resets, gaps, and boundary behavior.

DSL scenarios expose enough trace information to connect:

`input -> closed candle/tick -> indicator value -> rule edge -> signal -> normalized order -> broker
request -> accepted/rejected/cancelled/expired/filled event -> position/PnL -> report/journal/Insights`

Every correlation hop is asserted. Merely observing the final position is insufficient.

## Generated Strategy Scenarios

The harness creates purpose-built temporary `.qkt` strategies for each applicable catalog row.
Each scenario includes a matching configuration and risk setup, stable strategy/book/deployment/run
identifiers, bounded symbols and timeframes, and explicit expected decisions and cleanup state.
Where the CLI supports both forms, the scenario runs once with `qkt.config.yaml` and once with
equivalent explicit command-line configuration. Risk coverage includes strategy sizing, account
risk, book allocation, broker volume normalization, and rejection at every enforced boundary.

Every generated live scenario records the complete causal chain:

`gateway tick/bar -> qkt candle -> indicator value/readiness -> DSL rule edge -> signal -> risk and
quantity -> normalized order -> gateway request -> MT5 order/deal -> fill and costs -> position/PnL
-> report/journal -> Insights envelope`

The strategy source, effective configuration, sanitized risk configuration, input capture, trace,
and outputs are retained under the run manifest. Temporary credentials and secrets are not.

## Local MT5 Demo Safety

Live venue exercises use only the MT5 gateway bound to localhost. Remote tunnel URLs are forbidden.
The harness obtains credentials from the existing local environment without printing or persisting
them and refuses to run unless all of these checks pass:

- gateway health and readiness are healthy;
- account login, server, currency, and demo trade mode match an explicit allowlist;
- trading and expert execution are allowed;
- the account begins in the scenario's expected clean state, or reconciliation explains every
  existing order and position;
- quantity is the instrument minimum and no greater than 0.01 lots for the planned FX exercises;
- one bounded scenario owns all orders it creates and always performs cancellation, close, and
  final reconciliation.

The harness stops on an unexpected account, unknown position, ambiguous attribution, kill switch,
or cleanup failure. It never uses real capital.

## Sustained Polling And Recovery

Non-blocking behavior is established with measurements, not inferred from unit-test structure.
The local gateway exercise records duration, symbols, timeframes, tick and bar counts, polling
latency percentiles and maxima, queue high-water marks, dropped/duplicate/out-of-order events,
engine processing latency, thread stalls, reconnects, memory trend, and cleanup state.

The staged runs include a short correctness run, a multi-symbol/multi-timeframe load run, restart
and resynchronization cases, and the documented 30-day demo burn-in. Disk and network operations
remain outside the tick/bar engine hot path. No artificial JVM heap restriction is introduced by
this validation program.

Serial live scenarios establish causality before concurrency is introduced. The stress stage then
runs multiple QKT Docker containers against the shared localhost gateway. Each container has an
isolated state directory, configuration, journal, logs, and unique strategy/book/deployment/run
identity. Concurrency increases in measured waves across symbols and timeframes. The account-wide
reconciler must agree with the union of container-owned state while each container and Insights view
contains only its own attributable activity. Restarting one container must not adopt, duplicate,
cancel, or report another container's orders.

## Accounting And Artifact Integrity

Each scenario reconciles order requests, venue orders, deals, fills, positions, cash, equity,
realized and unrealized PnL, spread, commission, fees, and swap. Values use venue metadata and
account currency at one translation boundary. Unknown costs remain explicitly unknown; they are not
silently converted to zero.

Every run retains a manifest containing qkt commit, dirty-worktree state, strategy hash, config hash,
input hashes, gateway version, sanitized account/server identity, timestamps, mode, timeframe,
random seed, commands, scenario result, and SHA-256/size for every artifact. Secrets and remote
gateway URLs are excluded.

## QKT Insights And Attribution

Insights validation follows engine/live validation. It asserts exact counts and content for orders,
deals, fills, positions, equity, risk, lifecycle, journal, and heartbeat events. Retries must be
idempotent and ordered according to the documented contract.

Strategy and book identifiers are traced from compiled strategy through composite order children,
broker correlation, journal, and Insights. A broker deal without a proven correlation remains
unattributed. It must never be copied to every running strategy or book. Per-strategy and per-book
totals reconcile to the account overview without sharing mutable accounting state.

## External Strategy Books

The downloaded strategy archive is input to the final stage, not evidence that qkt is correct. Each
strategy must first parse against the current grammar, declare compatible symbols/account mode/risk,
pass the same tick/bar/live/Insights matrix, and receive an explicit risk review. Strategies are then
assembled into three or four independently attributable books and promoted in measured demo waves.

## Completion Criteria

The program is complete only when:

- the machine-readable catalog exactly matches all registered indicators, functions, normalized
  order types, time-in-force values, and enumerated DSL/runtime capabilities;
- every required evidence cell passes after all fixes, with retained artifacts and no unexplained
  reconciliation differences;
- the entire matrix passes again after the final defect fix;
- local MT5 stress, restart, already-deployed, and the required 30-day demo burn-in pass;
- QKT Insights strategy/book isolation and account reconciliation pass; and
- residual unsupported behavior is rejected clearly and documented without a production-readiness
  claim.

Only then may the strategy-book adaptation and staged promotion begin. Promotion through
`dev -> testing -> main` remains subject to the repository CI and production-evidence gates.
