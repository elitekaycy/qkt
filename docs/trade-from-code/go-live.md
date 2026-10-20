# Go live

A live session is the same session on your account: the commands, the identities, the risk limits and the
journal are the ones you used in the backtest. Only `session start` changes. Start on a demo account; qkt refuses to
trade when the account it reaches is not the one the config names.

## The account

The broker comes from `qkt.config.yaml`, as for any live qkt run ([Deploy MT5](../get-started/deploy-mt5.md) sets up
the gateway; futures and options go through the [venue gateway](../reference/bot-cli.md)). Pin the account so a
wrong login cannot trade, and keep the risk limits:

```yaml
brokers:
  exness:
    type: mt5
    extends: exness
    gateway_url: http://127.0.0.1:5001
    expected_account_login: 436804390
    expected_trade_mode: demo
risk:
  max_order_qty: "0.05"
```

## The session

```bash
qkt bot session start --symbols EXNESS:BTCUSD --tf 1m --identities agent --run golive --out runs/golive --json \
  > session.log 2>&1 &
qkt bot session wait --run golive --timeout 180 --json
qkt bot next EXNESS:BTCUSD --run golive --json
qkt bot buy 0.01 EXNESS:BTCUSD --sl pct:1 --tp rr:2 --as agent --run golive --json
qkt bot positions --run golive --json
qkt bot close EXNESS:BTCUSD --as agent --run golive --json
qkt bot session finish --run golive --json
```

Live, `next` blocks until the bar closes, and orders go to the account. On the Exness demo on 2026-10-20 the buy
filled at the venue with its stop and target held there, a 1-lot order was refused by the 0.05 cap, and after the
close the account was flat:

```text
"ticket":3387492620,"symbol":"BTCUSDm","side":"BUY","lots":0.01,"entry":82690.69,"current":82715.64,"sl":81863.78,"tp":84344.51
"reason":"order qty 1 exceeds per-order cap 0.05"
"openLegs":[],"workingOrders":[]
```

Verbs need `--run golive`: a verb without a session to route to acts one-shot on the account, and one that names a
session that is not running sends nothing.

## Replay the live session

`finish` keeps, beside the journal, every tick the session was handed (`ticks.csv`), the venue's own record of its
executions (`venue-deals.jsonl`) and the venue's instrument spec it traded on (`instruments.yaml`), so the record
replays on any machine:

```bash
qkt bot session replay runs/golive --out runs/golive-replay --json
```

```text
"sameTrades":true,"samePrices":true,"recordedFills":2,"replayedFills":2
```

The replay runs the session again over the market it traded on and fills each order at the quote the venue executed
it at. `identical` stays `false` only on the fill times: the venue's confirmation reached qkt a few hundred
milliseconds after it executed. Pass `--predict` to replay at the measured round trip instead, which is what a
backtest of the same commands would have done.

## One-shot commands

Without a session, `qkt bot buy`, `sell`, `close`, `modify` and `cancel` act on the account directly and exit, with
the same risk limits and the same `--json` replies; `quote`, `bars`, `positions`, `orders`, `account` and `history`
read it. A gateway broker (futures, perpetuals, options) holds no stops for a one-shot order, so a protected entry
there needs a session, where the engine holds them.
