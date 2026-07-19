# Exit Hooks Design

Issue: #674

## Goal

Allow a `BUY` or `SELL` action to attach one-shot `ON_STOP`, `ON_TP`, and
`ON_CLOSE` actions. The follow-on actions execute through the same signal, book
scaling, risk, and order pipeline as ordinary strategy actions in backtest and
live modes.

## DSL

Hooks follow the parent action's other options:

```qkt
BUY gold SIZING 0.1
  BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 100 }
  ON_STOP {
    SELL gold SIZING 0.1
      BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 100 }
  }
  ON_TP {
    BUY gold SIZING 0.1
      ORDER_TYPE = LIMIT AT EXIT.price - 30
      TIF GTD UNTIL NOW + 2h
  }
```

Each block contains one or more `BUY`, `SELL`, or `LOG` actions. Hook children may use a
bracket but may not declare `ON_FILL`, `ON_STOP`, `ON_TP`, or `ON_CLOSE`.
Nesting is therefore bounded at one level.

The hook-only namespace exposes:

- `EXIT.price`: closing fill price.
- `EXIT.side`: closing fill side (`BUY` or `SELL`).
- `EXIT.qty`: closing fill quantity.
- `EXIT.pnl`: net strategy realized PnL attributed to the closing fill.
- `EXIT.reason`: `STOP`, `TP`, or `CLOSE`.

`EXIT.*` is parsed everywhere so diagnostics remain precise, but compilation
rejects it outside a hook. Numeric accessors participate in arithmetic. String
accessors are available to structured logging.

Hook pending prices also accept `WITH <distance>` and `AGAINST <distance>`.
The direction is resolved against the exit side: `WITH` adds the exit-side
direction and `AGAINST` subtracts it. This reuses `DirRel` rather than creating
a second direction model.

## Compiled Representation

The action compiler assigns each hook-bearing action a stable definition id and
fingerprint. A definition contains independently compiled actions for the three
reasons. Signals emitted by the parent carry only the definition reference; the
compiled strategy retains executable definitions.

The fingerprint covers the resolved hook AST plus its stream and basket
bindings. Persisted live bindings include both id and fingerprint. At restore,
a missing or mismatched compiled definition is a hard error: changed source
must never cause an old live order to run new hook code silently.

## Runtime Ownership

`TradingPipeline` owns an `ExitHookManager`. A binding is registered only after
book scaling and risk approval, immediately before the request is published.
This means rejected or suppressed entries cannot leave orphan hooks.

The binding records:

- strategy, symbol, original side, and compiled definition identity;
- deterministic entry and bracket/stack child order ids;
- filled quantity and broker ticket as entry fills arrive;
- correlated explicit close request ids.

The pipeline's first fill subscriber updates accounting, then supplies the
manager the exact net strategy PnL for that fill and the post-fill strategy
position. The manager classifies and durably removes a matching one-shot
binding. Actual action execution is deferred to a later subscriber, after the
order manager has cancelled old bracket siblings and updated order lifecycle
state, then travels through the strategy's normal `emit` callback. Removing the
binding before actions are emitted prevents synchronous paper fills from
re-entering the same hook.

Deterministic engine bracket ids (`<id>-sl`, `<id>-tp`) are authoritative.
Venue-attached MT5 closes use the deal reason reported by the venue. Explicit
close requests carry `CLOSE` intent. An accounted exposure-reducing fill is the
symbol-keyed fallback for an otherwise uncorrelated external/manual close; it
is never used to guess stop versus TP.

## Partial Fills And One-Shot Semantics

Entry fills accumulate active quantity. Exit fills reduce it. A hook fires when
the binding is fully closed, not on an intermediate partial fill. The exit
context uses the terminal fill's price and side, total closed quantity for that
exit, and accumulated net PnL for the binding's close sequence.

Each binding can fire once. A parent action emitted again creates a new binding,
even if deterministic definition identity is the same.

## Persistence And Recovery

`StatePersistor` stores active hook bindings in `exit-hooks.json` beside other
strategy runtime state. Every registration, fill, partial close, and fire is
write-on-mutate. Fired or cancelled bindings are removed.

Restore occurs after the DSL strategy is compiled and bound. Definitions are
validated by fingerprint before bindings become active. Broker tickets restored
with adopted legs supplement deterministic client-order-id matching so manual
and venue-attached closes can be associated with the correct entry.

## Hot-Path Cost

Strategies without hooks perform one nullable signal-field check when emitting
and one empty active-strategy lookup on broker lifecycle events. Hooked fills
use maps keyed by strategy, client order id, and broker ticket; there is no scan
over historical orders or unrelated strategies. Persistence writes occur only
on order/fill lifecycle mutations, never per tick or bar.

## Compatibility

All new signal, request, and broker-event metadata is optional with defaults.
Existing strategies and persistors therefore retain their behavior. The hook
manager lives in the shared pipeline, preserving backtest/live symmetry.
