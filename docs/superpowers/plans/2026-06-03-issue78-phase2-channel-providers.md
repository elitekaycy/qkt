# Phase 2 — Outbound → channel-provider model (#78)

Status: ready to implement
Spec: docs/superpowers/specs/2026-06-02-issue78-command-channels-design.md (phase 2)
Branch: `issue78-channel-providers` (off `dev`)

## Goal

Make the outbound notify subsystem channel-pluggable behind a `ChannelProvider` seam, with
Telegram as the sole provider. **Zero behavior change** — the `notify.telegram` YAML and
runtime behavior are byte-for-byte what they are today; this phase only generalizes the
internal wiring so adding Slack/Discord later is "one provider + one config block."

## Invariants (must hold throughout)

- Prod `notify.telegram` YAML parses identically — same keys, same meaning. Prod config file untouched.
- With only Telegram enabled, runtime is behaviorally identical to today (one notifier, same
  events, same daily summary). Guarded by a regression test.
- Backtest/replay path untouched — none of these types are constructed off the daemon path.
- No change to `Notifier`, `TelegramNotifier`, `TelegramClient`, `NotificationWorker`,
  `NotificationEvent`, `NotifyEventKind`, `MessageTemplate`, `EventTranslator`, the
  `DailySummaryScheduler` class, or the `LiveSession`/`StrategyHandle` internals. We change
  *what is passed in*, not those types.

## Design decisions (resolved)

1. **Config shape.** `NotifyConfig` becomes `data class NotifyConfig(val channels: List<ChannelConfig>)`
   with helpers `enabledChannels()` and `enabledEventKinds()` (union of every enabled channel's
   `events`). `ChannelConfig(type, enabled, commands, events, settings)`:
   - common fields: `type: String` (= the YAML block name, e.g. `"telegram"`), `enabled: Boolean`,
     `commands: Boolean` (parsed now, **consumed in Phase 3**), `events: Set<NotifyEventKind>`,
     `dailySummaryUtc: String` (`""` disables).
   - `settings: Map<String, String>` — everything channel-specific. Telegram: `bot_token`,
     `chat_id`, `queue_capacity`. Mirrors the nested-map `brokers` config (`Config.parseNested`).
   - **Spec deviation, deliberate:** `daily_summary_utc` is promoted from a telegram setting to a
     common `ChannelConfig` field, because "build a daily-summary scheduler per channel that
     enables it" is a channel-agnostic daemon concern — the daemon must read it without knowing
     the channel type. The YAML key stays `notify.telegram.daily_summary_utc`; only the internal
     home changes. `queue_capacity` stays in `settings` (a `NotificationWorker` impl detail).
2. **The seam.** `ChannelProvider { val type: String; fun notifier(config: ChannelConfig): Notifier }`.
   Phase 2 carries **only** `notifier()`; Phase 3 adds `commandChannel(config, control)`.
   `ChannelRegistry` maps `type -> provider`; it is the one place the known channel types live.
3. **Telegram provider.** `TelegramChannelProvider : ChannelProvider`, `type = "telegram"`.
   `notifier()` is the current `NotifierFactory.fromConfig` logic, reading `bot_token`/`chat_id`/
   `queue_capacity` out of `config.settings`; returns `NoopNotifier` when disabled or creds blank.
   `NotifierFactory` is replaced by this provider (not kept alongside).
4. **Fan-out + per-channel routing.**
   - `CompositeNotifier(notifiers: List<Notifier>) : Notifier` — dumb fan-out; `notify` to all,
     `close` all, `metrics` = first non-null (preserves the single-notifier metrics surface the
     control plane reads).
   - `FilteringNotifier(events: Set<NotifyEventKind>, delegate: Notifier) : Notifier` — forwards
     only events whose kind is in `events`; `DailySummary` (no kind) always passes.
   - `kindOf(event: NotificationEvent): NotifyEventKind?` — exhaustive `when` over the sealed type
     (null for `DailySummary`). Lives beside `FilteringNotifier`. Keeps `NotificationEvent` pure.
   - Daemon composes: each enabled channel → `provider.notifier(cfg)` wrapped in
     `FilteringNotifier(channel.events)` → `CompositeNotifier`. With one channel the filter passes
     exactly what the session already emits → identical behavior.
5. **Session events.** Sessions still take `notifyEvents: Set<NotifyEventKind>`; the daemon now
   passes `cfg.notify.enabledEventKinds()` (union). One channel → union = that channel's events →
   unchanged. `LiveSession`/`StrategyHandle` are not modified.
6. **Daily summary per channel.** For each enabled channel with non-blank `dailySummaryUtc`, build
   a `DailySummaryScheduler` pointed at *that channel's* notifier (the unwrapped provider notifier,
   so the summary isn't dropped by an events filter). One channel → one scheduler → identical.

## Daemon wiring changes (DaemonCommand.kt)

Replace the 6 `cfg.notify.telegram.*` reads:
- `:64` `NotifierFactory.fromConfig(cfg.notify.telegram)` → build per-channel notifiers via the
  registry, wrap in `FilteringNotifier`, fold into a `CompositeNotifier` (the `notifier` val).
