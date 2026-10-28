# Trade US shares through Alpaca

Cash equities, not CFDs: qkt buys and sells the shares themselves on an Alpaca account, through a
[qkt-venue-gateway](https://github.com/elitekaycy/qkt-venue-gateway) running its Alpaca adapter. qkt never holds the
Alpaca key; the gateway does. Measured on Alpaca paper (2026-10); the adapter's page has every venue fact.

## Run the gateway

```console
$ export GATEWAY_ADAPTER=alpaca GATEWAY_SETTING_ENVIRONMENT=paper   # live is real money, never a default
$ export GATEWAY_LOGIN=<key id> GATEWAY_SECRET=<secret>               # from your Alpaca dashboard
$ export GATEWAY_TRADER_TOKEN=<random> GATEWAY_GUARDIAN_TOKEN=<random> GATEWAY_LISTEN=127.0.0.1:8446
$ qkt-venue-gateway
```

`GATEWAY_SETTING_EXTENDED_HOURS=true` lets limit DAY and GTC orders on shares fill outside regular hours
(04:00-09:30, 16:00-20:00 and overnight 20:00-04:00 New York); market orders trade regular hours only.

## Connect qkt

```yaml
brokers:
  alpaca:
    type: gateway
    gateway_url: http://127.0.0.1:8446
    api_key: env:ALPACA_TRADER_TOKEN        # the gateway's GATEWAY_TRADER_TOKEN
    expected_adapter: alpaca
    expected_account_login: "<the account_login the gateway's /v1/health reports>"
    expected_trade_mode: demo               # real on the live environment
    calendars:
      "category:crypto": crypto             # coin pairs trade around the clock
      "category:*": nyse                    # every share on the NYSE session, holidays and early closes included
```

The adapter files each share under its exchange (`NASDAQ`, `NYSE`, `ARCA`, ...) and each coin pair under `crypto`, so
the two `category:` rules put every symbol on its session with no list to keep. For extended hours, write the window:
`"category:*": nyse[04:00-20:00]`. The overnight session crosses midnight, which no qkt calendar window does yet: a
strategy is not evaluated there.

```sql
SYMBOLS
    aapl = ALPACA:AAPL EVERY 5m
    btc  = ALPACA:BTCUSD EVERY 5m
RULES
    WHEN aapl.close > sma(aapl.close, 20) AND POSITION.aapl = 0
    THEN BUY aapl SIZING 1000 USD                         -- whole shares: $1,000 at $346 is 2 shares
```

A share's quantity is a share count (contract size 1). Pull the venue's facts into the data root, so backtests size
and refuse what the venue would, and live sessions read the same file:

```console
$ qkt instruments pull --config qkt.config.yaml --symbols ALPACA:AAPL,ALPACA:BTCUSD --out data/instruments.yaml
```

One order from the shell works the same way:

```console
$ qkt bot buy 1 ALPACA:AAPL --limit 300 --tif day --config qkt.config.yaml --json
```

## What the venue decides

- **DAY** is Alpaca's trading day: qkt sends `day` and leaves the end to the venue (a gateway venue ends its own day;
  MT5 brokers get qkt's session end as an expiry instead).
- **Short sales** need a shortable share; a share Alpaca cannot lend lists as long-only, and coin pairs are long-only.
  With the instruments pulled, qkt refuses an opening sell on them before it is sent, in backtest and live; without
  them, Alpaca refuses it.
- **Coin orders** need at least $10 of value and take only GTC or IOC.
- **Costs**: no commission on Alpaca. Regulatory sale fees, when you want them modelled in backtests, go in the
  instrument's `equityCosts` ([Backtest data](backtest-data.md)).

## Not supported yet

Fractional shares, trailing stops, opening and closing auction orders (`OPG`/`CLS`), options, borrow fees, the
pattern-day-trader count (Alpaca paper reports no flag), strategies evaluated in the overnight session, and Alpaca's
dividend and fee activities reaching qkt's ledger (the venue's equity has them). See the equities plan,
`docs/superpowers/plans/2026-10-27-equities.md`.
