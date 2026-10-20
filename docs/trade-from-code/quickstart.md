# Quickstart: trade from your code

Ten minutes, offline after one download. A session runs over the month of sample data, a shell and a Python script
trade in it side by side, your risk limit refuses an order that breaks it, and the whole session replays with the
same fills. Do the [main quick start](../get-started/quickstart.md) first if qkt is not installed.

<!-- quickstart:begin -->
**1. Data and a risk limit**

```bash
qkt data sample
cat > qkt.config.yaml <<'YAML'
risk:
  max_order_qty: "0.5"
YAML
```

`max_order_qty` caps every order's size, from any caller. The sample is a month of BTCUSDT one-minute bars.

**2. Start a session**

```bash
qkt bot session start --backtest --symbols BINANCE:BTCUSDT --tf 15m --from 2026-09-01 --to 2026-09-08 \
  --identities shell,script --run demo --out runs/demo --json > session.log 2>&1 &
qkt bot session wait --run demo --json
```

A session is the qkt engine held open for your code: here a backtest over the sample's first week, on 15-minute
bars, with two identities so every order says who sent it. `start` runs in the foreground, so it goes in the
background and `wait` returns once the session answers. Pass `--run demo` on every command that follows: a command
that names a session that is not running fails and sends nothing.

**3. Trade from the shell**

```bash
qkt bot next BINANCE:BTCUSDT --run demo --json
qkt bot buy 0.1 BINANCE:BTCUSDT --sl pct:1 --tp rr:2 --as shell --run demo --json
qkt bot buy 2 BINANCE:BTCUSDT --sl pct:1 --tp rr:2 --as shell --run demo --json
qkt bot next BINANCE:BTCUSDT --run demo --json
```

`next` is the clock: it hands out the next closed bar and everything that happened to your orders since the last
one. The first buy fills; the second asks for 2 BTC, over the limit, and comes back `rejected` with the rule that
refused it.

**4. Trade from a Python script**

```bash
python3 qkt/share/examples/bot/python/agent_loop.py --run demo --symbol BINANCE:BTCUSDT --identity script \
  --fast 5 --slow 20 --lots 0.1
```

`agent_loop.py` decides with a moving-average cross in plain Python and places its orders through the session's
HTTP API (`qkt_session.py` beside it is a small client with no dependencies). Both ship in the release archive
under `qkt/share/examples/bot/`, and in the repository under `examples/bot/`. At the end of the data it closes everything
and finishes the session, which writes the report to `runs/demo`.

**5. Replay it**

```bash
qkt bot session replay runs/demo --out runs/demo-replay --json
```
<!-- quickstart:end -->

The replay runs the session again from its journal (every intent, from every caller, at the tick it arrived) and
compares the fills: `identical` is `true`. The report in `runs/demo/report.html` has one equity curve per identity.

## What you just used

- **One engine for every caller.** The shell's orders and the script's went through the same pipeline a `.qkt`
  strategy runs on: the same fill model, the same risk checks from `qkt.config.yaml`, the same journal.
- **Your rules, not the caller's.** The size limit refused the shell's order without any code in the shell.
  Instrument specs (`instruments.yaml`), halts and Insights apply the same way.
- **Replay.** Anything that traded in a session, backtest or live, can be run again and compared fill for fill.

## Next

- [Connect an AI agent](ai-agent.md): the same session, driven by a language model.
- [From any language](any-language.md): the HTTP API the Python script used.
- [Go live](go-live.md): the same commands on a demo account.
