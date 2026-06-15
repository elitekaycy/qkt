# BASKET (synthetic instrument)

A **basket** is several real streams bound together into one synthetic, tradeable
pseudo-stream. You read its composite price like any stream (`antipodean.close`), and you
trade it with one verb (`BUY antipodean`) that fans out to a real order on each constituent.
It exists so a strategy can express a market-neutral pair like "short gold versus the
antipodean basket" as a single readable construct, and backtest it with the same
bit-identical determinism as everything else.

Baskets are declared in the `SYMBOLS` block, alongside the [streams](streams.md) they are
built from.

## Shape

```qkt
SYMBOLS
    aud        = EXNESS:AUDUSD EVERY 1h
    nzd        = EXNESS:NZDUSD EVERY 1h
    antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
```

After `=`:

1. **`BASKET`** — the keyword that marks a synthetic stream.
2. **`EQUAL_WEIGHT`** — the weighting. `EQUAL_WEIGHT` is the only weighting in v1 (every
   constituent contributes equally to the composite). Beta-weighting is a future addition.
3. **`[a, b, ...]`** — the constituents, in square brackets. Each must be an alias of a
   real stream declared earlier in `SYMBOLS`. At least two are required.
4. **`EVERY <timeframe>`** — the basket's bar interval. It must match every constituent's
   timeframe, so their bars share a window.

The alias (`antipodean`) is a first-class stream reference everywhere else in the file:
`antipodean.close` reads its composite price, and `BUY`/`SELL`/`CLOSE antipodean` trade it.

## The composite price — an equal-weight log-return index

A basket has no single market price, so qkt synthesizes one: an **equal-weight log-return
index** that starts at `100` and compounds the average log return of its constituents each
window.

```
R(t) = (1 / N) * Σ ln( price_i(t) / price_i(t-1) )      # average log return across N constituents
I(t) = I(t-1) * exp( R(t) )                             # the index, starting at I = 100
```

Why log returns and base 100, rather than a price average? Averaging raw prices is
meaningless across instruments at different price levels (one lot of AUDUSD and one of a
$2,000 metal are nothing alike). A log-return index is **scale-invariant**: it measures how
the basket *moves*, not what it costs, so a ratio z-score built on it (`gold.close /
antipodean.close`) has stable thresholds.

The index needs two aligned windows before it has a return to compound, so a basket's
`close` is **Undefined until its second aligned bar** (the first window only sets the
baseline). Treat that warmup like any indicator's.

**Worked example.** Two constituents. Window 1 both rise +1%, window 2 both fall ~1%:

```
start:    I = 100
window 1: each +1%  → R = ln(1.01) → I = 100 * 1.01   = 101
window 2: each -1%  → R = ln(0.99) → I = 101 * 0.99   ≈ 100
```

The synthesized basket candle carries `open = I(t-1)`, `close = I(t)`, `high`/`low` = the
higher/lower of the two, and **`volume = 0`**. Because the composite has no real volume, a
volume-based indicator (`vwap`, `obv`) pointed at a basket is rejected at compile time.

## Trading a basket — order fan-out

`BUY`/`SELL` on a basket emits **one plain-market order per constituent**, each on the
constituent's own symbol, all on the same side:

```qkt
WHEN zscore(gold.close / antipodean.close, 100) >= 2.0 AND POSITION.gold = 0
THEN SELL gold ; BUY antipodean SIZING 10000 USD
```

`BUY antipodean SIZING 10000 USD` becomes a BUY of AUDUSD and a BUY of NZDUSD.

**Sizing is equal notional.** "Equal weight" means equal *economic* exposure, not equal
lots — one lot of two differently-priced symbols is two different bets. So a basket order
requires notional sizing (`SIZING <amount> USD`), and each constituent is sized so its
notional is the total split `N` ways:

```
qty_i = (notional / N) / (price_i * contractSize_i)
```

Any other sizing form on a basket order (fixed lots, percent-of-equity, risk-based) is a
compile error — there is no single price to turn a lot count or risk distance into an
even split.

**Basket orders are plain market in v1.** A `BRACKET`, `OCO`, `TIF`, or `STACK` on a basket
order is a compile error. Exits are rule-driven instead (see `CLOSE` below and the example).

