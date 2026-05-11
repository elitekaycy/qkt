# Why qkt vs alternatives

Honest comparison against the trading frameworks people actually pick from. No marketing — if qkt is wrong for your use case, this page tells you which tool to use instead.

## The short version

| | qkt | Lean / QuantConnect | Backtrader | freqtrade | NautilusTrader |
| --- | --- | --- | --- | --- | --- |
| Language | Kotlin | C# / Python | Python | Python | Python (Rust core) |
| Strategy form | `.qkt` DSL file | Python class | Python class | Python class | Python class |
| Determinism | enforced by parity test | partial | best-effort | best-effort | partial |
| Live brokers | MT5, Bybit, TV | IB, Tradier, GDAX, OANDA, Bitfinex, etc. | community plugins | Binance, Kraken, KuCoin, etc. | Binance, IB, Coinbase |
| Backtest fidelity | tick-level, deterministic | tick + bar | bar | bar | tick + bar |
| Multi-strategy daemon | yes (built-in) | yes | no | one at a time | yes |
| Production readiness | **pre-1.0, not for real money** | mature | mature, slowing | mature for crypto | mature |
| License | Apache 2.0 | open core + paid cloud | GPL-3 | GPL-3 | LGPL-3 |
| Cloud-hosted option | no | yes (QC cloud) | no | no | no |

## Pick qkt when

- **You want bit-identical backtest and live execution.** The parity regression test is the differentiator. Lean/Backtrader/freqtrade are "best-effort" — close, but not enforced. qkt fails its build if backtest and live-paper diverge by one tick.
- **You're on the JVM** or want a small (< 30k LOC) engine you can step through with a debugger.
- **You want a SQL-like DSL** instead of Python class boilerplate. `WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21) THEN BUY btc` reads like SQL. The same logic in Backtrader is ~20 lines of `__init__` + `next()`.
- **You target MT5 brokers** (Exness, ICMarkets, FTMO, Pepperstone, prop-firm accounts). qkt has built-in profiles and a tested Docker stack.
- **You value readability over feature breadth.** qkt deliberately ships ~10 indicators instead of 200 — the catalog is the most common ones, written from scratch, with hand-computed tests.

## Pick Lean / QuantConnect when

- You want **managed cloud execution.** QC runs your strategy on their servers; you don't worry about uptime or VPS.
- You want **broad broker support.** IB, Tradier, GDAX, OANDA, Bitfinex, Binance — Lean has them all.
- You want **research notebooks integrated** with backtests. QC's Jupyter integration is mature.
- You're building a **paid product** and want commercial support contracts.

The flip side: Lean is large (>200k LOC C#), slow to learn, and the on-prem deployment story is painful.

## Pick Backtrader when

- You want **maximum learning resources.** Backtrader has 10 years of Stack Overflow answers and forum posts.
- You're a **discretionary trader prototyping ideas** quickly. The Python class form is fast to iterate.
- You're **backtesting only** and don't care about live trading. Backtrader's live brokers are community plugins of varying quality.

The flip side: Backtrader's maintainer left in 2023; development is community-driven and slow. Live trading is the rough part.

## Pick freqtrade when

- You're **trading crypto on Binance/Kraken/KuCoin** and want a production-grade bot.
- You want **a UI dashboard for non-developers.** freqtrade-UI is solid.
- You want **hyperopt** (Bayesian optimization over strategy parameters) built-in.

The flip side: crypto-only. No FX, no equities, no futures.

## Pick NautilusTrader when

- You need **microsecond-latency** event processing — NautilusTrader's Rust core is genuinely fast.
- You're building **market-making or HFT-adjacent** strategies that need full order book and queue position modeling.
- You're comfortable with **complex installation** (Cython + Rust toolchain).

The flip side: steep learning curve. Overkill for most directional strategies.

## Where qkt loses

To be straight about the weaknesses:

- **No order-book backtesting.** qkt fills against the trade price; order-book-aware backtesting (queue position, level-2 depth) isn't modeled. NautilusTrader does this; qkt doesn't.
- **Small indicator library.** ~10 indicators. If you need MACD-style stochastic-RSI or Hilbert Transform Sine Wave, you'll be implementing it yourself.
- **No commercial support.** Single maintainer. Issues get answered when they get answered.
- **JVM startup time.** ~3 seconds before the first tick. Fine for live trading, annoying for one-off CLI commands.
- **Pre-1.0.** Breaking changes happen between minor versions. The DSL grammar has stabilized but engine internals haven't.
- **Limited live broker coverage.** MT5 + Bybit only. No IB, no Alpaca, no OANDA. Adding a new broker is straightforward (~500 LOC) but it's not done.
- **No cloud-hosted option.** You run it yourself, on your hardware or your VPS.

## Where the comparison gets fuzzy

- **"Production-ready" is a spectrum.** Lean has been used at scale by quant funds. Backtrader is mature but slowing. qkt is single-developer and pre-1.0 — it's not yet at "production" for real-money use, but the codebase is small and auditable.
- **"Determinism" is qkt's strongest claim.** Other frameworks try to be deterministic, but most have at least one non-deterministic surface (timestamps, ID generation, random seeds) somewhere. qkt enforces it via interfaces + tests.
- **"Speed" depends on what you mean.** For event throughput at single-strategy scale, all of these are fast enough. For HFT, NautilusTrader is the only serious choice in this list.

## Bottom line

- **Want to ship a strategy fast on crypto?** freqtrade.
- **Want managed cloud + broad asset support?** QuantConnect.
- **Want HFT-adjacent latency?** NautilusTrader.
- **Want a small, deterministic, JVM-native engine with a SQL-like DSL and MT5 broker support?** qkt.
- **Backtesting research only with a community to ask?** Backtrader.

qkt is a good fit for one specific use case: **a developer building systematic strategies on MT5 brokers who values determinism and readability over feature breadth.** If that's you, the rest of the docs will feel native. If it's not, one of the alternatives above will save you time.

## See also

- [Get started: Quickstart](../get-started/quickstart.md) — 5 minutes from clone to first trade
- [Determinism](determinism.md) — the parity contract in detail
- [Architecture](architecture.md) — the full data flow
