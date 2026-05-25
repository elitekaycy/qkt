# Recipes

Task-oriented walkthroughs. Each page is one problem and one copy-paste-able solution.

<div class="grid cards" markdown>

- :material-chart-line:{ .lg .middle } **EMA crossover strategy**

    ---

    The "hello world" of trend-following — fast EMA crosses slow EMA, with a bracket.

    [:octicons-arrow-right-24: Build it](ema-crossover.md)

- :material-shield-half-full:{ .lg .middle } **Add a stop-loss**

    ---

    Every flavor of stop: fixed-price, ATR-based, trailing. When to use which.

    [:octicons-arrow-right-24: Add a stop-loss](add-stop-loss.md)

- :material-grid:{ .lg .middle } **Run a parameter sweep**

    ---

    Grid-search over EMA periods, plot the heatmap, pick the winner.

    [:octicons-arrow-right-24: Sweep parameters](parameter-sweep.md)

- :material-bug:{ .lg .middle } **Debug a strategy that isn't firing**

    ---

    Six things that go wrong, in order of how often they happen, with fixes.

    [:octicons-arrow-right-24: Debug it](debug-not-firing.md)

- :material-cash:{ .lg .middle } **Deploy live on Exness (MT5)**

    ---

    Docker stack, VNC login, profile config, first paper trade — end-to-end.

    [:octicons-arrow-right-24: Deploy live](deploy-exness.md)

- :material-code-tags:{ .lg .middle } **Install editor integrations**

    ---

    `qkt editor install vscode|nvim|vim|sublime` — one command, no manual copies.

    [:octicons-arrow-right-24: Install for your editor](editor-integrations.md)

</div>

## Need something else?

These recipes cover the highest-frequency questions. For more depth:

- [DSL grammar](../reference/dsl-grammar.md) — every keyword and operator
- [Architecture](../concepts/architecture.md) — what happens when a rule fires
- [Backtest model](../concepts/backtest-model.md) — what the engine guarantees and doesn't
- [Phases](../phases/index.md) — every shipped feature has a worked-example cookbook

Missing a recipe you'd find useful? [Open an issue](https://github.com/elitekaycy/qkt/issues/new) — that's how the next batch gets prioritised.