!!! warning "DEFAULTS that set TIF or BRACKET conflict with basket orders"
    `DEFAULTS` apply to every action, including basket orders — and a basket order cannot
    carry a TIF or bracket. If a strategy mixes a `DEFAULTS { TIF = ... }` or a default
    stop/take-profit with a basket `BUY`/`SELL`, the basket order is rejected at compile.
    Keep basket-trading strategies to `DEFAULTS { SIZING = ... }`, and give each basket
    order an explicit `SIZING <amount> USD`.

## Position and close

`POSITION.<basket>` reports **direction only**, unit-normalized: `+1` when every constituent
holds a net-long position, `-1` when every constituent is net-short, and `0` when the basket
is flat or its constituents disagree on side. (Summing quantities across different symbols
would be meaningless; the useful idiom is the flat-gate `POSITION.antipodean = 0`, exactly as
`POSITION.gold = 0` works for one symbol.)

```qkt
WHEN POSITION.antipodean = 0 AND <entry signal>
THEN BUY antipodean SIZING 10000 USD
```

`CLOSE <basket>` fans out too: it flattens every constituent (one basket close → N
constituent closes), each closed the same way a direct `CLOSE` on that symbol would be.

```qkt
WHEN POSITION.gold != 0 AND abs(zscore(gold.close / antipodean.close, 100)) <= 0.5
THEN CLOSE gold ; CLOSE antipodean
```

!!! note "v1 reads constituent net positions"
    `POSITION.<basket>` reads each constituent's *net* position, so a constituent that is
    traded both standalone **and** through a basket is not isolated from the basket view.
    For the market-neutral pair use case (constituents traded only via the basket) this is
    exact. Per-basket leg isolation is a future addition.

## Aligning a basket with another stream

A cross-stream condition like `gold.close / antipodean.close` only makes sense when both
sides are from the same window. Put the real stream and the basket in a `SYNCHRONIZE` group
so they evaluate together:

```qkt
SYMBOLS
    gold       = BACKTEST:XAUUSD EVERY 1h
    aud        = BACKTEST:AUDUSD EVERY 1h
    nzd        = BACKTEST:NZDUSD EVERY 1h
    antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
    SYNCHRONIZE gold antipodean
```

The rule then fires on the synchronized window with `gold.close` and `antipodean.close` both
current. See [z-score](indicators.md#zscore) for the ratio-spread pattern this enables.

## Validation

Every check below fires at compile time, with a message naming the offending alias:

| Error | When |
|---|---|
| Fewer than two constituents | `BASKET EQUAL_WEIGHT [aud]` — a one-symbol basket is just that symbol. |
| Unbound constituent | a constituent alias is not a declared real stream. |
| Basket of baskets | a constituent is itself a basket (unsupported in v1). |
| Timeframe mismatch | the basket's `EVERY` differs from a constituent's timeframe. |
| Volume indicator on a basket | `vwap`/`obv` on a basket — the composite has zero volume. |
| Non-notional sizing | a basket order sized by lots / percent / risk instead of `USD`. |
| Bracket / OCO / TIF / stack on a basket order | basket orders are plain market in v1. |

## Determinism

A basket is a pure function of its constituents' closed bars, computed the same way in
backtest and live. Its composite series, its z-scores, its fan-out quantities (given the
same prices and instrument metadata), and its closes are identical across modes — the
engine's backtest=live invariant holds with no special-casing.

## Worked example

`examples/basket-spread/basket-spread.qkt` is the full motivating strategy: a market-neutral
XAU-versus-antipodean spread that trades the ratio z-score, with rule-driven revert, hard
z-score stop, and time-stop exits.

## Cross-asset baskets

Constituents may come from different brokers (`SP500:AAPL` and `SP500:MSFT`, or a metals
basket across venues) — the fan-out routes each by its own symbol. They must still share a
timeframe, and a constituent that stops printing stalls the basket (the same wait-for-all
behavior as `SYNCHRONIZE`).

## Out of scope (v1)

Beta-weighted / risk-parity weighting, equal-lot (rather than equal-notional) fan-out, and
per-leg brackets on a fanned-out order are deferred. See the design spec,
`docs/superpowers/specs/2026-06-14-basket-zscore-design.md`, for the rationale.
