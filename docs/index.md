---
title: qkt
hide:
  - navigation
  - toc
---

<section class="qkt-hero" markdown="0">
  <div class="qkt-hero__meta">
    <span class="pill pill--accent">qkt / 0.24.0</span>
    <span class="pill">status &nbsp;·&nbsp; pre-1.0</span>
    <span class="pill">kotlin · jdk 21</span>
    <span class="spacer"></span>
    <span>same ticks. same trades.</span>
  </div>

  <h1 class="qkt-hero__lede">A&nbsp;deterministic trading engine <em>in&nbsp;Kotlin.</em></h1>

  <p class="qkt-hero__sub">
    Event-driven. Strategy-as-text. One <code>.qkt</code> file backtests, paper-trades, and runs live on MT5 — and the trades are bit-identical across all three.
  </p>

  <nav class="qkt-hero__cmds" aria-label="get started">
    <a class="qkt-cmd" href="get-started/quickstart/">
      <span class="qkt-cmd__sigil">$</span>
      <span class="qkt-cmd__body">qkt run <span class="arg">strategies/momentum.qkt</span></span>
      <span class="qkt-cmd__label">quickstart</span>
      <span class="qkt-cmd__arrow">→</span>
    </a>
    <a class="qkt-cmd" href="get-started/deploy-paper/">
      <span class="qkt-cmd__sigil">$</span>
      <span class="qkt-cmd__body">qkt daemon &amp; qkt deploy <span class="arg">--as momo</span></span>
      <span class="qkt-cmd__label">deploy paper</span>
      <span class="qkt-cmd__arrow">→</span>
    </a>
    <a class="qkt-cmd" href="get-started/deploy-mt5/">
      <span class="qkt-cmd__sigil">$</span>
      <span class="qkt-cmd__body">docker compose up -d</span>
      <span class="qkt-cmd__label">deploy mt5</span>
      <span class="qkt-cmd__arrow">→</span>
    </a>
    <a class="qkt-cmd" href="reference/dsl-grammar/">
      <span class="qkt-cmd__sigil">§</span>
      <span class="qkt-cmd__body">STRATEGY momo VERSION 1 <span class="arg">…</span></span>
      <span class="qkt-cmd__label">dsl reference</span>
      <span class="qkt-cmd__arrow">→</span>
    </a>
  </nav>

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
      <span class="tick">NGAS <span class="down">2.18 ▼</span></span><span class="sep">·</span>
      <span class="tick">SOLUSDT <span class="up">152.30 ▲</span></span><span class="sep">·</span>
      <span class="tick">AUDUSD <span class="down">0.6542 ▼</span></span><span class="sep">·</span>
      <!-- doubled so the marquee loops seamlessly -->
      <span class="tick">EURUSD <span class="up">1.0832 ▲</span></span><span class="sep">·</span>
      <span class="tick">XAUUSD <span class="down">2031.40 ▼</span></span><span class="sep">·</span>
      <span class="tick">BTCUSDT <span class="up">67422.15 ▲</span></span><span class="sep">·</span>
      <span class="tick">SPX500 <span class="up">5217.30 ▲</span></span><span class="sep">·</span>
      <span class="tick">GBPJPY <span class="down">192.18 ▼</span></span><span class="sep">·</span>
      <span class="tick">ETHUSDT <span class="up">3284.91 ▲</span></span><span class="sep">·</span>
      <span class="tick">USDJPY <span class="up">155.42 ▲</span></span><span class="sep">·</span>
      <span class="tick">NAS100 <span class="down">18204.5 ▼</span></span><span class="sep">·</span>
      <span class="tick">UKOIL <span class="up">82.71 ▲</span></span><span class="sep">·</span>
      <span class="tick">NGAS <span class="down">2.18 ▼</span></span><span class="sep">·</span>
      <span class="tick">SOLUSDT <span class="up">152.30 ▲</span></span><span class="sep">·</span>
      <span class="tick">AUDUSD <span class="down">0.6542 ▼</span></span><span class="sep">·</span>
    </span>
  </div>
