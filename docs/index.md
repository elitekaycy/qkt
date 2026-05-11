---
title: qkt
hide:
  - navigation
  - toc
---

<section class="qkt-hero qkt-hero--split" markdown="0">
  <div class="qkt-hero__left">
    <div class="qkt-hero__meta">
      <span class="pill pill--accent">qkt v0.24</span>
      <span class="pill">free &amp; open source</span>
      <span class="pill pill--warn">pre-1.0 — paper accounts only</span>
    </div>

    <h1 class="qkt-hero__lede">From idea to live trade — in <em>one file.</em></h1>

    <p class="qkt-hero__sub">
      qkt is a free, open-source trading engine. Write your strategy in one small file, then test it against years of real market data, run it on fake money to make sure it behaves, and connect it to your broker when you're ready. We work with <strong>Bybit</strong>, <strong>MT5 brokers</strong> (Exness, ICMarkets, FTMO, Pepperstone), and <strong>TradingView feeds</strong> today — more brokers (Alpaca, IBKR) on the way.
    </p>

    <div class="qkt-hero__ctas">
      <a class="qkt-btn qkt-btn--primary" href="get-started/quickstart/">
        <span>Run your first strategy in 5 min</span>
        <span class="qkt-btn__arrow">→</span>
      </a>
      <a class="qkt-btn" href="how-to/ema-crossover/">See a worked example</a>
      <a class="qkt-btn" href="concepts/broker-integration/">Which brokers work?</a>
    </div>
  </div>

  <aside class="qkt-hero__right" aria-label="example strategy">
    <div class="qkt-codeframe">
      <header class="qkt-codeframe__head">
        <span class="qkt-codeframe__file">strategies/momentum.qkt</span>
        <span class="qkt-codeframe__lang">qkt</span>
      </header>
      <pre class="qkt-codeframe__body"><code><span class="kn">STRATEGY</span> <span class="n">momentum</span> <span class="kn">VERSION</span> <span class="mi">1</span>

<span class="kn">SYMBOLS</span>
    <span class="n">btc</span> <span class="o">=</span> <span class="nc">BACKTEST</span><span class="p">:</span><span class="no">BTCUSDT</span> <span class="kp">EVERY</span> <span class="mi">1m</span>

<span class="kn">RULES</span>
    <span class="k">WHEN</span> <span class="nf">ema</span><span class="p">(</span><span class="nv">btc</span><span class="p">.</span><span class="na">close</span><span class="p">,</span> <span class="mi">9</span><span class="p">)</span>
         <span class="ow">CROSSES</span> <span class="ow">ABOVE</span>
         <span class="nf">ema</span><span class="p">(</span><span class="nv">btc</span><span class="p">.</span><span class="na">close</span><span class="p">,</span> <span class="mi">21</span><span class="p">)</span>
    <span class="k">THEN</span> <span class="kr">BUY</span> <span class="n">btc</span> <span class="kp">SIZING</span> <span class="mf">0.1</span>
         <span class="kp">BRACKET</span> <span class="p">{</span>
           <span class="kp">STOP_LOSS</span> <span class="kp">BY</span> <span class="mi">50</span> <span class="kp">PCT</span><span class="p">,</span>
           <span class="kp">TAKE_PROFIT</span> <span class="kp">BY</span> <span class="mi">100</span> <span class="kp">PCT</span>
         <span class="p">}</span>