- `:162`, `:178` `cfg.notify.telegram.events` → `cfg.notify.enabledEventKinds()`.
- `:207`, `:220` `... in cfg.notify.telegram.events` → `... in cfg.notify.enabledEventKinds()`
  (the union gate; `FilteringNotifier` does the per-channel routing).
- `:233-245` single daily scheduler → a `List<DailySummaryScheduler>` built per enabled channel
  with a non-blank `dailySummaryUtc`, each closed in both shutdown paths (`:259`, `:276`).

## Tasks (TDD — red, then green; one subagent per task, fresh context)

**Sequencing rationale:** reshaping `NotifyConfig`'s type breaks its consumers
(`NotifierFactory`, `DaemonCommand`) the instant it changes — so a config-first task can't
commit green. Instead: build the entire new seam **additively** (Tasks A + B, nothing removed,
every commit compiles), then **flip** the daemon onto it and delete the old in one atomic
commit (Task C). Old `NotifierFactory`/`TelegramConfig` keep working until Task C deletes them;
brief transient duplication of the ~6-line Telegram-build logic is the cost, removed in Task C.

### Task A — Seam + Telegram provider (additive)
- New files: `ChannelConfig` (data class: `type`, `enabled`, `commands`, `events`,
  `dailySummaryUtc`, `settings: Map<String,String>`), `ChannelProvider` (interface:
  `val type: String`, `fun notifier(config: ChannelConfig): Notifier`), `ChannelRegistry`
  (`register`/`get(type)`/known types; pre-registers Telegram), `TelegramChannelProvider`
  (`type="telegram"`; the current `NotifierFactory.fromConfig` logic, reading `bot_token`/
  `chat_id` from `config.settings`, `queue_capacity` from settings defaulting to 100; returns
  `NoopNotifier` when `!enabled` or either cred blank).
- Nothing existing is modified or deleted. `NotifierFactory` stays (still used by the daemon).
- Tests: `TelegramChannelProviderTest` (enabled+creds → `TelegramNotifier`; disabled / blank
  token / blank chat → `NoopNotifier`; missing `queue_capacity` → defaults 100).
  `ChannelRegistryTest` (`get("telegram")` resolves; unknown type → null).

### Task B — Notifier composition (additive)
- New files: `CompositeNotifier(notifiers: List<Notifier>)` (fan-out `notify`, close-all,
  `metrics` = first non-null), `FilteringNotifier(events, delegate)` + `kindOf(event)`
  (exhaustive `when`, null for `DailySummary`; filter drops out-of-set kinds, passes
  `DailySummary`).
- Nothing existing is modified or deleted.
- Tests: `CompositeNotifierTest` (fans to N recording notifiers; close closes all; metrics =
  first non-null). `FilteringNotifierTest` (in-set forwarded, out-of-set dropped, `DailySummary`
  passes). `kindOf` via a small table or the filter test.

### Task C — Flip: generalize `NotifyConfig`, rewire daemon, delete old, regression guard
- Reshape `NotifyConfig` to `channels: List<ChannelConfig>` + `enabledChannels()` +
  `enabledEventKinds()` (union) + `DISABLED = NotifyConfig(emptyList())`. `parse`: iterate the
  `notify` map's entries; each `key -> sub-map` → `ChannelConfig(type=key, ...)`, common fields
  parsed, remaining keys into `settings`; unknown event names warn-and-drop (unchanged). The
  `telegram` YAML parses byte-identically (`bot_token`/`chat_id`/`queue_capacity` → settings).
- Rewire `DaemonCommand.kt` (the 6 reads listed above): build per-channel notifiers via the
  registry, wrap each in `FilteringNotifier(channel.events)`, fold into a `CompositeNotifier`;
  pass `enabledEventKinds()` as session `notifyEvents`; gate STRATEGY_ERROR/DAEMON_STARTED on
  `enabledEventKinds()`; build a `DailySummaryScheduler` per enabled channel with non-blank
  `dailySummaryUtc` (pointed at that channel's *unwrapped* notifier), close them all in both
  shutdown paths.
- Delete `NotifierFactory` + `TelegramConfig`. Rewrite `NotifyConfigTest` to the new API.
- Regression guard test: a config with only the telegram block enabled produces — through the
  same registry+composite construction the daemon uses — one enabled channel, a non-Noop
  Telegram-backed notifier inside the composite, `enabledEventKinds()` == the telegram events,
  and a daily scheduler iff `daily_summary_utc` set; assert no second notifier.
- `./gradlew ktlintFormat` + targeted test runs green.

## Out of scope (Phase 3)

`commandChannel()` on `ChannelProvider`, `DaemonCommand`/`CommandParser`/`CommandDispatcher`,
`CommandChannel`/`TelegramCommandChannel`, `TelegramClient.getUpdates`, the `commands` config key's
*consumption*, and command-channel daemon wiring. The `commands` field is parsed here but unused.
