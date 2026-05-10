---
title: qkt
hide:
  - navigation
  - toc
---

# qkt

> A deterministic, event-driven trading engine with a SQL-like DSL — backtest, paper-trade, or run live on MT5 from the same strategy file.

<div class="grid cards" markdown>

- :material-rocket-launch:{ .lg .middle } **Get started in five minutes**

    ---

    Clone, build, and run your first paper strategy.

    [:octicons-arrow-right-24: Quickstart](get-started/quickstart.md)

- :material-school:{ .lg .middle } **Learn the model**

    ---

    Read why backtest equals live-paper given the same ticks.

    [:octicons-arrow-right-24: Determinism](concepts/determinism.md)

- :material-book-open-page-variant:{ .lg .middle } **Look it up**

    ---

    DSL grammar, CLI commands, config schema.

    [:octicons-arrow-right-24: Reference](reference/index.md)

- :material-cash:{ .lg .middle } **Go live on MT5**

    ---

    Spin up the Docker stack, log in once, deploy.

    [:octicons-arrow-right-24: Deploy MT5](get-started/deploy-mt5.md)

</div>

## What qkt is

A Kotlin trading runtime built on three ideas:

- **Event-driven.** Ticks in, signals out, orders to brokers, trades back. One bus, many subscribers. No hidden coupling.
- **Deterministic.** Same ticks + same strategy = same trades, whether the run is a backtest, a paper deployment, or a live MT5 session. Enforced by a regression test.
- **Strategy-as-text.** Strategies are plain `.qkt` files in a SQL-like DSL. The same file backtests, paper-trades, and runs live.

## What qkt is not

- It is not a broker. qkt connects to brokers (MT5 today; Bybit in tree as composite); it doesn't custody funds or execute on its own venue.
- It is not a backtest framework. The backtester is one consumer of the engine — the same engine that runs live.
- It is not an indicator library. It ships the indicators you need to build strategies, not a comprehensive ta-lib port.

## Project status

qkt is single-developer, fast-moving, and pre-1.0. Every feature lands in a numbered phase. See [Phases](phases/index.md) for the latest changelog. Backlog and known limitations live in [`backlog.md`](backlog.md).

## Where to look

| If you want to... | Go here |
|---|---|
| Run something in 5 minutes | [Quickstart](get-started/quickstart.md) |
| Understand how it works | [Concepts](concepts/index.md) |
| Look up syntax | [Reference](reference/index.md) |
| Deploy to production | [Operations](operations/index.md) |
| Contribute code | [Contributing](contributing/index.md) |
| See what shipped recently | [Phases](phases/index.md) |