</code></pre>
      <footer class="qkt-codeframe__foot">
        <span class="qkt-codeframe__cmd"><span class="sigil">$</span> qkt backtest strategies/momentum.qkt <span class="qkt-cmd__note">test it</span></span>
        <span class="qkt-codeframe__cmd"><span class="sigil">$</span> qkt run strategies/momentum.qkt <span class="qkt-cmd__note">paper-trade it</span></span>
        <span class="qkt-codeframe__cmd"><span class="sigil">$</span> qkt deploy strategies/momentum.qkt <span class="qkt-cmd__note">go live</span></span>
      </footer>
    </div>
  </aside>

  <div class="qkt-ticker" aria-hidden="true">
    <span class="qkt-ticker__strip">
      <span class="tick">EURUSD <span class="up">1.0832 ▲</span></span><span class="sep">·</span>
      <span class="tick">XAUUSD <span class="down">2031.40 ▼</span></span><span class="sep">·</span>
      <span class="tick">BTCUSDT <span class="up">67422.15 ▲</span></span><span class="sep">·</span>
      <span class="tick">SPX500 <span class="up">5217.30 ▲</span></span><span class="sep">·</span>
      <span class="tick">GBPJPY <span class="down">192.18 ▼</span></span><span class="sep">·</span>
      <span class="tick">ETHUSDT <span class="up">3284.91 ▲</span></span><span class="sep">·</span>
      <span class="tick">USDJPY <span class="up">155.42 ▲</span></span><span class="sep">·</span>
      <span class="tick">NAS100 <span class="down">18204.5 ▼</span></span><span class="sep">·</span>
      <span class="tick">UKOIL <span class="up">82.71 ▲</span></span><span class="sep">·</span>
      <span class="tick">SOLUSDT <span class="up">152.30 ▲</span></span><span class="sep">·</span>
      <span class="tick">AUDUSD <span class="down">0.6542 ▼</span></span><span class="sep">·</span>
      <span class="tick">EURUSD <span class="up">1.0832 ▲</span></span><span class="sep">·</span>
      <span class="tick">XAUUSD <span class="down">2031.40 ▼</span></span><span class="sep">·</span>
      <span class="tick">BTCUSDT <span class="up">67422.15 ▲</span></span><span class="sep">·</span>
      <span class="tick">SPX500 <span class="up">5217.30 ▲</span></span><span class="sep">·</span>
      <span class="tick">GBPJPY <span class="down">192.18 ▼</span></span><span class="sep">·</span>
      <span class="tick">ETHUSDT <span class="up">3284.91 ▲</span></span><span class="sep">·</span>
      <span class="tick">USDJPY <span class="up">155.42 ▲</span></span><span class="sep">·</span>
      <span class="tick">NAS100 <span class="down">18204.5 ▼</span></span><span class="sep">·</span>
      <span class="tick">UKOIL <span class="up">82.71 ▲</span></span><span class="sep">·</span>
      <span class="tick">SOLUSDT <span class="up">152.30 ▲</span></span><span class="sep">·</span>
      <span class="tick">AUDUSD <span class="down">0.6542 ▼</span></span><span class="sep">·</span>
    </span>
  </div>
</section>

<section class="qkt-section" markdown="1">

<div class="qkt-section__head" markdown="0">
  <span class="qkt-section__num">§ 01</span>
  <h2 class="qkt-section__title">How qkt works, in three steps</h2>
  <span class="qkt-section__rule"></span>
</div>

<div class="qkt-steps" markdown="0">
<div class="qkt-step">
<div class="qkt-step__num">1</div>
<h3>Write your strategy</h3>
<p>You write a small file that says <em>"when this happens, do that"</em> — for example: when the 9-period moving average crosses above the 21-period one, buy Bitcoin with a stop-loss. The syntax reads like English, so you don't need to be a software engineer to follow it.</p>
</div>
<div class="qkt-step">
<div class="qkt-step__num">2</div>
<h3>Test it on real data</h3>
<p>Run the same file against years of historical market data — Bitcoin, EURUSD, gold, stocks. qkt reports how the strategy would have performed: how many trades, win rate, profit, the deepest drawdown. You'll know whether the idea is worth more time before you risk anything.</p>
</div>
<div class="qkt-step">
<div class="qkt-step__num">3</div>
<h3>Go live, when you're ready</h3>
<p>Point the same strategy file at a paper-trading account first to watch it in real-time. When you're satisfied, connect it to your real broker. qkt is designed so the trades you saw in testing match what happens live — the engine doesn't behave differently between modes.</p>
</div>
</div>

</section>

<section class="qkt-section" markdown="1">

<div class="qkt-section__head" markdown="0">
  <span class="qkt-section__num">§ 02</span>
  <h2 class="qkt-section__title">What you can build</h2>
  <span class="qkt-section__rule"></span>
</div>

A few examples of what people use qkt for:

<div class="grid cards" markdown>

- :material-chart-bell-curve:{ .lg .middle } **Test a trading idea**

    ---

    You've read about a momentum or mean-reversion strategy and want to see whether it actually works on real markets. Write it as a `.qkt` file, run it against historical data, look at the numbers.

