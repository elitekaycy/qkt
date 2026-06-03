# Phase 3 — Inbound operator commands over Telegram (#78)

Status: ready to implement
Spec: docs/superpowers/specs/2026-06-02-issue78-command-channels-design.md (phase 3)
Branch: `issue78-inbound-commands` (off `dev`)

## Goal

Let an operator drive the daemon from chat: `/status`, `/halt [name]`, `/resume [name]`, `/help`.
Telegram long-polls `getUpdates`, authorizes the sender by `chat_id`, runs the text through a
channel-agnostic parser + dispatcher backed by the existing `DaemonControl`, and replies on the
same chat. Reuses the Phase-1 halt/resume capability and the Phase-2 `commands` config flag.

## Decision: inbound seam placement (chosen with elitekaycy)

**Direct in `com.qkt.cli.daemon` (YAGNI).** The command abstractions and `TelegramCommandChannel`
live in `com.qkt.cli.daemon` (next to `DaemonControl`); the daemon builds the Telegram command
channel directly for any enabled channel with `commands: true` and `type == "telegram"`. We do
**not** add `commandChannel()` to the notify-side `ChannelProvider` (that would point
`notify -> cli.daemon` and cycle). A pluggable inbound provider is deferred until a real second
inbound channel exists — at which point two impls will shape it correctly. `ChannelProvider` and
all Phase-1/Phase-2 code are untouched.

## Invariants / safety

- **Daemon-only.** None of these types are constructed on the backtest/replay path — the
  backtest=live determinism invariant is untouched. Halt drives the same `RiskState` the risk
  engine already uses; no new order-path behavior.
- `commands: false` (default) = today's behavior exactly (no command channel started).
- A halt from chat pauses **new** order submission only (existing positions keep being managed) —
  identical to `qkt halt` / the HTTP `/halt` route (all three go through `DaemonControl`).
- Unauthorized chat (id != configured `chat_id`) is ignored — the bot never acts on or replies
  to it. Network/HTTP/parse failures back off and retry; they never crash the daemon.

## New/changed surface

### `com.qkt.cli.daemon` (command core — all new unless noted)
- `DaemonControl` (MODIFY): add `fun status(): StatusReport`. `RegistryDaemonControl.status()` =
  `StatusReport(registry.list().map { StrategyStatus(it.name, it.live.running, it.live.isHalted()) })`.
- `StatusReport(val strategies: List<StrategyStatus>)`, `StrategyStatus(val name: String,
  val running: Boolean, val halted: Boolean)`.
- `ControlCommand` (sealed): `Status`, `Halt(val target: Target)`, `Resume(val target: Target)`,
  `Help`, `Unknown(val raw: String)`. (Named `ControlCommand`, NOT `DaemonCommand` — that name is
  the existing daemon entrypoint class `com.qkt.cli.DaemonCommand`.)
- `CommandParser` (object, pure): `parse(raw): ControlCommand`. First whitespace token, lowercased,
  selects: `/status`->Status, `/halt`->Halt(targetOf(arg)), `/resume`->Resume(targetOf(arg)),
  `/help`->Help, anything else->Unknown(raw). `targetOf(arg)` = `Target.All` when no arg, else
  `Target.Strategy(arg)` (arg kept verbatim — strategy names are matched exactly by the registry).
- `CommandReply(val text: String)`.
- `CommandDispatcher(private val control: DaemonControl)`: `dispatch(cmd): CommandReply`.
  - Status -> list rows: `"strategies (N):\n- gold: running, halted\n- silver: running"`, or
    `"no strategies deployed"` when empty.
  - Halt/Resume -> `control.halt/resume(target)`; if `result.unknown` non-empty ->
    `"unknown strategy: <first>"`; else `"halted: <affected joined>"` / `"resumed: ..."` (or
    `"halted: all (N)"` for `Target.All`).
  - Help / Unknown -> usage text (`/status`, `/halt [name]`, `/resume [name]`, `/help`); Unknown
    prefixes `"unknown command: <raw>"`.
- `CommandChannel` (interface): `fun start()`, `fun close()`.
- `TelegramCommandChannel(client: TelegramClient, chatId: String, dispatcher: CommandDispatcher) :
  CommandChannel` — a daemon thread that long-polls. Plus a companion
  `from(config: ChannelConfig, control: DaemonControl): TelegramCommandChannel?` that reads
  `bot_token`/`chat_id` from `config.settings`, builds a `TelegramClient` with the Telegram API
  base url and an OkHttpClient whose readTimeout exceeds the poll timeout, and returns null
  (with a `[WARN]`) when either credential is blank. Depends on `notify.TelegramClient` —
  `cli.daemon -> notify` is the existing direction.

