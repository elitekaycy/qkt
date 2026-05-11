---
title: qkt
hide:
  - navigation
  - toc
---

<section class="qkt-hero qkt-hero--split" markdown="0">
  <div class="qkt-hero__left">
    <div class="qkt-hero__meta">
      <span class="pill pill--accent">qkt / 0.24.0</span>
      <span class="pill">kotlin · jdk 21</span>
      <span class="pill pill--warn">do not trade real money yet</span>
    </div>

    <h1 class="qkt-hero__lede">One <em>strategy file.</em><br>Three execution modes.<br>Identical trades.</h1>

    <p class="qkt-hero__sub">
      qkt is a Kotlin trading engine where the <strong>same <code>.qkt</code> file</strong> backtests, paper-trades, and runs live on MT5 — and a regression test enforces that the trades come out bit-identical across all three.
    </p>

    <div class="qkt-hero__ctas">
      <a class="qkt-btn qkt-btn--primary" href="get-started/quickstart/">
        <span>Run it in 5 minutes</span>
        <span class="qkt-btn__arrow">→</span>
      </a>
      <a class="qkt-btn" href="concepts/determinism/">Why determinism matters</a>
      <a class="qkt-btn" href="concepts/architecture/">Read the architecture</a>
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
        <span class="qkt-codeframe__cmd"><span class="sigil">$</span> qkt backtest strategies/momentum.qkt</span>
        <span class="qkt-codeframe__cmd"><span class="sigil">$</span> qkt run strategies/momentum.qkt</span>
        <span class="qkt-codeframe__cmd"><span class="sigil">$</span> qkt deploy strategies/momentum.qkt</span>
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
  <h2 class="qkt-section__title">qkt is for you if…</h2>
  <span class="qkt-section__rule"></span>
</div>

<div class="qkt-twoup" markdown="0">
  <div class="qkt-twoup__yes">
    <h3>Pick qkt when</h3>
    <ul>
      <li>You want one strategy file that backtests <em>and</em> runs live with bit-identical fills.</li>
      <li>You're on the JVM and want a small, readable Kotlin engine you can step through.</li>
      <li>You're targeting MT5 (Exness, ICMarkets, FTMO, Pepperstone) and want a Docker stack.</li>
      <li>You care about determinism and reproducibility — same ticks → same trades, enforced by tests.</li>
      <li>You want a SQL-like DSL for strategies instead of writing Python class boilerplate.</li>
    </ul>
  </div>
  <div class="qkt-twoup__no">
    <h3>Pick something else when</h3>
    <ul>
      <li>You need a battle-tested production framework — qkt is pre-1.0, no real-money users yet.</li>
      <li>You want a huge indicator library (ta-lib parity). qkt ships ~10 indicators, not 200.</li>
      <li>You need market-microstructure backtesting (order-book reconstruction, queue position).</li>
      <li>You're a pure Python shop and don't want JVM in your stack.</li>
      <li>You want managed cloud execution. qkt runs on your hardware. <a href="concepts/why-qkt/">Compare alternatives →</a></li>
    </ul>
  </div>
</div>

</section>

<section class="qkt-section" markdown="1">

<div class="qkt-section__head" markdown="0">
  <span class="qkt-section__num">§ 02</span>
  <h2 class="qkt-section__title">The parity contract</h2>
  <span class="qkt-section__rule"></span>
</div>

The same strategy file produces the same trades whether you're replaying historical data or running live on MT5. This isn't marketing — it's enforced by [a regression test in `src/test/kotlin/com/qkt/parity/`](concepts/determinism.md) that fails the build if the two diverge by one tick.

Three primitives make it possible:

| primitive | what it does |
| --- | --- |
| `Clock` | Time always flows through this interface. Backtest uses `FixedClock`, live uses `SystemClock`. Strategies never read wall-clock directly. |
| `IdGenerator` / `SequenceGenerator` | Every order id, every event id, comes from a deterministic generator. Same inputs → same ids. |
| `EventBus` | Single backbone. Every component subscribes — no hidden direct calls. The event stream is the audit log. |

When your backtest says you'd have made $X, live execution on the same ticks produces $X. Slippage and venue-side latency are modeled separately and opt-in.

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

    Clone, build, run your first paper strategy. Real fills in your terminal.

    [:octicons-arrow-right-24: Quickstart](get-started/quickstart.md)

- :material-school:{ .lg .middle } **Recipes**

    ---

    Copy-paste guides for common tasks — EMA crossover, stop-loss, parameter sweep, debug.

    [:octicons-arrow-right-24: Recipes](how-to/index.md)

- :material-cash:{ .lg .middle } **Deploy live on MT5**

    ---

    Docker compose stack, broker profiles, Exness / ICMarkets / FTMO / Pepperstone.

    [:octicons-arrow-right-24: Deploy MT5](get-started/deploy-mt5.md)

- :material-graph:{ .lg .middle } **Architecture**

    ---

    Tick → bus → strategy → order → broker → trade. Four diagrams, twenty minutes.

    [:octicons-arrow-right-24: System overview](concepts/architecture.md)

- :material-code-tags:{ .lg .middle } **DSL reference**

    ---

    Every keyword, every action, every operator the `.qkt` parser accepts.

    [:octicons-arrow-right-24: DSL grammar](reference/dsl-grammar.md)

- :material-scale-balance:{ .lg .middle } **Why qkt?**

    ---

    Honest comparison vs. Lean, Backtrader, freqtrade, QuantConnect.

    [:octicons-arrow-right-24: Compare](concepts/why-qkt.md)

</div>

</section>

<section class="qkt-section" markdown="1">

<div class="qkt-section__head" markdown="0">
  <span class="qkt-section__num">§ 04</span>
  <h2 class="qkt-section__title">What's in the box</h2>
  <span class="qkt-section__rule"></span>
</div>

| capability | status |
| --- | --- |
| Deterministic event-driven engine | shipped |
| SQL-like strategy DSL (`.qkt` files) | shipped |
| Backtest replay with HTML report | shipped |
| Paper trading + per-strategy observability port | shipped |
| Live MT5 (Exness/ICMarkets/FTMO/Pepperstone via gateway) | shipped |
| Bybit Spot + Linear (USDT) | shipped |
| TradingView free-tier live ticks | shipped |
| Parameter sweeps + walk-forward | shipped |
| Portfolio composition (regime-gated children) | shipped |
| STACK pyramiding + BRACKET orders | shipped |
| Per-strategy P&L attribution, risk halts, drawdown caps | shipped |
| Real-money use | **not yet — pre-1.0** |

</section>

<section class="qkt-section" markdown="1">

<div class="qkt-section__head" markdown="0">
  <span class="qkt-section__num">§ 05</span>
  <h2 class="qkt-section__title">Status &amp; safety</h2>
  <span class="qkt-section__rule"></span>
</div>

!!! warning "Pre-1.0 — do not connect to a funded account"
    qkt is single-developer, fast-moving, and not yet battle-tested. The engine produces deterministic results on synthetic and historical data, and the parity test guarantees backtest = live-paper. But:

    - No third-party security review
    - No production users with real money
    - Breaking changes happen between minor versions
    - The MT5 gateway is a community wrapper, not officially supported by MetaQuotes

    Use it with paper accounts and demo brokers. Wait for 1.0 before considering live capital.

</section>
