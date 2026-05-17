# Phase 31 — Telegram alerts (design)

**Goal.** Add a first-class notification subsystem to qkt that pushes critical
trading events and a daily summary to a Telegram chat. Operators get
phone-visible signals for the failure modes that today require SSHing in to
notice: rejected orders, risk-halts, position drift, strategy lifecycle
changes, and the silent-failure case (daemon wedged but container "healthy").

The subsystem is opt-in via config, env-driven for credentials, and engineered
so a Telegram outage can never block or slow down a trading path.

## Why now

qkt-prod is deployed and self-restarting, but every operational signal lives
inside `docker compose logs qkt`. There is no out-of-band notification of
anything — not a rejected order, not a circuit-breaker halt, not a daemon
crash-restart loop. The 19:55 UTC verification window in this same session
made it concrete: the only way I know whether the hedge-straddle placement
fired correctly is to SSH and grep. That doesn't scale to multiple strategies
or to leaving qkt running unattended.

This phase closes that gap with the smallest realistic surface: alerts-out
only, Telegram only, single chat, daily heartbeat.

## Scope summary

In:

- New `com.qkt.notify` package, peer with `com.qkt.risk`.
- `Notifier` interface; `NoopNotifier` + `TelegramNotifier` impls.
- EventBus subscription for the 8 in-scope event types + 1 scheduler-driven
  daily summary.
- Bounded async queue + dedicated worker thread for HTTP delivery.
- Config block in `qkt.config.yaml`; credentials via env vars.
- LiveSession wiring; backtest does not construct the notifier.
- Test coverage across translation, templates, queue, worker, lifecycle, and
  the daily-summary scheduler.
- Phase changelog `docs/phases/phase-31-telegram-alerts.md`.
- qkt-prod updates: `.env.example`, `config/qkt.config.yaml`, `docs/DEPLOY.md`.

