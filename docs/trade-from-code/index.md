# Trade from your code

Anything that can run a command or send an HTTP request can trade through qkt: an AI agent, a Python script, a
service in any language, or you at a shell. It decides; qkt executes. Every order it places goes through the same
engine as a `.qkt` strategy, so everything you have set up for strategies applies to it as well:

| You set up once | What it does to every order from your code |
|---|---|
| `qkt.config.yaml` | which broker it trades on, the account currency, Insights |
| `instruments.yaml` | contract size, digits, minimum lot, costs |
| `risk:` limits and guardrails | an order over a limit is refused, with the rule that refused it |
| The fill model | the same fills a backtest of a `.qkt` file gets, on the same data |
| The journal | every intent, its exact DSL, its hash, who sent it and what became of it |
| Replay | the session runs again and gives the same fills |

```text
your agent · script · app        any language: JSON over the CLI or local HTTP
        │
        ▼
qkt     qkt.config.yaml · instruments.yaml · risk limits · guardrails
        │  the engine pipeline every .qkt strategy runs on
        ▼
        backtest over your data, or your broker (MT5, futures and options through the venue gateway)
        │
        ▼
        journal ─▶ report ─▶ Insights ─▶ qkt bot session replay: the same fills
```

## Two ways in

**One-shot commands** do one thing and exit: `qkt bot buy`, `sell`, `close`, `modify`, `cancel`, and reads such as
`quote`, `bars`, `positions` and `account`. They need no setup beyond a broker in `qkt.config.yaml`.

**A session** is the engine itself, held open for your code: a backtest over your data, or live on your accounts.
Your code reads the next bar with `next`, places orders, and sees what became of them. The engine holds your stops,
targets and trailing exits; the config's risk rules apply to every order; and the whole session can be replayed.
Use a session for anything you manage over time, and backtest it before it trades live: the commands are the same.

## Start here

- [Quickstart](quickstart.md): a session over the sample data, driven from the shell and from Python, then replayed.
  Ten minutes, offline.
- [Connect an AI agent](ai-agent.md): the system prompt and a loop for any model with shell access.
- [From any language](any-language.md): the session's HTTP API, and the Python client in `examples/bot/python`.
- [Go live](go-live.md): the same commands on a demo account, and replaying a live session.

The full command list is the [bot CLI reference](../reference/bot-cli.md); the HTTP contract is the
[session API reference](../reference/bot-session-api.md).
