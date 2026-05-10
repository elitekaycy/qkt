# Architecture

qkt is event-driven from end to end: market data flows in as ticks, strategies emit signals, signals become orders, orders go to a broker, fills come back, the engine settles them, and observers see the state.

## High-level topology

```mermaid
flowchart LR
    Source([Market data source]) -->|Tick| Bus
    Bus[(EventBus)] -->|Tick| Strategy
    Strategy[Strategy / Compiled DSL] -->|Signal| OrderManager
    OrderManager -->|Order| Broker
    Broker -->|Trade| Bus
    Bus -->|Trade| Risk
    Bus -->|Trade| PnL[P&L attribution]
    Bus -->|Tick / Trade| Observability
    Observability -->|HTTP /metrics, /status, /logs| Operator((Operator))
```

The `EventBus` is the single backbone. Components publish and subscribe; nobody calls anyone directly. This is what enables backtest=live parity: swap the source from a CSV replay to a live feed, swap the broker from `PaperBroker` to `MT5Broker`, and the same strategy code runs.

## Backtest vs live

```mermaid
flowchart TB
    subgraph Backtest
        CSV[CSV replay] --> Bus1[(EventBus)]
        Bus1 --> Strat1[Strategy]
        Strat1 --> OM1[OrderManager]
        OM1 --> Paper[PaperBroker]
        Paper --> Bus1
    end
    subgraph Live
        MT5Feed[MT5 / TV feed] --> Bus2[(EventBus)]
        Bus2 --> Strat2[Strategy]
        Strat2 --> OM2[OrderManager]
        OM2 --> Real[MT5Broker / PaperBroker]
        Real --> Bus2
    end
    Strat1 -. same code .- Strat2
    OM1 -. same code .- OM2
```

Phase 19's parity test enforces that the same strategy + same tick stream produces the same trades regardless of which path runs them.

## Strategy lifecycle inside a daemon

```mermaid
sequenceDiagram
    participant CLI as qkt CLI
    participant D as Daemon
    participant LS as LiveSession
    participant B as Broker
    participant Bus as EventBus
    participant S as Strategy

    CLI->>D: deploy momentum.qkt
    D->>LS: start("momentum")
    LS->>B: BrokerFactory(bus, clock, tracker)
    LS->>Bus: subscribe Tick → S
    Bus->>S: Tick
    S->>Bus: Signal
    Bus->>B: Order
    B-->>Bus: Trade
    Bus->>S: Trade
    CLI->>D: stop momentum
    D->>LS: close()
    LS->>B: close()
```

Each `LiveSession` owns its broker. Stopping a strategy fully tears down its connection to the venue — no shared state across sessions.

## Portfolio dispatch (Phase 14)

```mermaid
flowchart LR
    Parent[PORTFOLIO mybook] --> Sup[PortfolioSupervisor]
    Sup --> C1[Child: trend]
    Sup --> C2[Child: meanrev]
    Sup --> C3[Child: breakout]
    C1 -->|Signal| Router{Risk router}
    C2 -->|Signal| Router
    C3 -->|Signal| Router
    Router -->|sized + capped Order| Broker
```

A portfolio is a parent strategy that spawns children. Each child publishes signals scoped to its alias; the supervisor's risk router sizes and caps before forwarding to the broker.

## Where to read more

- [Determinism](determinism.md) — the parity contract and what's deterministic.
- [Backtest model](backtest-model.md) — how fills, slippage, and equity are computed.
- [Broker integration](broker-integration.md) — capability matrix and MT5 specifics.
- API reference (KDoc) — <a href="/qkt/api/">/api/</a> (built by CI)
- Phase changelogs — [Phases](../phases/index.md)
