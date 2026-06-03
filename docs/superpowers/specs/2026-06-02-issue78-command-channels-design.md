# Pluggable notify channels + inbound commands + manual halt (#78)

Status: design approved
Issue: #78 (P3, enhancement) — inbound Telegram commands, generalized to a channel-pluggable notify subsystem.

## Problem

Telegram alerts are outbound only — an operator can't check status or halt/resume the
engine from a phone. Beyond that immediate gap, the notify subsystem is **not actually
multi-channel**: the `Notifier` interface is channel-agnostic, but `NotifyConfig` has a
single `telegram` field, `NotifierFactory` only builds `TelegramNotifier`, and the daemon
reads `cfg.notify.telegram.*` directly in ~6 places. Swapping in Slack/Discord today would
mean editing config parsing, the factory, the daemon wiring, and writing the impl — the
abstraction exists at the interface but the pluggability doesn't.

The goal is both: add inbound operator commands (`/status`, `/halt`, `/resume`), and make
the whole notify subsystem channel-pluggable in **both** directions, so adding a channel is
"write one impl, enable it in config" — for alerts *and* commands. Telegram must keep
working exactly as it does today (config shape and behavior unchanged).

## Goals

- Operator can `/status`, `/halt [name]`, `/resume [name]` from a configured channel.
- A manual halt pauses **new** order submission (existing positions keep being managed,
  strategies stay deployed); resume re-enables. Global or per-strategy.
- The same halt/resume capability is reachable from the CLI (`qkt halt`/`qkt resume`) and
  the control-plane HTTP API — not just chat.
- Adding a channel (Slack, Discord, …) = implement one provider + enable a config block.
  No changes to the command core, the control surface, or other channels.
- Telegram's config (`notify.telegram.*`) and runtime behavior are unchanged.

## Non-goals

- Building any channel other than Telegram (the abstraction is the deliverable; impls
  follow as needed).
- Changing the event taxonomy or message templates — `NotificationEvent`, `NotifyEventKind`,
  `MessageTemplate`, `EventTranslator` are already channel-agnostic and stay as-is.
- Flattening positions on halt (that's the more aggressive variant; out of scope —
  `/halt` pauses new orders only).
- Multi-daemon command consumption (one `getUpdates` consumer per bot token — documented
  limitation, mirrors the single-writer state assumption).

## Architecture

Three channel-agnostic layers plus a per-channel provider seam. Nothing in the shared
layers knows about Telegram.

### The seam: `ChannelProvider` + `ChannelRegistry`

A `ChannelProvider` is the single extension point for a channel type. It builds that
channel's outbound `Notifier` and inbound `CommandChannel` from that channel's config:

```
interface ChannelProvider {
    val type: String                                  // "telegram", "slack", …
    fun notifier(config: ChannelConfig): Notifier      // outbound (NoopNotifier if disabled)
    fun commandChannel(config: ChannelConfig, control: DaemonControl): CommandChannel?  // inbound; null if commands off
}
```

`ChannelRegistry` maps `type → provider`. Adding Slack = implement `SlackChannelProvider`
(+ its `Notifier`/`CommandChannel`), register it, add a `notify.slack` config block. The
registry is the only place the set of known channel types lives.

### Outbound (generalized; Telegram behavior identical)

- `Notifier`, `TelegramNotifier`, `TelegramClient`, `NotificationWorker` unchanged.
- `NotifyConfig` becomes a set of per-channel `ChannelConfig`s. The `telegram` block parses
  exactly as today (same keys: `enabled`, `bot_token`, `chat_id`, `events`,
  `daily_summary_utc`, `queue_capacity`) — **prod config untouched.**
- The daemon builds one `Notifier` per enabled channel via the registry and composes them
  into a `CompositeNotifier` that fans `notify(event)` out to all. With only Telegram
  enabled, this is behaviorally identical to today (one notifier).
