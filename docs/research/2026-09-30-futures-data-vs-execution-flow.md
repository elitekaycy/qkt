# Futures data vs execution flow

Date: 2026-09-30

## Thesis
Futures differ from CFDs in exactly three ways that matter to qkt: finite expiry with rollover, exchange-standardized contract specs (multiplier/tick/margin), and exchange order-book price formation vs broker market-maker quotes. qkt's MarketSource/Broker split already isolates these — new venue = new prefix route + translator + calendar, engine untouched.

## Flow finding
- Data path: Venue -> MarketSource.supports/liveTicks/bars/ticks -> TickFeed -> TradingPipeline.ingest -> candleHub/engine/strategy. Broker never feeds prices; it only reads via MarketPriceProvider.
- Execution path: Strategy -> OrderRequest -> risk -> OrderManager -> Broker.submit -> OrderTranslator -> venue wire -> BrokerEvent back on bus.
- Adaptability comes from: prefix routing (CompositeMarketSource / CompositeBroker), shared interfaces, MarketPriceTracker as decoupled read model, InstrumentRegistry for contractSize-aware sizing.

## Futures concrete rules
- Symbology: root + month code H/F/G/J/K/M/N/Q/U/V/X/Z + year digit, e.g. ESZ25, NQU26. CME Globex codes: ES, NQ, MES, MNQ, YM, RTY.
- Specs: ES $50 x index, tick 0.25 = $12.50; NQ $20 x index, tick 0.25 = $5.00; MES $5 x, $1.25; MNQ $2 x, $0.50. Margins ~ ES $12-27k, NQ $17-42k (exchange SPAN, varies by volatility).
- Expiry: quarterly H/M/U/Z, last trading day third Friday, cash-settled for equity index. Rollover ~1 week before expiry when next-contract volume overtakes front (ESU->ESZ Sep 12 2024, ESZ->ESH Dec 13 2024).
- Continuous contracts: raw (exact front-month quotes, gaps at roll) vs back-adjusted (shift full history by settlement-difference premium, e.g. +70.25 on Dec 13 2024). Back-adjusted for backtest signals, raw for PnL.
- Costs: no overnight swap; holding cost = rollover spread. Commission per contract per side. Daily mark-to-market margin.
- Data: CME DataMine (FIX/RLC depth, Security Definition tag 35=d carries expiry/strike), Databento/Rithmic for live, CME Reference Data API for first/last trade dates. Dukascopy fetcher in-repo covers FX/CFD only — futures need new fetcher.
- Sessions: Sun 6pm-Fri 5pm ET, daily halt 4:15-4:30pm CT, price limits 7/13/20% down.

## Caveats
- qkt InstrumentMeta today has contractSize/volume/point/digits but no multiplier/expiry/firstLastTradeDate — must extend for futures.
- MT5 gateway can serve futures CFDs as new symbols with same plumbing; native CME needs new Broker + MarketSource pair.
- Backtest needs continuous-contract builder; live needs expiry-gate (reject within N days) + rollover worker.

## Sources

- https://www.linnsoft.com/support/continuous-futures-contracts
- https://www.cmegroup.com/market-data/browse-data/catalog/futures-and-options-data.html
- https://www.cmegroup.com/education/courses/introduction-to-futures/understanding-contract-trading-codes
- https://www.barchart.com/futures/contract-specifications/indices
- https://www.gate.com/learn/articles/cfd-vs-futures
