# DSL capability-gap batch — implementation plan

Spec: `docs/superpowers/specs/2026-06-21-dsl-capability-gaps-design.md`
Branch: `feat-dsl-capability-gaps` (off `dev`)

Each task is TDD: failing test first, minimal impl, green, commit. One commit per task
(`<type>(dsl): …`, subject only).

## Task 1 — scalar grid math (#501, #505 modulo)
- Extend `FuncRegistryTest`: `MOD` (floored, sign-of-divisor), `FLOOR`, `CEIL`,
  `ROUND` (half-up), arity errors.
- Add the four `FuncSpec` entries to `FuncRegistry` in exact `BigDecimal`.
- Extend `RegistryNamesTest` function assertion.
- Commit: `feat(dsl): add MOD/FLOOR/ROUND/CEIL scalar functions`.

## Task 2 — PERCENTILE_RANK (#504)
- `PercentileRankTest` (catalog): fraction-below over a window, ties, warmup, bounds.
- `indicators/catalog/PercentileRank.kt` — `Indicator<BigDecimal>`, rolling window.
- Register `PERCENTILE_RANK` (NUMERIC_SERIES, arity 2). Extend `RegistryNamesTest`.
- Commit: `feat(dsl): add percentile_rank rolling indicator`.

## Task 3 — session VWAP + stdev (#503, #506)
- `VwapSessionTest` / `VwapSessionStdevTest`: anchor reset across day boundary,
  volume weighting, typical price, warmup, requiresVolume skip.
- `indicators/catalog/VwapSession.kt` (shared accumulator + two `Indicator<Candle>`).
- Register `VWAP_SESSION` / `VWAP_SESSION_STDEV` (CANDLE_SERIES, arity 2,
  requiresVolume). Extend `RegistryNamesTest` / `CandleIndicatorRegistryTest`.
- Commit: `feat(dsl): add session-anchored VWAP and stdev bands`.

## Task 4 — session range latch (#508)
- `SessionRangeTest`: latch prior completed window high/low, hold across the later
  window, wrap-midnight, reset next instance, warmup null.
- `indicators/catalog/SessionRange.kt` (shared accumulator + high/low `Indicator<Candle>`).
- Register `SESSION_RANGE_HIGH` / `SESSION_RANGE_LOW` (CANDLE_SERIES, arity 5).
- Commit: `feat(dsl): add session-anchored range high/low`.

## Task 5 — CONFIRM_RATIO (#479)
- `ConfirmRatioTest` (catalog): fraction same-signed return over lookback, polarity via
  negated peer, warmup, all-confirm / none-confirm.
- `indicators/catalog/ConfirmRatio.kt` — `MultiIndicator` (signal first, then peers).
- `ExprCompiler`: special-case `CONFIRM_RATIO` like `compileResidual` → `bindMulti`.
- Compile/eval test for `confirm_ratio(eur.close, gbp.close, -usdchf.close, 4)`.
- Commit: `feat(dsl): add confirm_ratio cross-symbol aggregate`.

## Task 6 — closures proof (#507, #502, #505 wick)
- `MinuteOfHourGateTest`, `RangeContractionTest`, `RejectionWickTest`: parse +
  `AstCompiler().compile` the candidate strategies, assert success.
- Commit: `test(dsl): prove minute-of-hour, NR7/inside-bar, wick expressible`.

## Task 7 — docs + LSP + changelog
- `docs/reference/dsl/indicators.md`, `functions.md` entries with worked examples.
- `lsp/QktVocabulary.kt` + `QktDocs.kt` for the new names.
- Phase changelog under `docs/phases/`.
- KDoc on every new public type.
- Commit: `docs(dsl): document grid math, percentile_rank, session VWAP/range, confirm_ratio`.

## Task 8 — ship
- `./gradlew ktlintFormat` then `./gradlew build` green.
- Push; PR to `dev` (`Closes #479 #501 #503 #504 #506 #508`; `Refs` the closures).
- CI green; merge `--no-ff`.

## Task 9 — close stale issues
- Close #507, #502 with proof comments; #505 closed by the build (modulo + wick proof).

## Task 10 — forge + loop
- Update `qkt-forge` `authoring.py` grammar/examples on bot2; parse-validate; rerun loop.
