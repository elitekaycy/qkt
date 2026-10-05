# Option mark IV and Greeks as rule inputs — design

**Status:** design for #1299, part of #1295, on the capability foundation of
`2026-10-04-perpetual-funding-and-venue-capabilities.md` and the pattern of
`2026-10-04-mark-and-index-stream-fields.md`. Amends `2026-10-01-vgp-v1-wire.md` (additive; the gateway's
copy follows it).

## 1. Problem

Chain analytics expose `atm_iv` and `skew_25d`, and a held structure exposes `POSITION.ps.delta`,
`.gamma`, `.vega` and `.theta`, but a rule cannot read one contract's mark IV or delta before holding it:
"buy the call while its IV is under 40" or "sell it once its delta passes 0.6" cannot be written.

## 2. What the venue offers (measured 2026-10-05, Deribit testnet and mainnet)

- `public/ticker` (and the `ticker.<name>` channel the gateway streams) carries `mark_iv`,
  `underlying_price` (the expiry's forward) and `greeks` (`delta`, `gamma`, `vega`, `theta`, `rho`).
- `public/get_book_summary_by_currency` (what `qkt fetch --chains --live` reads) carries `mark_iv` and
  `underlying_price` but **no Greeks**.
- No history of mark IV or Greeks exists: `public/get_mark_price_history` answers `[]` for a linear
  (`_USDC`) option on testnet and mainnet, and serves only `[time, mark]` pairs of inverse options; a public
  trade carries the trade's own `iv`, `mark_price` and `index_price`, not the mark IV or Greeks.
- Deribit's Greeks are Black-76 on the mark IV, the `underlying_price` forward, rate 0 and a 365-day year
  (vega per volatility point, theta per day), to within 0.05%, published to 5 decimals. One recorded ticker
  (`BTC_USDC-30OCT26-110000-C`, mark IV 45.46, forward 86575.5; the gateway's
  `fixtures/ticker-option-greeks.json`): Deribit delta 0.02616, vega 13.84089, theta −12.42725, gamma
  0.00001; Black-76 from the same inputs 0.026169, 13.8450, −12.4317, 0.0000059. The rounding leaves a BTC
  option's gamma one significant digit (here 70% off).

## 3. Decision: qkt prices the Greeks from the mark IV, live and in backtests

The issue proposed the venue's Greeks where published, else qkt's Black-76. Measured, the venue's Greeks
add nothing over Black-76 on the mark IV but rounding (ruinous for BTC gamma), and no stored series can
hold them (§2: the book summary and the trade tape have none), so a backtest could never replay what live
read. qkt therefore computes the Greeks itself, everywhere, with the formula held structures already use
(`StructureGreeks`): the venue's Greeks are not carried on the wire. The mark IV and the forward are the
inputs, and they are in every stored chain series.

## 4. The rule (as for funding and marks)

1. **Declared:** a gateway whose option quotes carry `mark_iv` and `underlying` declares `option_marks`.
   There is no endpoint to refuse (the data rides on `/v1/quotes`, as `quotes` itself); the contract kit
   checks a declaring adapter quotes an option with both.
2. **Refused, never Undefined for ever:** a strategy reading `<alias>.iv`, `.delta`, `.gamma`, `.vega` or
   `.theta` does not start when the alias is not a catalogued option contract, or when its feed serves no
   option marks (a gateway not declaring `option_marks`; MT5; Bybit). A backtest needs the root's declared
   chain series for every day the contract is read (`OptionChainCoverage`, unchanged), naming
   `qkt fetch <ROOT> --chains`.
3. **One shape:** the chain quote (`mark`, `markIv`, `underlying`, …) whatever the venue.
4. **Replayable:** the chain series (`chains/<V>/<R>/book|trade/<day>.csv.gz`) a backtest reads is what live
   recorded (`ChainRecorder`) or `qkt fetch --chains` stored; each value is visible from its snapshot on.

## 5. Wire (VGP v1, additive)

Capability `option_marks`: the gateway's option quotes carry `mark_iv` (volatility points) and `underlying`
(the forward the venue values the option against), the fields §4a already defines. No new endpoint.

## 6. Gateway

- `adapter-api`: `Capability.OPTION_MARKS`.
- `adapter-testkit`: when declared, the contract test's `optionCode` (a listed option quoted now) is
  subscribed and must be quoted with a positive mark IV and forward. The test names it: Deribit lists
  `inactive` options whose ticker never pushes (testnet `AVAX_USDC-6OCT26-12-C`, measured 2026-10-05), so the
  kit cannot pick one from the listing.
- `deribit` declares it (its ticker carries both); the README records §2. `paper` declares it (it streams
  Deribit's tickers).

## 7. qkt

- DSL: `<alias>.iv`, `.delta`, `.gamma`, `.vega`, `.theta` on an option contract stream
  (`DslVocabulary.optionFields`). `OptionFieldCompiler` reads `MarketSource.optionMarksFor(symbol)` at
  evaluation time: the newest usable quote (positive mark IV, unexpired, its mark at most the root's
  `maxQuoteAgeMinutes` old). `iv` is its mark IV in volatility points; the Greeks are Black-76 on it, its own
  forward, rate 0, time to expiry from the clock, per contract (× contract size), vega per volatility
  point and theta per day, as `POSITION.<structure>` reports them. Undefined until a usable quote is known.
- Bind: `requireOptionMarks` refuses a strategy whose option-field symbols are not catalogued options or
  whose source reports a problem.
- Live: `GatewayMarketSource` keeps each code's newest quote that carried a mark IV (by reference: no
  allocation per quote), served when `/v1/health` declares `option_marks`.
- Backtest: `OptionChainMarketSource` serves the root's declared chain series through `ChainView`: the
  contract's quote in the newest snapshot at or before the instant (as chain analytics and structures).
- Parity row A62 (live reads the newest quote, a backtest the newest snapshot; a trade-built series
  has the trade's IV and index, not the mark IV and forward).

## 8. Out of scope

The venue's own Greeks (§3), rho, lookback (`c.iv[1]`), option fields on a root feed or a structure leg
not declared as a stream, and venues other than the gateway's.
