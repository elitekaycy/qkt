# What can be traded algorithmically — a survey of instrument classes

Date: 2026-10-04

## Scope
Algorithmic trading systems already trade nearly every regulated macro asset class: equities, ETF shares, index products, FX spot, listed futures/options/CFDs, crypto (spot, perps, options), commodity futures, rates/bond futures, and volatility products. The two archetypes are exchange-listed derivatives (futures/options) and broker-hosted CFDs / FX spot — both equitable up to specifics of venue truth, cost model, and settlement mechanics.

## Predominant engine categories
- Equities and ETFs: momentum and reversion signals, sector risk, earnings event strategies, long/short portfolios, dividend capture.
- Futures on indices/commodities/rates: calendar spreads, roll capture, seasonal, carry.
- Options: straddle/strangle income, covered calls, vertical spreads, delta-hedged gamma scalps.
- FX spot / CFDs: carry, mean-reversion, carry-trade, cross-asset risk triggers.
- Crypto: 24/7 perp breakouts, funding-rate harvesting, basis/futures-perpetual, options smile arbitrage.

## Why they exist for algorithms
1. Every class has reference venues that publish fair canonical streams (trades/bars, depth, chain/settlement); there is no algorithmic system that gets identical venue truth without bid ask/Ausführung gaps.
2. Each market has a unique cost layer: futures clearing fees, CFD spread+swap, crypto maker/taker, options bid/ask spreads, margin schedules.
3. Settlement behavior differs enough to avoid modeling errors; futures expire, CFDs roll, options settle at delivery/expiry, perps roll perpativamente via funding.
4. Infrastructure: qkt + mt5-gateway for broker CFDs, qkt-venue-gateway for VGP adapters (paper, Deribit), local strategy engine.

## Limitations for qkt today
- Live MT5 is broker-hosted CFD; crypto spot/perps/options work through venue adapters.
- Alt assets (real estate, loans, prediction markets) lack the same VGP adapter coverage and cannot share one contract as cleanly as public venues do.

## Takeaway
For the engine architecture, the right taxonomy is by *venue truth + instrument class*, not by asset name. CFDs live inside `mt5-gateway` brokers, futures/options/perps live inside `qkt-venue-gateway` adapters. Everything else belongs to a venue contract exposing the same series shape (listed instruments, bars, depth, chains, funding, settlements, etc.).

## Sources

- https://aip.vse.cz/pdfs/aip/2025/03/13.pdf