Out (deferred or won't-build):

- Inbound bot commands (`/status` from phone) — separate subsystem.
- Multi-chat routing — YAGNI at one strategy; schema leaves the door open.
- Event coalescing — re-evaluate after a month of prod data.
- Per-strategy alert overrides, web UI, KMS-backed token storage.
- Email/SMS/Slack/Discord — `Notifier` interface is the extension point;
  ship Telegram alone in this phase.
- Persistent delivery across restarts — alerts are real-time by nature.
- Sub-daily heartbeat — daily summary doubles as heartbeat by design.

## Architecture

```
                ┌─────────────┐
                │  EventBus   │  (already global)
                └──────┬──────┘
                       │ subscribe
                       ▼
              ┌────────────────────┐
              │  Notifier          │
              │  ─ translate       │  (BrokerEvent/RiskEvent → NotificationEvent)
              │  ─ filter (opt-in) │
              │  ─ enqueue (O(1))  │
              └──────┬─────────────┘
                     │ non-blocking offer, bounded ~100
                     ▼
          ┌──────────────────────────┐
          │  NotificationWorker      │  daemon thread
          │  ─ drain queue           │
          │  ─ format template       │
          │  ─ POST to Telegram      │
          │  ─ retry + backoff       │
          └──────┬───────────────────┘
                 │ HTTP POST
                 ▼
        api.telegram.org/bot<TOKEN>/sendMessage
```

Key properties:

- **One bus subscription point.** `Notifier` registers handlers with the
  existing EventBus the same way `MT5StateRecovery` does. Trading-path
  producers don't know it exists.
- **Translation at the boundary.** Raw `BrokerEvent` / `RiskEvent` are
  translated into `NotificationEvent` inside the subscriber. This lets us
  add scheduler-driven events (`DailySummary`) and lifecycle events
  (`StrategyStarted`, `DaemonStarted`) without overloading the bus types.
- **`Notifier.notify()` is O(1), non-blocking, never throws.** This is the
  contract that protects trading. Any implementation that violates it is a
  bug.
- **The worker thread is separate from the engine thread.** Telegram 429s,
  network stalls, or a slow JSON serialization step cannot reach into the
  tick-publishing path. The worker is also a daemon thread so a stuck HTTP
  call cannot hold the JVM open on shutdown.
- **LiveSession-only.** `Backtest` does not wire a notifier. Tests use
  `NoopNotifier` unless they're specifically asserting notify behavior.

## Internal types

`NotificationEvent` is a sealed interface in `com.qkt.notify`:

```kotlin
sealed interface NotificationEvent {
    val timestamp: Long
    val severity: Severity              // INFO, WARN, CRITICAL

    data class OrderRejected(
        val strategyId: String,
        val symbol: String,
        val side: Side,
        val quantity: BigDecimal,
        val reason: String,
        override val timestamp: Long,
    ) : NotificationEvent { override val severity = Severity.CRITICAL }

    data class Halted(
        val strategyId: String?,        // null = global
        val reason: String,
        override val timestamp: Long,
    ) : NotificationEvent { override val severity = Severity.CRITICAL }

    data class Resumed(
        val strategyId: String?,
        override val timestamp: Long,
    ) : NotificationEvent { override val severity = Severity.INFO }

    data class PositionReconciled(
        val strategyId: String,
        val symbol: String,
        val oldQty: BigDecimal?,
        val newQty: BigDecimal,
        val reason: String,
        override val timestamp: Long,
    ) : NotificationEvent { override val severity = Severity.WARN }

    data class StrategyStarted(
        val strategyId: String,
        override val timestamp: Long,
    ) : NotificationEvent { override val severity = Severity.INFO }

    data class StrategyStopped(
        val strategyId: String,
        val flatten: Boolean,
        override val timestamp: Long,
    ) : NotificationEvent { override val severity = Severity.INFO }

    data class StrategyError(
        val strategyId: String,
        val message: String,
        override val timestamp: Long,
    ) : NotificationEvent { override val severity = Severity.CRITICAL }

    data class DaemonStarted(
        val version: String,
        val strategies: List<String>,
        override val timestamp: Long,
    ) : NotificationEvent { override val severity = Severity.INFO }

    data class DailySummary(
        val asOfUtc: String,
        val strategies: List<StrategySummary>,
        override val timestamp: Long,
    ) : NotificationEvent { override val severity = Severity.INFO }
}

data class StrategySummary(
    val strategyId: String,
    val equity: BigDecimal,
    val equityDeltaPct: BigDecimal,     // from prior day
    val realizedToday: BigDecimal,
    val unrealized: BigDecimal,
    val tradesToday: Int,
    val haltsToday: Int,
    val positionsSummary: String,       // "flat" or "long 0.24 EXNESS:XAUUSD"
)
```

## Configuration

`qkt.config.yaml` (committed in qkt-prod):

```yaml
notify:
  telegram:
    enabled: ${TELEGRAM_ENABLED:-false}
    bot_token: ${TELEGRAM_BOT_TOKEN:-}
    chat_id: ${TELEGRAM_CHAT_ID:-}
    daily_summary_utc: "00:00"        # HH:MM; "" disables
    queue_capacity: 100
    events:
      - order_rejected
      - halted
      - resumed
      - position_reconciled
      - strategy_started
      - strategy_stopped
      - strategy_error
      - daemon_started
```

`.env.example` (committed in qkt-prod):

```
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
```

Behavior at LiveSession startup:

| State | Notifier | Log line |
|---|---|---|
| `notify` block absent | `NoopNotifier` | none |
| `enabled: false` | `NoopNotifier` | INFO `Telegram notifications disabled by config` |
| `enabled: true` + creds missing | `NoopNotifier` | WARN `Telegram enabled but bot_token/chat_id unresolved — running without alerts` |
| `enabled: true` + creds resolved | `TelegramNotifier` | INFO `Telegram notifications active (chat <chat_id>, <N> event types)` |

Choices encoded in this schema:

- **`events:` is an opt-in list, not a verbosity level.** Adding a 9th
  `BrokerEvent` variant to the bus does not silently start firing alerts.
  Unknown event names in the list emit a WARN at startup but do not fail
  the load.
- **Single `chat_id` (scalar).** YAGNI for one strategy. If a second
  strategy needs its own chat, evolve the schema from scalar to map; that
  is a one-PR change later.
- **No `parse_mode`.** Plain text. Avoids the Markdown/HTML escaping
  surface area; severity tags `[CRITICAL]`/`[WARN]`/`[INFO]` are searchable
  in any chat client.

## Message templates

All templates are plain text, no Markdown, no emoji (per `CLAUDE.md`).

```
OrderRejected:
  [CRITICAL] qkt order rejected
  strategy: hedge-straddle
  EXNESS:XAUUSD BUY 0.24 lots
  reason: 10015 invalid price (price too far from market)
  19:55:03 UTC

Halted (global):
  [CRITICAL] qkt HALTED (global)
  reason: MaxDrawdown 12.3% > 10.0%
  19:55:14 UTC

Halted (strategy):
  [CRITICAL] qkt HALTED hedge-straddle
  reason: MaxStrategyDailyLoss -$215 < -$200
  19:55:14 UTC

Resumed:
  [INFO] qkt resumed hedge-straddle
  19:55:14 UTC

PositionReconciled:
  [WARN] qkt position drift hedge-straddle
  EXNESS:XAUUSD qty: 0.24 -> 0.00
  reason: external close detected by venue poller
  19:55:14 UTC

StrategyStarted:
  [INFO] qkt started hedge-straddle
  19:55:14 UTC

StrategyStopped:
  [INFO] qkt stopped hedge-straddle (flatten=false)
  19:55:14 UTC

StrategyError:
  [CRITICAL] qkt strategy error hedge-straddle
  message: contractSize lookup failed for EXNESS:XAUUSDXXX
  19:55:14 UTC

DaemonStarted:
  [INFO] qkt 0.27.0 started
  strategies: hedge-straddle
  19:55:14 UTC

DailySummary:
  [INFO] qkt daily summary 2026-05-17
  hedge-straddle:
    equity: $10,154.38 (-0.5% from yesterday)
    realized today: +$23.40
    unrealized: -$0.00
    trades: 14
    halts: 0
    positions: flat
  uptime: 23h 59m
```

Timestamps render `HH:mm:ss UTC` using the injected `Clock`. Monetary fields
use `BigDecimal` formatting at 2 dp with `$` prefix. Quantity fields use the
strategy's lot scale.

## Failure modes

The notifier is non-critical infrastructure. Every failure mode below leaves
trading untouched.

| Failure | Detection | Behavior | Operator signal |
|---|---|---|---|
| Telegram API 429 (rate-limit) | HTTP response | Worker honors `Retry-After`, sleeps, retries same message. Queue keeps accepting. | WARN log per retry; `rateLimitHits` counter. |
| Telegram API 5xx / network error | HTTP response or 5s timeout | Worker retries with exponential backoff 1s, 5s, 30s. After 3 failures, drop. | WARN log per retry; ERROR log on drop; `failed` counter. |
| Token revoked / chat unknown | HTTP 401 / 403 | Drop message; flip notifier to degraded mode (logs only, no further HTTP). | ERROR log `Telegram notifications disabled until restart — auth/chat invalid`. |
| Worker thread crashes | Uncaught exception | Re-spawn once. Second crash → degraded mode. | ERROR log with stack trace. |
| Queue full (storm) | `offer()` returns false | Drop newest message, FIFO preserved. | WARN log first time per minute; `dropped` counter. |
| Construction fails at startup | Startup exception | LiveSession continues with `NoopNotifier`. | ERROR log at startup. |
| EventBus handler throws | Subscribe boundary | Catch, log, drop. | WARN log per event. |
| Daily summary scheduler skips | Clock drift / long GC | Next tick fires normally; missed summary is gone. | None — acceptable at daily granularity. |

`NotifierMetrics` exposes in-memory counters:

```kotlin
interface NotifierMetrics {
    val sent: Long
    val dropped: Long
    val failed: Long
    val rateLimitHits: Long
    val degradedMode: Boolean
}
```

Not persisted, not yet exposed over HTTP. Read via logs (each significant
event is logged with a `[notify]` MDC tag) and via the daily summary as an
implicit signal — two missed daily summaries means something is wrong even
if `degradedMode == false`.

Shutdown: `LiveSession.stop()` calls a synchronous final-shutdown message
with a 2-second timeout, then aborts. Trading-side shutdown never hangs
waiting on Telegram.

## Testing strategy

Per qkt skill §11: no mock frameworks. Anonymous interface impls, capture
lists, JUnit 5 + AssertJ. MockWebServer (OkHttp's own test fixture, already
a test dep in qkt — see `MT5ClientTest`) is the HTTP boundary.

| Test class | Proves |
|---|---|
| `NotificationEventTest` | Each variant constructs; severity and timestamp accessible; sealed exhaustiveness. |
| `EventTranslatorTest` | `BrokerEvent.OrderRejected` → `NotificationEvent.OrderRejected` field-for-field. Same for Halted, Resumed, PositionReconciled. |
| `MessageTemplateTest` | Each `NotificationEvent` variant produces the documented plain-text format. Deterministic via `FixedClock`. |
| `BoundedQueueTest` | `offer()` returns true under capacity, false at capacity. FIFO. Concurrent producer + single drainer loses no messages under capacity. |
| `TelegramNotifierTest` | Via MockWebServer: POST to `/bot<token>/sendMessage`, JSON body has `chat_id` and `text`, honors 429 `Retry-After`, retries 5xx with backoff, gives up after 3, flips degraded mode on 401/403. |
| `NotificationWorkerTest` | Drains queue in order, survives `TelegramClient` exception with retry, flips degraded mode after 401, increments metrics. |
| `NotifierLifecycleTest` | End-to-end: real `EventBus`, real `Notifier`, capture-list-backed `TelegramClient`. Publish `BrokerEvent.OrderRejected`, assert it lands as a Telegram payload. Same for Halted, Resumed, PositionReconciled. |
| `DailySummaryScheduledTest` | `FixedClock` advances; assert summary enqueued exactly at configured UTC tick, not before, not double-fired. |
| `NotifierConfigTest` | YAML parsing: absent → Noop. `enabled: false` → Noop. `enabled: true` + missing creds → Noop + WARN. `enabled: true` + creds → TelegramNotifier. Unknown event in `events:` → WARN + skip. |
| `NoopNotifierTest` | Every call is a no-op, never throws, metrics always zero. |

Not tested in this phase (documented as limitations):

- Live Telegram API (CI has no real bot token).
- Production-rate concurrent storms (modeled via bounded-queue unit test
  rather than load test).
- Bot token rotation (operator action, no qkt code path).

## Acceptance criteria

1. Fresh `docker compose up -d` of qkt-prod with valid `TELEGRAM_*` env vars
   produces one `DaemonStarted` Telegram message within 60 seconds of the
   qkt service becoming healthy.
2. `qkt stop hedge-straddle` from an `exec` produces a `StrategyStopped`
   Telegram message.
3. With invalid creds, daemon still starts, hedge-straddle still runs, and
   an ERROR log line confirms degraded mode on the first publish attempt.
4. Daily summary arrives at the configured UTC time ±1 minute, with equity
   and trade count cross-checked against `qkt status hedge-straddle`.
5. All ten test classes from the testing strategy pass; CI green.
6. `docs/phases/phase-31-telegram-alerts.md` covers all six sections
   required by the qkt phase-changelog convention.

## File layout

New production files:

- `src/main/kotlin/com/qkt/notify/Notifier.kt`
- `src/main/kotlin/com/qkt/notify/NoopNotifier.kt`
- `src/main/kotlin/com/qkt/notify/TelegramNotifier.kt`
- `src/main/kotlin/com/qkt/notify/NotificationEvent.kt`
- `src/main/kotlin/com/qkt/notify/EventTranslator.kt`
- `src/main/kotlin/com/qkt/notify/MessageTemplate.kt`
- `src/main/kotlin/com/qkt/notify/NotificationWorker.kt`
- `src/main/kotlin/com/qkt/notify/NotifierMetrics.kt`
- `src/main/kotlin/com/qkt/notify/TelegramClient.kt`
- `src/main/kotlin/com/qkt/notify/DailySummaryScheduler.kt`
- `src/main/kotlin/com/qkt/notify/NotifyConfig.kt`

Modified production files:

- `src/main/kotlin/com/qkt/app/LiveSession.kt` — construct notifier from
  config, subscribe to bus, fire lifecycle events, wire scheduler.
- `src/main/kotlin/com/qkt/cli/Config.kt` — add `notify: NotifyConfig?`
  field to the `Config` data class and `notify:` block parsing to
  `Config.load(path)`.

Test files mirror production layout under `src/test/kotlin/com/qkt/notify/`.

qkt-prod files modified:

- `config/qkt.config.yaml` — add `notify:` block.
- `.env.example` — add three env vars.
- `docs/DEPLOY.md` — add a "Telegram bot setup" section.

New docs in qkt:

- `docs/phases/phase-31-telegram-alerts.md` — changelog with cookbook,
  written when the implementation phase finishes (per qkt skill §6).

## References

- Strategy of subscribing to existing bus events without disturbing
  trading paths: `src/main/kotlin/com/qkt/broker/mt5/MT5StateRecovery.kt`.
- HTTP-out test pattern with MockWebServer:
  `src/test/kotlin/com/qkt/broker/mt5/MT5ClientTest.kt`.
- Bus event surface this notifier subscribes to:
  `src/main/kotlin/com/qkt/events/BrokerEvent.kt`,
  `src/main/kotlin/com/qkt/risk/RiskState.kt`.
- Telegram Bot API `sendMessage`: <https://core.telegram.org/bots/api#sendmessage>.
- Telegram Bot API rate limits: <https://core.telegram.org/bots/faq#my-bot-is-hitting-limits-how-do-i-avoid-this>.
