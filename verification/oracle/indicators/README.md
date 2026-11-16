# Indicator oracle

Independent reference values for every registered DSL indicator and every numeric function, compared bar by
bar by `src/test/kotlin/com/qkt/verification/IndicatorVerificationTest.kt`. Nothing here imports or runs qkt;
the fixtures are regenerated only by an explicit script and a fixture diff is reviewed like code.

```bash
scripts/verification-fixtures.sh                     # rewrite fixtures/ from the checked-in series/
scripts/verification-fixtures.sh --refresh-series    # also rebuild series/ from the local qkt bar store
```

| Path | What |
| --- | --- |
| `gen.py` | The oracle: TA-Lib where it has the indicator, otherwise numpy/pandas/scipy formulas written from the published definition (or, for qkt's own constructs, from `docs/reference/dsl/indicators.md`). Each formula's docstring states the definition and the qkt convention it encodes. |
| `requirements.txt` | Pinned oracle libraries. Run `gen.py` with `python3 -I` in a venv built from it. |
| `series/*.json` | The input series: three real (XAU 1h, BTC 1m and 1h with volume, source and range recorded in `source`) and seven synthetic edge series (flat, gaps and a x10 jump, a zero price, 1e9 and 1e-6 magnitudes, a single bar, five bars). Bars are `[startMs, open, high, low, close, volume]` as decimal strings. |
| `fixtures/indicators/<NAME>__<args>__<series>.json` | Expected output per bar (`null` = Undefined), the input side fed (`close`, `candle`, `tick`, `pair:close,open`, `bool:close>open`), the tolerance and a note on the convention. |
| `fixtures/functions/<NAME>.json` | Expected results of one FuncRegistry function over an argument grid (`null` = domain error). |

Tolerances: `{"abs": a, "rel": r}` admits `|expected - actual| <= a + r * |expected|`; both zero means the
doubles must be equal. Groups: exact arithmetic 5e-9 absolute (half a unit at qkt's 8 decimals) plus 1e-13
relative (the oracle's own double resolution at 1e9); recursive indicators 1e-9 relative; square-root based
1e-7 absolute; `Double`-backed functions 1e-12 relative.

Where qkt's documented convention differs from TA-Lib the fixture encodes qkt's and says so in `note`: RSI and
Stochastic read 50 and Williams %R -100 on a rangeless window (TA-Lib 0); OBV starts at 0 (TA-Lib at the
first volume); the DI lines share the ADX 2N warmup; MACD seeds each EMA on its own first window (TA-Lib's
`MACD` seeds the fast EMA late, so the oracle composes `EMA(fast) - EMA(slow)` from Appel's definition) and
reports all three lines from the signal's first value; Bollinger uses the population standard deviation and
`stddev()` the sample one; `failed_break_*` reads 0 from the bar that fills its range window.

A fixture that exposes a bug is excluded from `IndicatorVerificationTest`'s factory and listed `@Disabled` with
the reason in a sibling test class; it keeps asserting the correct behaviour, so re-enabling it is the proof once
the fix lands. Every fixture passes at present, so no such class exists.
