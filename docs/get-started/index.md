# Get started

Three paths from clone to running strategy. Pick the one that matches what you want to do today.

<div class="grid cards" markdown>

- :material-rocket-launch:{ .lg .middle } **Quickstart**

    ---

    Five minutes from clone to a running paper-traded strategy.

    [:octicons-arrow-right-24: Quickstart](quickstart.md)

- :material-test-tube:{ .lg .middle } **Deploy paper**

    ---

    Run a strategy in the daemon against the in-process paper broker. No real money, no real broker.

    [:octicons-arrow-right-24: Deploy paper](deploy-paper.md)

- :material-cash:{ .lg .middle } **Deploy MT5**

    ---

    Spin up the full Docker stack — qkt + mt5-gateway — and trade against an Exness or other MT5 broker.

    [:octicons-arrow-right-24: Deploy MT5](deploy-mt5.md)

</div>

## What you need

| For | Required |
|---|---|
| Quickstart | JDK 21, Git |
| Deploy paper | JDK 21, Git, a working `qkt` install |
| Deploy MT5 | Docker + Docker Compose, an MT5 broker account (Exness/ICMarkets/FTMO/Pepperstone) |

## Where to next

After your first strategy is running, the [Concepts](../concepts/index.md) section explains how the engine actually works under the hood — what guarantees you get and what you don't. The [Reference](../reference/index.md) section is the lookup material when you're writing strategies day-to-day.