- :material-shield-check:{ .lg .middle } **Validate before risking money**

    ---

    Paper-trade your strategy for a few weeks. Watch how it reacts to news events, weekends, and unusual price moves — without losing a penny.

- :material-cog-sync:{ .lg .middle } **Run multiple strategies at once**

    ---

    qkt's daemon hosts many strategies side by side, each with its own observability port, log file, and risk budget. Combine them into a portfolio when you're ready.

- :material-tune:{ .lg .middle } **Tune your parameters honestly**

    ---

    Run a parameter sweep across hundreds of combinations to find what worked, then walk-forward test to make sure it's not overfit to one period of data.

- :material-bridge:{ .lg .middle } **Connect to your broker**

    ---

    Bybit (spot + linear), MT5 brokers (Exness, ICMarkets, FTMO, Pepperstone), TradingView feeds. Adding a new broker is a small adapter — Alpaca and IBKR are on the roadmap.

- :material-source-branch:{ .lg .middle } **Read and modify the engine**

    ---

    qkt is small (~30k lines of Kotlin), open-source under Apache 2.0, and designed to be read end-to-end. Forking and changing behavior is encouraged.

</div>

</section>

<section class="qkt-section" markdown="1">

<div class="qkt-section__head" markdown="0">
  <span class="qkt-section__num">§ 03</span>
  <h2 class="qkt-section__title">Start here</h2>
  <span class="qkt-section__rule"></span>
</div>

<div class="grid cards" markdown>

- :material-rocket-launch:{ .lg .middle } **5-minute quickstart**

    ---

    Clone, build, run your first paper-traded strategy. Real fills land in your terminal.

    [:octicons-arrow-right-24: Get started](get-started/quickstart.md)

- :material-school:{ .lg .middle } **Browse the recipes**

    ---

    Copy-paste guides for the most common tasks — moving-average crossovers, stop-losses, parameter sweeps, debugging.

    [:octicons-arrow-right-24: Open the cookbook](how-to/index.md)

- :material-cash:{ .lg .middle } **Deploy live on MT5**

    ---

    Docker stack walkthrough for Exness (or ICMarkets, FTMO, Pepperstone). About 10 minutes end-to-end.

    [:octicons-arrow-right-24: Deploy on MT5](how-to/deploy-exness.md)

- :material-scale-balance:{ .lg .middle } **qkt vs alternatives**

    ---

    Honest comparison with Lean, Backtrader, freqtrade, NautilusTrader, QuantConnect. When to pick qkt — and when not to.

    [:octicons-arrow-right-24: Compare](concepts/why-qkt.md)

- :material-code-tags:{ .lg .middle } **Learn the strategy language**

    ---

    Every keyword qkt understands, with examples. Indicators, conditions, orders, brackets, pyramiding.

    [:octicons-arrow-right-24: DSL grammar](reference/dsl-grammar.md)

- :material-graph:{ .lg .middle } **How the engine works inside**

    ---

    Tick → strategy → order → broker → trade. Four diagrams, twenty minutes — if you want to understand the internals.

    [:octicons-arrow-right-24: System overview](concepts/architecture.md)

</div>

</section>

<section class="qkt-section" markdown="1">

<div class="qkt-section__head" markdown="0">
  <span class="qkt-section__num">§ 04</span>
  <h2 class="qkt-section__title">Before you trade real money</h2>
  <span class="qkt-section__rule"></span>
</div>

!!! warning "qkt is pre-1.0 — please don't connect a funded account yet"
    qkt is open-source software built by a single developer. The engine works and is well-tested on historical and paper data, but it has not yet been used in production with real funds. A few honest caveats:

    - **No third-party security review.** The code is open and auditable, but no one has paid auditors to look at it.
    - **No real-money users yet.** That means edge cases that only show up under live trading conditions haven't all been found.
    - **Breaking changes between minor versions.** The strategy language is stable; the engine internals still move.
    - **The MT5 connector is a community wrapper**, not officially supported by MetaQuotes.

    Use qkt with paper accounts and demo brokers. Wait for the 1.0 release before considering live capital. If you find something broken, [open an issue](https://github.com/elitekaycy/qkt/issues/new) — that's how it gets fixed.

</section>
