# Free futures data: Binance USDⓈ-M quarterlies

Date: 2026-09-30
Question: which free source gives per-contract futures data good enough to develop and test qkt's
futures support (contracts, rolls, expiry), and what does it actually contain?

## Source

- `data.binance.vision` (no key): daily and monthly zip files per contract, e.g.
  `data/futures/um/daily/klines/BTCUSDT_240927/1m/BTCUSDT_240927-1m-2024-09-27.zip`. Kline intervals
  1m to 1d; also `trades`, `aggTrades`, `metrics` (open interest). Top-of-book `bookTicker` files for
  quarterlies stop after March 2024.
- The S3 listing (`s3-ap-northeast-1.amazonaws.com/data.binance.vision?prefix=…&delimiter=/`) lists
  every quarterly that ever had monthly klines.
- `fapi.binance.com/futures/data/delivery-price?pair=BTCUSDT` (no key): delivery prices.

## What a real fetch returned (2026-09-30)

- `qkt fetch BINANCE_UM:BTCUSDT --catalog`: 24 quarterlies, `BTCUSDT_210326` … `BTCUSDT_261225`.
  18 carry a delivery price. Unpriced: the four 2021 contracts (older than the endpoint's history),
  `BTCUSDT_260925` (settled 2026-09-25, not yet published by the endpoint) and `BTCUSDT_261225`
  (not yet expired).
- 1m bars for `BTCUSDT_240927`, `BTCUSDT_241227`, `BTCUSDT_250328` over each contract's last four
  months: every day present (118–119 days each), 27 MB of CSV in total; the day after delivery is
  recorded empty.

## Findings that shape the implementation

1. **Delivery timestamps.** The delivery-price endpoint stamps each delivery at 00:00 UTC of the
   delivery date, while the contract settles at 08:00 UTC. Prices are matched to contracts by UTC
   date (fixed in `BinanceContractCatalog`, pinned by `BinanceContractCatalogTest`).
2. **Bars after delivery.** Binance keeps printing flat, zero-volume klines for about 16 minutes after
   the 08:00 delivery (`BTCUSDT_240927` until 08:16, all at 65426.0). The delivery price itself was
   65422.7, not the last traded price. Anything stamped at or after `expiryMs` must be ignored by the
   continuous series and the exchange simulator, and settlement must use the catalog's delivery price.
3. **Headerless files.** Kline files before 2022 have no header row (handled by `BinanceKlineCsv`).
4. **No free quotes.** Without `bookTicker`, quarterly backtests fill at trade prices; a slippage
   allowance in ticks stands in for the spread.

5. **Listing lead time decides which rolls exist.** Until mid-2023 Binance listed each new quarterly
   only 1–7 days before the previous one delivered (e.g. `BTCUSDT_211231` first traded 2021-09-22,
   two days before `BTCUSDT_210924` delivered), so an 8-day roll had no contract to roll into. Since
   `BTCUSDT_231229` (listed 2023-08-18) two quarterlies always overlap. `qkt fetch BINANCE_UM:BTCUSDT
   --rolls` with an 8-day, 08:00 policy measured 13 front rolls, 2023-09-21 to 2026-09-17, with
   contango gaps of 0.5–5.7% (e.g. 2024-12-19: 102050.9 → 105750.5). The builder keeps the latest
   contiguous run, so continuous BTCUSDT backtests start after the 2023-09-21 roll.
6. **No `@next` history on Binance.** The contract after next lists only at the front's delivery, so
   at a roll instant there is nothing for `@next` to roll into; a `@next` stream on a Binance root fails
   at start with a message saying so.

## Sources

- https://data.binance.vision
- https://www.binance.com/en/support/faq/delivery-and-settlement-of-quarterly-futures-a3401595e1734084959c61491bc0dbe3
