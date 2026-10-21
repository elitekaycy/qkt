# Connect an AI agent

An agent with a shell tool trades through qkt with the same commands as the [quickstart](quickstart.md). It
decides; qkt executes, so your risk limits, instrument specs and journal apply to everything it does, and you can
replay its session afterwards to see exactly what it did and why each order filled or did not.

## The prompt

[`examples/bot/SYSTEM_PROMPT.md`](https://github.com/elitekaycy/qkt/blob/main/examples/bot/SYSTEM_PROMPT.md)
(also in the release archive at `qkt/share/examples/bot/`) is a system prompt for any model: always `--json`,
always `--as <name>` and `--run <id>`, read what became of each order instead of assuming it filled, never work
around a refusal. Paste it into your agent's system prompt, or pass it as a file.

## A run

With a backtest session running as in the quickstart (`--run llm --identities agent`), this hands a model the
prompt and a task, and lets it run only `qkt bot` commands. The example uses Claude Code's headless mode; any agent
that can run shell commands works the same way.

```bash
awk 'f{print} /^---$/{f=1}' qkt/share/examples/bot/SYSTEM_PROMPT.md > prompt.txt
claude -p 'A qkt bot backtest session is running with run id "llm" on BINANCE:BTCUSDT 15m bars. Your identity
is "agent". Read 12 bars with `qkt bot next`. If the last close is above the average of those 12 closes, buy
0.1 BTC with a 1% stop and a 2:1 target; otherwise sell 0.1 the same way. Then also try to buy 1 BTC, and report
what qkt said about it. Read 8 more bars, report what happened to your orders with `qkt bot intents`, then
finish the session with flatten.' \
  --append-system-prompt "$(cat prompt.txt)" --allowedTools "Bash(qkt bot:*)"
```

On 2026-10-20 the model made seven calls, each a plain `qkt bot` command:

```text
qkt bot session wait --run llm --json
qkt bot next BINANCE:BTCUSDT --run llm --as agent --json        (12 times)
qkt bot sell 0.1 BINANCE:BTCUSDT --sl by:784.5 --tp rr:2 --as agent --run llm --dry-run --json
qkt bot sell 0.1 BINANCE:BTCUSDT --sl by:784.5 --tp rr:2 --as agent --run llm --json
qkt bot buy 1 BINANCE:BTCUSDT --as agent --run llm --json
qkt bot next BINANCE:BTCUSDT --run llm --as agent --json        (8 times)
qkt bot intents --run llm --as agent --json
qkt bot session finish --flatten --run llm --as agent --json
```

and reported:

```text
Signal: last close 78449.99 was below the 12-close average of 78521.94, so I sold 0.1 BTC with a 1% stop
(79234.49) and a 2:1 target (76880.99).
Sell: filled at 78441.65 on the next bar.
1 BTC buy: qkt rejected it with "order qty 1 exceeds per-order cap 0.5".
```

The model computed its own 1% stop distance (`by:784.5`), previewed the order with `--dry-run`, and learned the
size limit only from qkt's refusal: nothing in the prompt mentioned it. A model's choices vary from run to run; what
qkt does with each order does not.

## Keep it on a short leash

- **Allow only `qkt bot`.** `--allowedTools "Bash(qkt bot:*)"` (or your agent's equivalent) keeps the model away
  from every other command.
- **Put the limits in qkt, not in the prompt.** `risk.max_order_qty`, `risk.max_order_notional` and
  `risk.price_collar_pct` in `qkt.config.yaml`, halts and instrument specs hold whatever the model asks for.
- **Backtest the agent first.** The same prompt and commands work on a backtest session and on a live one; only
  `session start` changes ([Go live](go-live.md)).
- **Audit afterwards.** `qkt bot session replay runs/llm` reruns the session from its journal and compares every
  fill; `intents.jsonl` holds each order with its exact DSL, its hash, its sender and its fate.
