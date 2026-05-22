# Phase 31 — Telegram alerts

## Summary

Phase 31 adds a first-class notification subsystem so a long-running qkt
daemon can push operational signals — risk halts, position drift, strategy
lifecycle changes, and a daily summary — to a Telegram chat. Opt-in via
`qkt.config.yaml`, credentials via env vars, single chat per daemon.
Trading paths are never blocked by Telegram I/O.

The daily summary doubles as a heartbeat: if you stop seeing it for two
days, something is wrong even when no alerts have fired.

## What's new

- `com.qkt.notify.NotificationEvent` — sealed type with 9 variants:
  `OrderRejected`, `Halted`, `Resumed`, `PositionReconciled`,
  `StrategyStarted`, `StrategyStopped`, `StrategyError`, `DaemonStarted`,
  `DailySummary`.
- `Notifier` interface + `NoopNotifier` default + `TelegramNotifier` impl.
- `TelegramClient` — single-request HTTP wrapper around the Telegram bot
  `sendMessage` endpoint with discriminated `Outcome`.
- `NotificationWorker` — bounded queue + daemon-thread drainer with retry
  (1s/5s/30s backoff) and degraded-mode handling.
- `DailySummaryScheduler` — fires `DailySummary` events at a configured
  UTC time.
- `MessageTemplate` — plain-text renderer (no Markdown, no emoji).
- `EventTranslator` — pure-function bus-event → notification-event mapping.
- `NotifyConfig` + parser, wired through `com.qkt.cli.Config.load(...)`.
- `AtomicNotifierMetrics` — in-memory counters for `sent`, `dropped`,
  `failed`, `rateLimitHits`, `degradedMode`.
- `LiveSession` gained three optional constructor parameters: `notifier`,
  `notifyEvents`, `dailySummaryUtc`. Defaults preserve every existing
  call site — `NoopNotifier` + empty event set + no scheduler.

## Migration from previous phase

No public API changed in a breaking way. `Config` gained a new
`notify: NotifyConfig` field with a `DISABLED` default — existing config
files keep parsing. `LiveSession` ctor gained three new params, all with
defaults; existing callers compile unchanged.

## Usage cookbook

### 1. Make a bot

Talk to `@BotFather` in Telegram, run `/newbot`, follow the prompts. You
get a token in the form `123456:ABC-DEF...`. Then talk to the bot at least
once (send `/start`) so it can message you back, and call the `getUpdates`
endpoint to find your chat id:

```sh
curl "https://api.telegram.org/bot<TOKEN>/getUpdates"
```

Find your numeric chat id in the response.

### 2. Configure qkt

Add to `qkt.config.yaml`:

```yaml
notify:
  telegram:
    enabled: ${TELEGRAM_ENABLED:-false}
    bot_token: ${TELEGRAM_BOT_TOKEN:-}
    chat_id: ${TELEGRAM_CHAT_ID:-}
    daily_summary_utc: "00:00"
    queue_capacity: 100
    events:
      - halted
      - resumed
      - position_reconciled
      - strategy_started
      - strategy_stopped
```

Add to `.env`:

```
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=123456:ABC-DEF...
TELEGRAM_CHAT_ID=987654321
```

### 3. Restart the daemon

```sh
docker compose up -d qkt
```

Within seconds of the qkt service becoming healthy, you should see a
`[INFO] qkt started <strategy-id>` Telegram message per deployed strategy.

### 4. Verify a halt alert end-to-end

Trip a halt rule (for testing, set `risk.max_daily_loss` low and let a
position close at a loss that crosses it). You should receive a
`[CRITICAL] qkt HALTED hedge-straddle` message naming the strategy and
reason within a few seconds of the breach.

### 5. Disable alerts temporarily

Set `TELEGRAM_ENABLED=false` and `docker compose up -d qkt`. The daemon
boots with `NoopNotifier`; trading continues unaffected, no Telegram I/O.

### 6. Mute a noisy event

Comment out a line in `events:` (e.g. `# - position_reconciled`) and
restart. That event type silently bypasses the notifier; the bus event
still flows to other subscribers (state recovery, risk engine, etc.).

## Testing patterns

- Bus-end-to-end: real `EventBus` + capture-list-backed `send` callback.
  See `NotifierLifecycleTest`.
- HTTP-end: MockWebServer (already a qkt test dep). See `TelegramClientTest`.
- Time-driven: `FixedClock` for timestamps in `MessageTemplateTest`; tiny
  `periodMs` for `DailySummaryScheduledTest`.
- Concurrency: `CountDownLatch` and `flush(timeoutMs)` rather than
  `Thread.sleep` of arbitrary durations.

## Known limitations

These are concrete scope cuts in this initial Phase 31 shipment. Each is
listed with the follow-up phase that closes it.

- **`order_rejected` is wired** (Phase 31.1). `BrokerEvent.OrderRejected`
  omits `symbol`/`side`/`quantity`; `LiveSession` recovers them via
  `OrderManager.orderDetailsFor` and the alert names the full order.
- **`strategy_error` fires on deploy failure** (Phase 31.1). `DaemonCommand`
  fires it when a `--load-dir` auto-deploy fails to parse, compile, or start.
  A runtime error in an already-running strategy is not yet covered — there is
  no strategy-level error event on the bus for that case.
  (`daemon_started` is fired from `DaemonCommand` at boot when opted in; the
  daemon builds its notifier via `NotifierFactory.fromConfig`.)
- **Daily summary `equityDeltaPct`, `tradesToday`, `haltsToday` are real**
  (Phase 31.1). `DailyRollingTracker` counts trades and halts and tracks
  equity change since the previous summary; each summary fire reads and
  resets its window. The tracker is in-memory — a daemon restart begins a
  fresh window.
- **Single chat per daemon.** Multi-chat routing is deferred. Config
  schema (`chat_id` as scalar) leaves the door open for a scalar→map
  evolution when a second strategy needs its own chat.
- **One scheduler per LiveSession, not per daemon.** If the daemon hosts
  N strategies, the operator receives N daily-summary messages at the
  configured UTC tick. Consolidating to one daemon-level scheduler is
  Phase 31.1 work — needs the daemon-level wiring above anyway.
- **Outbound only.** No `/status` from phone, no two-way commands.
- **No persistent delivery.** Alerts queued during a daemon restart are
  lost. Alerts are real-time by nature; a 30s-stale alert is misleading.
- **No coalescing.** A storm of 100+ identical events produces 100+
  Telegram messages (subject to the queue cap and rate-limit retries).
  Will be revisited if real storms become a pain.
- **In-memory metrics only.** No `/metrics` HTTP endpoint; read counters
  via the daily summary and the daemon log.

## References

- Spec: `docs/superpowers/specs/2026-05-17-phase31-telegram-alerts-design.md`
- Plan: `docs/superpowers/plans/2026-05-17-phase31-telegram-alerts.md`
- Telegram Bot API `sendMessage`: <https://core.telegram.org/bots/api#sendmessage>
- Telegram bot rate limits: <https://core.telegram.org/bots/faq#my-bot-is-hitting-limits-how-do-i-avoid-this>
