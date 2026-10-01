# Free options data for qkt backtests — findings (2026-10-01)

Probed while planning phase 43 (options). All requests were anonymous.

## 1. Tardis `options_chain` CSVs are not reachable by a program

`https://datasets.tardis.dev/v1/deribit/options_chain/YYYY/MM/DD/OPTIONS.csv.gz` (the spec's planned
source, first day of each month free) answers `403` with a Cloudflare browser challenge to `curl`. It
cannot back an automated `qkt fetch`. A full day is also many gigabytes. **Replaced as the free source.**

## 2. Deribit's own public API serves options history without a key

- `https://history.deribit.com/api/v2/public/get_last_trades_by_currency_and_time?currency=USDC&kind=option&start_timestamp=…&end_timestamp=…&count=…&sorting=asc`
  returns every option trade of the window, including expired instruments, each with `price`,
  `mark_price`, `iv` (percent), `index_price` (the underlying index at the trade), `amount`,
  `contracts`, `direction`, `instrument_name`, `timestamp` (ms), and `has_more` for paging.
- `…/public/get_instruments?currency=USDC&kind=option&expired=true` (history host) lists expired
  contracts with `strike`, `option_type`, `expiration_timestamp`, `contract_size`, `tick_size`,
  `min_trade_amount`, `settlement_currency`, `taker_commission`.
- `https://www.deribit.com/api/v2/public/get_delivery_prices?index_name=btc_usd` gives one delivery
  (settlement) price per day, 2624 records back.

## 3. Use the linear USDC options, not the inverse BTC/ETH ones

Deribit's `BTC-…` options are inverse (priced and settled in BTC) — refused by the spec (E18). The
`<COIN>_USDC-…` options are linear: `instrument_type: linear`, quoted and settled in USDC. On
2026-10-01: SOL 662, BTC 614, ETH 526, XRP 422, HYPE 420, AVAX 384, TRX 264 live contracts.
`BTC_USDC-1OCT26-74000-C`: contract size 1, tick 5 USDC. Strikes with a decimal point are written with
`d` (`AVAX_USDC-1OCT26-9d5-C` is strike 9.5).

Caution: one expired listing (`SOL_USDC-13FEB24-96-C`) reports `quote_currency: SOL` though it
settles in USDC — the importer must take settlement/quote currency per instrument and refuse
anything not linear-in-USDC rather than assume.

## 4. What this means for the design

- Chain snapshots are built from trades: per instrument, the last trade's `mark_price`, `iv` and
  `index_price` at or before each snapshot boundary (spec §6.3's "last quote at or before t", with
  trades standing in for quotes). Bid/ask are not in the trade history; fills must therefore use a
  declared spread model around the mark (a documented divergence), never the mark itself.
- Expiry settlement uses `get_delivery_prices` for the underlying index (`<coin>_usdc` index names
  to be confirmed per coin).