- The ~6 hardcoded `cfg.notify.telegram.*` references in `DaemonCommand` are replaced by a
  channel-agnostic accessor (e.g. `cfg.notify.enabledChannels()` and per-event routing via
  each channel's `events` set). The daily-summary scheduler is built per channel that
  enables it.

### Inbound (new, channel-agnostic)

- **`DaemonControl`** — the in-process control surface, the one place halt/resume/status
  logic lives:
  ```
  interface DaemonControl {
      fun status(): StatusReport                 // existing per-strategy status, structured
      fun halt(target: Target): ControlResult    // Target = All | Strategy(name)
      fun resume(target: Target): ControlResult
  }
  ```
  Backed by `StrategyRegistry` + the per-session `RiskState`. The HTTP `/halt`·`/resume`
  routes, the CLI, and every `CommandChannel` all call this — no logic duplication.
- **`DaemonCommand`** (sealed): `Status`, `Halt(target)`, `Resume(target)`, `Help`,
  `Unknown(raw)`. Channel-independent.
- **`CommandParser`**: raw text → `DaemonCommand`. `/status`, `/halt`, `/halt <name>`,
  `/resume`, `/resume <name>`, `/help`; anything else → `Unknown` (replies with usage).
  Pure function, unit-testable.
- **`CommandDispatcher`**: `DaemonCommand` → `CommandReply` (text + structured), via
  `DaemonControl`. Pure given a `DaemonControl`, unit-testable with a fake control.
- **`CommandChannel`** (interface): `start()` / `close()`. A channel receives raw input on
  its transport, **authorizes the sender**, runs the text through the shared
  `CommandParser` + `CommandDispatcher`, and **replies on its own transport**. The only
  channel-specific surface.
- **`TelegramCommandChannel`** (impl #1): a daemon thread that long-polls
  `getUpdates(offset, timeout)`, tracks the update offset, keeps only messages whose
  `chat.id` equals the configured `chat_id` (others ignored), and replies via
  `TelegramClient.send`. Reuses the existing `TelegramClient` (extended with `getUpdates`).

### Manual-halt capability (backs `DaemonControl`)

- `LiveSession` gains `halt(reason)` / `resume()` (alongside the existing `flatten()`),
  delegating to its `RiskState`. A halted session's `RiskEngine` rejects new
  `OrderRequest`s (existing path: `isStrategyHalted` → reject) while positions keep being
  managed.
- `StrategyRegistry` gains `halt(target)` / `resume(target)`: `All` iterates `list()` (and
  portfolio children); `Strategy(name)` resolves via `get(name)`.
- Reuses the existing `RiskEvent.Halted`/`Resumed` events — so an operator halt flows
  through the alert path for free (HALTED/RESUMED are existing `NotifyEventKind`s) and
  shows up in status.
- `ControlRoutes` gains `POST /halt[/<name>]` and `POST /resume[/<name>]` delegating to
  `DaemonControl`. `ControlClient` + new `qkt halt [name]` / `qkt resume [name]` CLI
  commands call them.

### Config model

`notify.telegram` is unchanged. A channel block gains one optional inbound key:

```yaml
notify:
  telegram:
    enabled: true            # outbound alerts (as today)
    bot_token: ${...}
    chat_id: ${...}
    events: [order_rejected, halted, resumed, ...]
    commands: false          # NEW — opt-in inbound control surface; default false
```

`commands: false` (default) = alerts-only, today's behavior. `commands: true` = the channel
also runs a `CommandChannel`. Future channels add a sibling block (`notify.slack: {...}`)
recognized by the registry.

`ChannelConfig` carries the common fields every channel has — `type`, `enabled`,
`commands`, `events` — plus a `settings: Map<String, String>` for channel-specific keys
(telegram: `bot_token`, `chat_id`, `daily_summary_utc`, `queue_capacity`; a future Slack:
`webhook_url`, …). Each provider reads its own keys out of `settings`. This mirrors the
existing nested-map `brokers` config (`Map<String, Map<String, String>>`) — so the
`notify.telegram` YAML keys are unchanged; only the internal representation generalizes.
The `type` is the block name (`telegram`), so no `type:` key is added to existing config.

## Data flow

**Inbound command round-trip:** `TelegramCommandChannel` long-polls `getUpdates` → filters
to the configured `chat_id` → `CommandParser` parses the message text → `CommandDispatcher`
runs it against `DaemonControl` → `DaemonControl` halts/resumes the targeted session(s)'
`RiskState` or gathers status → `CommandReply` text → `TelegramClient.send` back to the
chat. Example: operator sends `/halt gold` → dispatcher calls `control.halt(Strategy("gold"))`
→ `registry.get("gold").live.halt("operator")` → risk engine rejects new orders for gold →
reply `"halted: gold"`.

**Outbound fan-out:** an engine event → `CompositeNotifier.notify(event)` → each enabled
channel's `Notifier` (filtered by that channel's `events` set) → its worker → its transport.

## Error handling

- `getUpdates` failure (network, Telegram 5xx) → log + backoff + retry, never crash the
  daemon (mirrors `NotificationWorker`'s retry posture).
- Message from a non-configured `chat.id` → ignored (optionally logged at debug). The bot
  never acts on or replies to unauthorized senders.
- Unknown/garbled command → reply with usage text; no state change.
- `halt`/`resume` of an unknown strategy name → `ControlResult` failure → reply
  `"unknown strategy: X"`; no partial action.
- Halt is idempotent (RiskState.halt/resume already are) — repeated `/halt` is a no-op.

## Testing

- `CommandParser` — table tests: every command + args + malformed input → expected
  `DaemonCommand`. Pure, no I/O.
- `CommandDispatcher` — with a fake `DaemonControl`: each `DaemonCommand` → expected
  `DaemonControl` call + reply text. No channel, no network.
- `DaemonControl` impl — with a real `StrategyRegistry` of test handles: `halt(All)` halts
  every session; `halt(Strategy)` halts one; resume reverses; unknown name fails cleanly.
  Assert the session's risk engine then rejects a new order (real `RiskState`, no mocks).
- `TelegramCommandChannel` — against a stub HTTP server returning canned `getUpdates`
  payloads: authorizes `chat_id` (drops foreign chats), parses+dispatches, posts the reply.
  No real Telegram API.
- Outbound generalization — `CompositeNotifier` fans out to N notifiers; `NotifyConfig`
  round-trips the unchanged `telegram` block; a config with only telegram produces
  identical behavior to today (regression guard).
- Control routes — `POST /halt` / `/resume` (all + by-name) hit `DaemonControl`.

## Backtest invariant / safety

Commands, the command channels, and the manual-halt capability are **daemon-only** — none
are constructed on the backtest/replay path, so the backtest=live determinism invariant is
untouched. Halt drives the same `RiskState` the risk engine already uses; it adds no new
order-path behavior beyond the existing halted-rejection.

## Phasing — one architecture, three shippable steps

1. **Halt capability.** `DaemonControl` + `Target` + `LiveSession.halt/resume` +
   `StrategyRegistry.halt/resume` + `ControlRoutes` `/halt`·`/resume` + `ControlClient` +
   `qkt halt`/`qkt resume` CLI. Channel-free; verifiable over CLI/HTTP.
2. **Outbound → provider model.** `ChannelProvider`/`ChannelRegistry`,
   `ChannelConfig`, `CompositeNotifier`; generalize `NotifyConfig` parsing,
   `NotifierFactory` (now `TelegramChannelProvider`), and the daemon's `notify.telegram`
   references. Telegram is the sole provider; **zero behavior change** (pure refactor
   proving the seam, guarded by the regression test).
3. **Inbound channels.** `DaemonCommand`/`CommandParser`/`CommandDispatcher` +
   `CommandChannel` + `TelegramCommandChannel` + `TelegramClient.getUpdates` + the
   `commands` config key + daemon wiring (start a command channel per commands-on channel,
   graceful shutdown).

Each phase lands, tests, and merges on its own; Telegram works identically throughout.

## References

- Issue #78. Backlog: Tier 8 (platform maturity).
- Outbound prior art: `Notifier`, `NotifierFactory`, `NotifyConfig`, `TelegramNotifier`,
  `NotificationWorker` (`src/main/kotlin/com/qkt/notify/`).
- Control plane: `ControlRoutes`, `ControlClient`, `StrategyRegistry`
  (`src/main/kotlin/com/qkt/cli/daemon/`).
- Risk halt: `RiskState.halt/resume`, `RiskEngine` (`src/main/kotlin/com/qkt/risk/`).