### `com.qkt.app.LiveSessionHandle` (MODIFY)
- Add `fun isHalted(): Boolean = false` (default; mirrors the Phase-1 `halt`/`resume` no-op
  defaults so non-daemon impls are unaffected). In `LiveSession`'s returned handle:
  `override fun isHalted(): Boolean = riskState.halted` (the session-global flag an operator halt
  sets — one session == one strategy in the daemon, so this is that strategy's halted state).

### `com.qkt.notify.TelegramClient` (MODIFY — additive)
- Add `data class TelegramUpdate(val updateId: Long, val chatId: Long?, val text: String?)` and
  `sealed interface UpdatesOutcome { data class Received(val updates: List<TelegramUpdate>);
  data object Failed }`.
- Add `fun getUpdates(offset: Long, timeoutSeconds: Int): UpdatesOutcome` — GET
  `$baseUrl/bot$botToken/getUpdates?offset=$offset&timeout=$timeoutSeconds`. Parse the body with
  **kotlinx.serialization.json** low-level element API (the established pattern in
  `marketdata/live/tv/TradingView*` — `Json.parseToJsonElement(...).jsonObject`): `ok != true` ->
  `Failed`; else map `result[]` to `TelegramUpdate(update_id, message.chat.id, message.text)`,
  carrying `update_id` for EVERY entry (so the caller can advance the offset past non-text /
  foreign / non-message updates), with `chatId`/`text` null when absent. Non-200 or `IOException`
  or parse error -> `Failed`.

### `com.qkt.cli.DaemonCommand` (MODIFY — wiring)
- After `registry` is built (~:181), construct one `val daemonControl = RegistryDaemonControl(registry)`
  and `val commandChannels: List<CommandChannel> = cfg.notify.enabledChannels()
  .filter { it.commands && it.type == "telegram" }.mapNotNull { TelegramCommandChannel.from(it, daemonControl) }`.
- Start them after `plane.start()` (~:208): `commandChannels.forEach { it.start() }`.
- Close them in BOTH shutdown paths (the shutdown Thread ~:274 and the post-latch cleanup ~:277):
  `commandChannels.forEach { runCatching { it.close() } }`.

## The long-poll loop (TelegramCommandChannel)

Daemon thread (`isDaemon = true`, so it never blocks JVM exit). `var offset = 0L`; while running:
`getUpdates(offset, POLL_TIMEOUT_SECONDS=25)` ->
- `Received(updates)`: for each `u`: `offset = u.updateId + 1` (always, before filtering); skip if
  `u.text == null`; skip if `u.chatId?.toString() != chatId` (authorize); else
  `client.send(dispatcher.dispatch(CommandParser.parse(u.text)).text)`.
- `Failed`: sleep `BACKOFF_MS=5000` (interruptible), then retry.
`close()`: `running = false`; interrupt + `join(2000)`. The dedicated command `TelegramClient` uses
readTimeout = POLL_TIMEOUT_SECONDS + buffer so the long-poll returns; the daemon thread is
abandoned harmlessly if mid-poll at shutdown.

## Tasks (TDD — red then green; one subagent per task, fresh context)

### Task 1 — `status()` on DaemonControl + `isHalted()` on the handle (additive)
- Add `StatusReport`/`StrategyStatus`; add `status()` to `DaemonControl` + `RegistryDaemonControl`.
  Add `isHalted()` default to `LiveSessionHandle` + the `LiveSession` override.
- Tests: extend `DaemonControlTest` (the Phase-1 fake-registry harness) — `status()` reports
  name+running+halted per handle (one running, one halted); empty registry -> empty report. A
  `LiveSession` test asserting `isHalted()` flips true after `halt(...)` and false after `resume()`
  (reuse the Phase-1 `LiveSessionHaltTest` blocking-feed harness if practical, else a focused test).

### Task 2 — Command core: parser + dispatcher + types (additive, pure)
- Add `ControlCommand`, `CommandReply`, `CommandParser`, `CommandDispatcher`, `CommandChannel`.
- Tests: `CommandParserTest` (table: every command + arg + malformed/empty -> expected
  `ControlCommand`). `CommandDispatcherTest` with a hand-written fake `DaemonControl` (records
  halt/resume targets, returns canned `StatusReport`/`ControlResult`): each `ControlCommand` ->
  expected control call + reply text, including unknown-strategy and empty-status paths. No I/O.

### Task 3 — `TelegramClient.getUpdates` (additive)
- Add `TelegramUpdate`, `UpdatesOutcome`, `getUpdates`.
- Tests: `TelegramClientGetUpdatesTest` (MockWebServer, the `TelegramClientTest` pattern): parses a
  multi-update payload (update_id/chat.id/text); request path+query carry `offset`/`timeout`;
  `result: []` -> `Received(empty)`; `ok:false` -> `Failed`; 500 -> `Failed`; malformed body ->
  `Failed`; an update with no `message.text` still yields a `TelegramUpdate` carrying its
  `update_id` (null text).

### Task 4 — `TelegramCommandChannel` + daemon wiring (integration)
- Add `TelegramCommandChannel` (loop above) + the `from(config, control)` factory; wire it into
  `DaemonCommand` (construct `daemonControl`, build/start channels, close in both shutdown paths).
- Tests: `TelegramCommandChannelTest` (MockWebServer): a `/status` from the configured chat ->
  the channel POSTs a `sendMessage` reply (assert the path is `sendMessage` and body carries the
  status text); a message from a FOREIGN chat -> NO `sendMessage` (assert the next request is
  another `getUpdates` whose `offset` advanced past the foreign update). Enqueue a trailing empty
  `getUpdates` so the loop parks; `close()` in teardown.
- `./gradlew --offline compileKotlin compileTestKotlin`, the affected tests, then ktlintFormat +
  ktlintCheck — green.

## Out of scope

A pluggable inbound provider seam (deferred per the decision above). Rich `/status` (equity,
positions) — `/status` reports name/running/halted only; full status stays on the observability
server and the CLI. Commands other than status/halt/resume/help. Multi-daemon `getUpdates`
consumption (one consumer per bot token — documented single-writer limitation).
