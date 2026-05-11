# Tutorial 1 — Your first strategy

You're going to install qkt, write a small trading strategy in a single file, backtest it against historical Bitcoin data, and look at the result. About 20 minutes from start to finish, assuming no surprises.

By the end of this tutorial you'll know:

- What a qkt strategy looks like and why it's structured the way it is
- How to backtest it and read the output
- What every line in the strategy file does
- Enough vocabulary to start adapting examples for your own ideas

## Part 1 — Get qkt running

### Step 1. Clone the repo

```bash
git clone https://github.com/elitekaycy/qkt.git
cd qkt
```

If you don't have Git, install it first. On Mac: `brew install git`. On Linux: `apt install git` or your distro's equivalent. On Windows: [git-scm.com](https://git-scm.com/).

### Step 2. Build qkt

```bash
./gradlew installDist
```

This downloads dependencies (about 200 MB the first time — coffee break) and produces a runnable `qkt` binary in `build/install/qkt/bin/qkt`.

If you don't have JDK 21, Gradle's toolchain may auto-provision one. If that fails, install Temurin 21 from [adoptium.net](https://adoptium.net/).

### Step 3. Put `qkt` on your PATH

```bash
export PATH="$PWD/build/install/qkt/bin:$PATH"
qkt --version
```

Expected output:

```text
qkt 0.25.0
```

If you see that, you're set. The PATH export only lasts for this shell session — for permanent use, add it to your `~/.bashrc` or `~/.zshrc`.

## Part 2 — Understand what a strategy is

Before you write one, let's look at the shape of a qkt strategy.

A strategy file answers three questions:

1. **What markets do I care about?** → `SYMBOLS` block
2. **When do I want to do something?** → `RULES` block, the `WHEN` clauses
3. **What do I want to do?** → `RULES` block, the `THEN` clauses

That's it. There's a header (`STRATEGY name VERSION 1`) wrapping all this, and a few optional bits (`LET` for variables, `DEFAULTS` for shared settings), but the three-block structure is the whole idea.

Here's the smallest meaningful strategy:

```qkt
STRATEGY hello VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 50000
    THEN LOG "Bitcoin is above 50k"
```

Read it line by line:

- `STRATEGY hello VERSION 1` — names this strategy `hello`, version 1
- `SYMBOLS` — declares what we're listening to
- `btc = BACKTEST:BTCUSDT EVERY 1m` — name the **BTCUSDT** market from the **BACKTEST** data source on **1-minute candles**, and refer to it as `btc` everywhere else
- `RULES` — what to do
- `WHEN btc.close > 50000` — every time a 1-minute candle closes with Bitcoin above $50,000…
- `THEN LOG "Bitcoin is above 50k"` — …write a log line

It does nothing useful yet — just logs. We'll add real trading in a moment.

## Part 3 — Write a real strategy

Let's build something with actual logic: a momentum strategy that buys Bitcoin when its short-term price trend turns up, and sells when it turns down.

Create a new directory:

```bash
mkdir -p strategies
```

Then create `strategies/momentum.qkt` with this content:

```qkt title="strategies/momentum.qkt"
STRATEGY momentum VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    # Buy when the 9-period moving average crosses above the 21-period one
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.1
         LOG "long entry"

    # Sell to close when the trend reverses
    WHEN ema(btc.close, 9) CROSSES BELOW ema(btc.close, 21)
     AND POSITION.btc > 0
    THEN CLOSE btc
         LOG "exit"
```

Let's unpack what's new:

- `ema(btc.close, 9)` — the **exponential moving average** of Bitcoin's close price over the last 9 candles. A moving average smooths short-term noise to reveal the trend.
- `CROSSES ABOVE` — fires **once**, on the candle where the fast MA crosses above the slow one. This is called an "edge-triggered" condition — it doesn't keep firing while the fast MA stays above the slow one.
- `POSITION.btc = 0` — only enter if we're flat. Without this guard, the rule wouldn't re-enter after closing (because of edge-trigger), but it also protects against accidental double-entries during testing.
- `BUY btc SIZING 0.1` — buy 0.1 of Bitcoin (a synthetic quantity for the BACKTEST data source)
- `CLOSE btc` — exit any open position on Bitcoin
- The `#` lines are **comments**, ignored by qkt (alongside `--` and `/* ... */`)

This is the classic momentum strategy. It catches sustained moves but loses small amounts in choppy markets where the MAs whipsaw.

## Part 4 — Get data to test against

Backtests need historical data. qkt has a tiny sample bundled in the repo:

```bash
ls data/sample/symbols/
```

You should see a couple of symbol directories with CSV files. Two days of synthetic-but-realistic ticks.

We'll use this sample for the tutorial. In real use you'd fetch a longer period via `./scripts/fetch-dukascopy.sh` — see the [data store recipe](../how-to/index.md) when you're ready.

## Part 5 — Run your first backtest

```bash
qkt backtest strategies/momentum.qkt \
    --data-root data/sample \
    --from 2024-01-15 --to 2024-01-17
```

(Adjust the date range to whatever the sample covers — `ls data/sample/symbols/BTCUSD/` shows the available days.)

You should see output like:

```text
[INFO] qkt 0.25.0 — strategy momentum v1 — backtest

Trades:           4
Final realized:   -127.50
Win rate:         0.250
Sharpe (daily):   n/a (insufficient samples)
Max drawdown:     -180.25

Report: ./reports/momentum-<timestamp>.html
```

A few things to notice:

- **`Trades: 4`** — the strategy fired 4 entry/exit cycles across the sample data. That's a small sample; real backtests use months.
- **`Final realized: -127.50`** — net loss. Two days is too short for a momentum strategy to find a sustained move.
- **`Win rate: 0.250`** — 25% of trades closed in profit. Momentum strategies typically have low win rates by design — they catch fewer but bigger winners. Two days is too short to show that.
- **`Sharpe (daily): n/a`** — can't compute risk-adjusted return on 2 days.
- **`Max drawdown: -180.25`** — worst peak-to-trough loss.

Open the HTML report to see charts:

```bash
# Mac
open reports/momentum-*.html
# Linux
xdg-open reports/momentum-*.html
# Windows
start reports/momentum-*.html
```

You'll see the equity curve, drawdown periods, and per-trade table. **Don't be alarmed by the negative P&L** — two days isn't enough data to evaluate any strategy. The point of this run is to prove the strategy works end-to-end, not that it's profitable.

## Part 6 — Read the report

Open the HTML and scroll through:

1. **Header strip** — name, version, date range, final equity
2. **Equity curve** — black line is total equity over time; the orange band is "underwater" (current equity below the running peak)
3. **Drawdown periods table** — every time equity hit a new low, when it started, when it recovered
4. **Trade table** — every entry and exit with prices, sizes, realized P&L, and per-trade risk
5. **Monte Carlo section** — bootstrapped what-if scenarios showing 5th/50th/95th percentile equity paths if the trades had occurred in random order

Most of these only become meaningful with more data. The point right now is: **you wrote a strategy, qkt ran it, and you have a report.**

## Part 7 — Iterate

Try changing the strategy and re-running:

### Faster moving averages

```qkt
WHEN ema(btc.close, 5) CROSSES ABOVE ema(btc.close, 13)
```

Faster MAs = more trades = more friction. Usually worse, sometimes better.

### Add a stop-loss

```qkt
WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
 AND POSITION.btc = 0
THEN BUY btc SIZING 0.1
     BRACKET {
       STOP_LOSS BY 200,
       TAKE_PROFIT BY 500
     }
     LOG "long entry"
```

The `BRACKET` attaches a stop-loss $200 below entry and a take-profit $500 above. Now even if the cross-below exit doesn't fire, you can't lose more than $200 on the trade.

### Add a trend filter

```qkt
LET trend = sma(btc.close, 100)

RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
     AND btc.close > trend          # only when above longer-term trend
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.1
```

`LET` names an expression for reuse. `AND` combines conditions. This filter — only trade with the trend — improves most momentum strategies.

(`sma` is the simple moving average — same period semantics as `ema` but equally weighted. Both are in qkt's catalog.)

## What you learned

- A strategy is a `.qkt` file with three blocks: `SYMBOLS`, `RULES`, and the header
- `WHEN ... THEN ...` rules express trading logic
- `CROSSES ABOVE` / `CROSSES BELOW` are edge-triggered — they fire **on** the cross, not while it stays crossed
- `POSITION.<stream>` returns the current position quantity; gate entries with `POSITION.btc = 0`
- Brackets attach stops + targets to entries atomically
- A backtest produces both numbers and a visual HTML report

## What's next

- **[Tutorial 2 — Backtest to live](backtest-to-live.md)** — take this strategy through paper-trading and onto a demo broker
- **[Tutorial 3 — Composing strategies](compose-portfolio.md)** — wire multiple strategies into a portfolio
- **[DSL deep-dive](../reference/dsl/index.md)** — every keyword explained
- **[Examples](../examples/index.md)** — seven complete strategies showing different patterns

## If something went wrong

- **`./gradlew installDist` failed** — usually JDK version mismatch. Run `java -version` and confirm 21. If older, install Temurin 21.
- **`qkt: command not found`** — the `PATH` export only lasts for one shell. Re-run `export PATH="$PWD/build/install/qkt/bin:$PATH"`.
- **`Symbol BTCUSDT not found`** — the data path is wrong. Check `data/sample/symbols/` and adjust `--data-root` to point at it.
- **HTML report doesn't open** — find it at `reports/momentum-<timestamp>.html` and open manually.

Anything else? Check the [debug recipe](../how-to/debug-not-firing.md) or [open an issue](https://github.com/elitekaycy/qkt/issues/new).
