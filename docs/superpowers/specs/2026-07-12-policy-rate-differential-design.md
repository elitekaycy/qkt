# Policy-rate differential feed design

## Scope

Issue #696 requires the RBA cash-rate target, the RBNZ Official Cash Rate, and their
signed difference to be readable by the DSL in backtest and live execution. Positive
`RBA - RBNZ` means AUD-positive carry. Swap accrual is already implemented separately.

## Series

- `MACRO:RBA_CASH_RATE`
- `MACRO:RBNZ_OCR`
- `MACRO:RBA_RBNZ_RATE_DIFF`

The source series come from the central banks' official XLSX statistical tables. The
derived series has one writer: `PolicyRateSeriesFetcher`. Each cache directory includes
deterministic provenance mapping its source URL to the downloaded workbook SHA-256.
The source may also be an operator-managed local copy of the same official artifact.
`QKT_RBA_POLICY_RATE_SOURCE` and `QKT_RBNZ_POLICY_RATE_SOURCE` accept an absolute path,
`file:` URI, or HTTPS URL. This is the supported production boundary when a central-bank
site rejects non-browser server traffic; qkt does not bypass anti-bot controls or silently
substitute an unofficial data vendor.

## Point-in-time semantics

Stored macro rows carry `date,value,availableAtMs`. Legacy two-column FRED rows remain
readable and continue to use `ReleaseSchedule`.

- RBA values become usable at the start of the table's published effective date in
  `Australia/Sydney`. The RBA states that an announced change takes effect the next day.
- RBNZ B2 daily values become usable at 15:00 `Pacific/Auckland` on the following
  New Zealand business day. RBNZ documents a one-business-day publication lag and an
  approximately 15:00 update time. The calendar covers national holidays, transferred
  holidays, Wellington Anniversary Day, and the statutory Matariki dates through 2052;
  dates outside the audited 1999-2052 horizon fail rather than guessing.
- A derived value becomes usable only when both source values used to calculate it are
  usable.

`MACRO:` ticks are read-only published events, not trade prices. The candle hub closes
an event candle immediately at `availableAtMs`; this avoids the one-observation lag of a
normal daily OHLC candle. Signed and zero macro values bypass trade-price validation and
the market-price outlier gate. Strategies still cannot submit orders for macro symbols.

Live polling timestamps a changed value when the running process observes it. It never
backdates a delayed network observation to the authority's earlier publication time.

## Failure behavior

Unknown macro identifiers are not accepted by the live source. HTTP errors, malformed
workbooks, oversized artifacts, missing local artifacts, and a missing recent value fail
loudly. Live operation also rejects an artifact whose latest observation is more than
seven calendar days old. Backtests can use a previously provisioned, provenance-bearing
cache with `--no-fetch`. Local artifact replacement is atomic operator responsibility;
provenance records the exact bytes qkt consumed.

## Verification

Tests cover legacy storage compatibility, availability override, official workbook
shapes, provenance, derived sign changes, live observation timestamps, immediate macro
event closure, signed-value ingestion, and an end-to-end entry/flatten strategy.
