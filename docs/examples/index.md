# Examples

Complete, runnable strategies that showcase what qkt can do. Each example is a real `.qkt` file you can copy, paste, and run.

## How these are organized

Each example is one page with the same shape:

1. **What it does** — the trading idea in plain English
2. **The strategy file** — copy-paste ready
3. **How to run it** — exact commands
4. **What to expect** — the real output of the command above, on a stated window
5. **How to adapt it** — common modifications and gotchas

Every complete strategy on these pages passes `qkt parse`; `ExampleStrategyBlocksTest` checks
that on every build. The results were produced by qkt 0.56.2 from local tick data (`BTCUSD`,
`XAUUSD`, 2026) with the default paper broker and a 10,000 USD account. The paper broker fills at
the mid price with no spread, commission or swap, so every number is optimistic; none of these
strategies is shown because it makes money.

## Single-strategy examples

<div class="grid cards" markdown>

- :material-trending-up:{ .lg .middle } **EMA-crossover trend-follower**

    ---

    The "hello world" of momentum trading. Buy when the fast EMA crosses above the slow EMA, exit on cross-below or bracket.

    [:octicons-arrow-right-24: Trend-follow](trend-follow.md)

- :material-undo-variant:{ .lg .middle } **RSI mean-reversion**

    ---

    Fade extreme oversold readings on RSI(2) on gold. Three entries, one bracket each, exit at three price targets.

    [:octicons-arrow-right-24: Mean-reversion](mean-reversion.md)

- :material-arrow-expand-up:{ .lg .middle } **Donchian breakout**

    ---

    Classic Turtle-style breakout. Long when price closes above the 20-day high, exit at the 10-day low.

    [:octicons-arrow-right-24: Breakout](breakout.md)

- :material-stairs-up:{ .lg .middle } **STACK pyramiding**

    ---

    Layer in three entries as the trade moves in your favor — each layer carries its own copy of the bracket.

    [:octicons-arrow-right-24: Pyramiding](pyramiding.md)

- :material-shield-half-full:{ .lg .middle } **Risk-managed strategy**

    ---

    ATR-sized positions, daily-loss halts, drawdown cap. The risk discipline that turns a backtest into a live deployment.

    [:octicons-arrow-right-24: Risk-managed](risk-managed.md)

</div>

## Multi-strategy examples

<div class="grid cards" markdown>

- :material-folder-multiple:{ .lg .middle } **Regime-gated portfolio**

    ---

    Three strategies (trend, mean-revert, breakout) gated by a regime detector. Only one runs at a time, depending on market conditions.

    [:octicons-arrow-right-24: Portfolio](portfolio.md)

- :material-source-branch-sync:{ .lg .middle } **Cross-broker portfolio**

    ---

    BTC on Bybit and gold on Exness MT5, running side by side in one daemon. Same risk engine, different venues.

    [:octicons-arrow-right-24: Cross-broker](cross-broker.md)

</div>

## End-to-end deployment

<div class="grid cards" markdown>

- :material-package-variant:{ .lg .middle } **Full-stack starter**

    ---

    The whole bundle: `qkt.config.yaml`, `.env`, `docker-compose.yml`, two strategies, deploy walkthrough. Clone, edit credentials, run.

    [:octicons-arrow-right-24: Full-stack bundle](full-stack/index.md)

</div>

## Looking for something simpler?

The [recipes section](../how-to/index.md) has step-by-step walkthroughs for individual tasks (add a stop-loss, run a parameter sweep, debug a strategy that isn't firing). Use recipes when you're learning; use examples when you need a working starting point to adapt.

## Don't trade these with real money yet

Every example here is paper-account-only. qkt is pre-1.0 — see [status & safety on the homepage](../index.md). Use these to learn the engine and validate your own ideas, not as production strategies.