</section>

<section class="qkt-section" markdown="1">

<div class="qkt-section__head" markdown="0">
  <span class="qkt-section__num">§ 01</span>
  <h2 class="qkt-section__title">The thesis</h2>
  <span class="qkt-section__rule"></span>
</div>

qkt is a Kotlin trading runtime built on three commitments. Each is a constraint on every line of code in the repository — not a marketing slogan.

- **Event-driven, single backbone.** Ticks in, signals out, orders to brokers, trades back. One bus, many subscribers, no hidden coupling. The shape is the same in backtest as it is at 3am on a Friday with real money moving.
- **Deterministic by construction.** `Clock`, `IdGenerator`, `SequenceGenerator` are interfaces. Same ticks plus same strategy produces the same trades — every time, in every mode. A regression test enforces it.
- **Strategy-as-text.** A `.qkt` file is the artifact. The same file backtests, paper-trades, and runs live on MT5. No code change between modes.

</section>

<section class="qkt-section" markdown="1">

<div class="qkt-section__head" markdown="0">
  <span class="qkt-section__num">§ 02</span>
  <h2 class="qkt-section__title">Read the engine</h2>
  <span class="qkt-section__rule"></span>
</div>

<div class="grid cards" markdown>

- :material-rocket-launch:{ .lg .middle } **Get started in 5 minutes**

    ---

    Clone, build, run your first paper strategy.

    [:octicons-arrow-right-24: Quickstart](get-started/quickstart.md)

- :material-equal:{ .lg .middle } **Determinism**

    ---

    Why backtest equals live-paper on identical ticks — and why it must.

    [:octicons-arrow-right-24: The parity contract](concepts/determinism.md)

- :material-graph:{ .lg .middle } **Architecture**

    ---

    Tick → bus → strategy → order → broker → trade. Four Mermaid diagrams.

    [:octicons-arrow-right-24: System overview](concepts/architecture.md)

- :material-code-tags:{ .lg .middle } **DSL grammar**

    ---

    Every keyword, every action, every operator the `.qkt` parser accepts.

    [:octicons-arrow-right-24: Reference](reference/dsl-grammar.md)

- :material-cash:{ .lg .middle } **Go live on MT5**

    ---

    Spin up the Docker stack, log in once, deploy.

    [:octicons-arrow-right-24: Deploy MT5](get-started/deploy-mt5.md)

- :material-book-open-page-variant:{ .lg .middle } **API reference**

    ---

    KDoc-generated Dokka site for every public Kotlin type.

    <a href="/qkt/api/">:octicons-arrow-right-24: Browse the API</a>

</div>

</section>

<section class="qkt-section" markdown="1">

<div class="qkt-section__head" markdown="0">
  <span class="qkt-section__num">§ 03</span>
  <h2 class="qkt-section__title">What qkt is not</h2>
  <span class="qkt-section__rule"></span>
</div>

- **Not a broker.** qkt connects to brokers — MT5 today, Bybit composite in tree. It doesn't custody funds and doesn't execute on its own venue.
- **Not a backtest framework.** The backtester is one consumer of the engine. The same engine runs live.
- **Not an indicator library.** It ships the indicators needed to build strategies, not a comprehensive ta-lib port.

</section>

<section class="qkt-section" markdown="1">

<div class="qkt-section__head" markdown="0">
  <span class="qkt-section__num">§ 04</span>
  <h2 class="qkt-section__title">Lay of the land</h2>
  <span class="qkt-section__rule"></span>
</div>

| If you want to… | Go here |
| --- | --- |
| Run something in 5 minutes | [Quickstart](get-started/quickstart.md) |
| Understand how it works | [Concepts](concepts/index.md) |
| Look up syntax | [Reference](reference/index.md) |
| Deploy to production | [Operations](operations/index.md) |
| Contribute code | [Contributing](contributing/index.md) |
| See what shipped recently | [Phases](phases/index.md) |

</section>
