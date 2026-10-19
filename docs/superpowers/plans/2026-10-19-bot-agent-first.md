# qkt bot agent-first Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One `qkt bot` surface over every configured venue, with sessions that run intents through the DSL engine, live sessions built like a deploy, full journals and a replay.

**Architecture:** One-shot verbs talk to a `BotVenue` (MT5 or VGP gateway) chosen by the broker's `type`. Each session identity is a compiled DSL strategy that compiles and fires ad-hoc DSL actions; the server turns CLI intents into those actions, records their fate and serves it back. Live sessions get their `LiveSession` from the deploy factory; replay re-injects journaled intents into a backtest session.

**Tech Stack:** Kotlin/JVM, JUnit 5 + AssertJ, kotlinx.serialization, OkHttp, JDK HttpServer; Python 3 stdlib for the client example.

**Spec:** `docs/superpowers/specs/2026-10-19-bot-agent-first-design.md`

## Global Constraints

- Branch `feat/bot-agent-first`, one PR to `dev`; Conventional Commit subjects, no body, author `Dickson Anyaele <dicksonanyaele1234@gmail.com>`, no AI references anywhere.
- New files ≤ 200 lines (tests ≤ 220); files in `config/file-size-baseline.txt` may only shrink (`./gradlew updateFileSizeBaseline` after a split).
- Builds serialized: `flock /var/tmp/qkt-work/jvm.lock taskset -c 2-15 nice -n 5 ./gradlew <tasks> --no-daemon`.
- Fail closed: every refusal names the rule and what to do instead; never a silent degrade.
- Existing session routes and response fields keep their names and meaning; additions only.
- One-shot JSON shapes in `docs/reference/bot-cli.md` keep their fields; a gateway `ticket` is a string.
- No deploys to bot hosts; live checks only on the Exness demo (`--key-file /var/tmp/qkt-validation/demo-gateway.key`, never printed) and the testnet/paper gateways on 127.0.0.1:18443/18444/18445 under `~/qkt-testnet-soak/state/testnet.lock` / `bybit.lock`.

## Review Focus

- A gateway one-shot `buy --sl` must refuse before any order is sent (no order in the fake gateway's book) — Task 3.
- An intent for a symbol or timeframe the session did not declare is refused at once, live and backtest — Task 7.
- A session restarted over persisted legs with ON_STOP/EXIT AFTER intents resumes without the "exit-hook definition missing" failure — Task 9.
- Two intents in the same bar fire in arrival order on the same tick and both get fates — Task 6.
- `finish` with open engine-held legs says they are unprotected; `finish --flatten` leaves the book flat — Task 9.

---

### Task 1: GatewayClient close, amend and one quote

**Files:**
- Modify: `src/main/kotlin/com/qkt/connector/gateway/GatewayClient.kt`
- Create: `src/main/kotlin/com/qkt/connector/gateway/GatewayQuoteSnapshot.kt`
- Modify: `src/main/kotlin/com/qkt/connector/gateway/GatewayWire.kt` (add `WireClose`, `WireAmend`)
- Test: `src/test/kotlin/com/qkt/connector/gateway/GatewayClientBotCallsTest.kt`

**Interfaces:**
- Produces: `GatewayClient.closePosition(symbol: String, quantity: BigDecimal?, clientOrderId: String): WireOrder` (`POST /v1/positions/close`, idempotent body); `GatewayClient.amend(clientOrderId: String, quantity: BigDecimal?, limitPrice: BigDecimal?, stopPrice: BigDecimal?): WireOrder` (`PATCH /v1/orders/{id}`); `fun gatewayQuoteOnce(baseUrl: String, apiKey: String, code: String, timeoutMs: Long = 10_000): WireQuote` (opens `/v1/quotes?symbols=code`, returns the first quote with a bid or ask, throws `IllegalStateException("no quote for <code> within 10 s")`).

- [ ] Step 1: tests against `FakeGateway`: `close sends symbol quantity and id`, `close retried with the same id returns the same order`, `amend sends only the given fields`, `quote once returns the first priced quote`, `quote once times out with a named error`.
- [ ] Step 2: run, expect FAIL (unresolved references).
- [ ] Step 3: implement; extend `FakeGateway`/`FakeQuotes` only as far as the tests need.
- [ ] Step 4: run `./gradlew test --tests '*GatewayClientBotCallsTest*'`, expect PASS.
- [ ] Step 5: commit `feat: gateway client close, amend and a single quote`.

### Task 2: BotVenue interface, MT5 implementation

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/venue/BotVenue.kt` (interface + DTOs `BotAccount`, `BotPosition`, `BotPendingOrder`, `BotDeal`, `BotTick`)
- Create: `src/main/kotlin/com/qkt/trade/venue/Mt5BotVenue.kt` (today's `BotGateway` behind the interface)
- Create: `src/main/kotlin/com/qkt/trade/venue/BotVenues.kt` (`BotVenues.forSymbol(cfg, qktSymbol)`, `BotVenues.forBroker(cfg, name?)`: picks by broker `type`, `mt5` → `Mt5BotVenue`, `gateway` → Task 3; unknown prefix lists configured brokers)
- Delete: `src/main/kotlin/com/qkt/trade/BotGateway.kt`; Modify `BotBrokerErrors.kt` (drop the MT5-only refusal)
- Modify: every `cli/bot/*Command.kt` to use `BotVenue`; `BotPlaceResult.ticket` becomes `String`
- Test: existing `BotCommandTest`, `BotEvalCommandTest`, `BotSessionCliTest` stay green; add `src/test/kotlin/com/qkt/trade/venue/BotVenuesTest.kt`

**Interfaces:**
- Produces:
```kotlin
interface BotVenue {
    val broker: String
    fun venueSymbol(qktSymbol: String): String
    fun quoteContext(qktSymbol: String, configAccountCurrency: String): BotQuoteContext
    fun place(order: CompiledBotOrder, clientId: String): BotPlaceResult
    fun close(ticket: String, qktSymbol: String, volume: BigDecimal?, clientId: String): BotPlaceResult
    fun modify(ticket: String, sl: BigDecimal?, tp: BigDecimal?): BotPlaceResult
    fun cancel(ticket: String): Boolean
    fun account(): BotAccount?
    fun positions(): List<BotPosition>?
    fun pendingOrders(): List<BotPendingOrder>?
    fun deals(fromMs: Long, toMs: Long): List<BotDeal>?
    fun tick(qktSymbol: String): BotTick?
    fun bars(qktSymbol: String, window: TimeWindow, count: Int, nowMs: Long): List<Candle>
}
```
  DTO fields are the union of today's JSON fields (`BotPosition(ticket, symbol, side, lots, entry, current, sl, tp, profit, swap, openedAtMs, comment)` etc.). JSON emission prints a ticket that parses as a Long as a number.

- [ ] Step 1: `BotVenuesTest`: `mt5 broker resolves to the mt5 venue`, `unknown prefix names the configured brokers`, `no broker named and two configured asks for --broker`.
- [ ] Step 2: run, FAIL.
- [ ] Step 3: move code; commands unchanged in behavior.
- [ ] Step 4: `./gradlew test --tests 'com.qkt.cli.bot.*' --tests 'com.qkt.trade.*'` PASS.
- [ ] Step 5: commit `refactor: bot verbs over a venue interface`.

### Task 3: Gateway one-shot venue (#1390)

**Files:**
- Create: `src/main/kotlin/com/qkt/connector/gateway/GatewayBotVenue.kt`, `GatewayBotOrders.kt` (compiled order → `WireSubmit`, refusals)
- Test: `src/test/kotlin/com/qkt/connector/gateway/GatewayBotVenueTest.kt`, `src/test/kotlin/com/qkt/cli/bot/BotGatewayCliTest.kt`

**Interfaces:**
- Consumes: Task 1 client calls, Task 2 `BotVenue`.
- Produces: `GatewayBotVenue.open(cfg: Config, account: AccountConfig): GatewayBotVenue` (settings via `GatewaySettings`, identity checked with `health()` against `expected_*` exactly as `GatewayTradingAccount.verify`).

Rules (copy into refusals): `--sl/--tp` → "a gateway venue holds no stop for a position; run a session (`qkt bot session start`) and the engine holds the bracket"; GTD/`--expires` → "VGP v1 orders cannot carry an expiry"; position modify → "a gateway venue holds no stop or target on a position". Place: `client_order_id = "bot-<as>-<ms>-<4 hex>"`, then poll `order(id)` every 200 ms up to 10 s until status ≠ `pending`; `ok` when `working` or `filled`/`cancelled` with filled quantity > 0; `rejected` → `ok=false` with `reject_reason`. Quote context from `/v1/instruments` (contract size, step, min, tick → `digits` from tick scale, currency) and `gatewayQuoteOnce`. Prices snap to tick (half toward entry, as `snapToTick`), quantities floor to step.

- [ ] Step 1: tests on `FakeGateway`: `market buy places and reports the fill`, `limit buy reports working`, `rejected order exits non-zero with the venue reason`, `sl on a gateway is refused and nothing is sent`, `gtd is refused`, `close sends a reduce close for the whole position`, `partial close sends the quantity`, `cancel deletes the order`, `pct of equity sizes from contract size and equity`, `quantity below the minimum is refused`, `perpetual and option symbols map to venue codes`; CLI test `bot buy on a gateway broker prints the json contract`.
- [ ] Step 2: FAIL. Step 3: implement. Step 4: PASS.
- [ ] Step 5: commit `feat: qkt bot one-shot verbs on gateway brokers`.

### Task 4: One-shot pre-trade controls

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/BotPreTrade.kt` (`fun checkBotPreTrade(order: CompiledBotOrder, ctx: BotQuoteContext, cfg: Config)`)
- Modify: `cli/bot/BotTradeCommand.kt`
- Test: `src/test/kotlin/com/qkt/trade/BotPreTradeTest.kt`

Refuse with the config key in the message: quantity > `max_order_qty`; quantity × contract size × entry > `max_order_notional`; when the venue reported no contract size and `max_order_notional` is set below its default, refuse "notional cannot be checked: the venue reported no contract size" (at the default, the check is skipped); limit/stop price farther than `price_collar_frac` × mid from mid.

- [ ] Step 1: tests `qty above max_order_qty is refused`, `notional above max_order_notional is refused`, `limit outside the collar is refused`, `inside every limit passes`.
- [ ] Steps 2-4. Step 5: commit `feat: one-shot bot orders apply the config's pre-trade controls`.

### Task 5: Ad-hoc actions on a compiled DSL strategy

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/AdHocActions.kt`
- Modify: `AstCompiler.kt` (keep `actionCompiler`, `resolver`, `ast.defaults`, read-only aliases, `bindings`/`aggregates` sizes; pass an `AdHocActions` to `CompiledStrategy`), `CompiledStrategy.kt`, `DslCompiledStrategy.kt` (`val adHoc: AdHocActions? get() = null`), `ExitHookDefinition.kt` (catalog thread-safe)
- Test: `src/test/kotlin/com/qkt/dsl/compile/AdHocActionsTest.kt`

**Interfaces:**
```kotlin
class AdHocActions {
    /** Compiles one action as a rule's action would be (defaults, complete bracket, read-only, capability). */
    fun compile(action: ActionAst): CompiledAdHoc   // throws CompileError with a named reason
    /** Fires against the latest closed candle; Skipped when no stream has closed a bar. */
    fun fire(compiled: CompiledAdHoc, ctx: StrategyContext, nowMs: Long, emit: (Signal) -> Unit): AdHocFire
}
sealed interface AdHocFire { data class Fired(val signals: List<Signal>) : AdHocFire; data class Skipped(val reason: String) : AdHocFire }
```
Refusals: binding count grew → "indicators are not available in an intent; compute the value and pass a number"; `STACK_AT`, `OF BOOK` sizing, `OPEN … = OPTIONS` → named.

- [ ] Step 1: tests: `market buy fires one submit on the next fire`, `bracket with stop only is refused like a rule`, `indicator in an intent is refused`, `close emits cancel then a close by leg`, `fire before any closed bar is skipped with a reason`, `compile on another thread while firing is safe` (1000 compiles vs fires, no exception).
- [ ] Steps 2-4. Step 5: commit `feat: compiled strategies run ad-hoc actions`.

### Task 6: Session identities are DSL strategies

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/session/BotIntentStrategy.kt` (queue of `(intentId, CompiledAdHoc)`, drains on tick, reports fates), `BotSessionStreams.kt` (renders the synthetic strategy: alias `s<i>_<tf>` per symbol × tf, one rule `WHEN 1 > 2 THEN BUY s0_... SIZING 0.01`; maps symbol+tf → alias), `BotIntents.kt` (intent text → `ActionAst` with stream rewritten to the session alias; undeclared symbol refused)
- Delete: `BotBridgeStrategy.kt` (+ its test, replaced)
- Modify: `BotRunSession.kt`, `BotSessionServer.kt` (`/intent` uses Task 5; reply adds `intentId`), `cli/bot/BotSessionCommand.kt` (backtest path), `trade/BotDslRenderer.kt` (renders `CLOSE`, `CLOSE_ALL`, `CANCEL`, `CANCEL_ALL`, trailing, `EXIT AFTER`)
- Test: `src/test/kotlin/com/qkt/trade/session/BotIntentSessionTest.kt`

**Interfaces:**
- Produces: `BotRunSession.submit(identity: String, source: String): SubmittedIntent(intentId: String, symbol: String)`; `BotIntentStrategy.fates(): List<IntentFate>`; `data class IntentFate(val intentId: String, val identity: String, val state: String /* queued|fired|skipped|rejected|filled|closed */, val orderIds: List<String>, val reason: String?, val atMs: Long)`.

- [ ] Step 1: tests over the in-memory `Backtest` fixture used by `BotRunSessionTest`: `buy with bracket opens and the stop closes it`, `close flattens the identity only`, `trailing stop follows`, `exit after closes on time`, `pct of equity sizes from the instrument registry`, `stop only bracket is refused at intent`, `two intents in one bar fire in order on the same tick`, `undeclared symbol is refused`.
- [ ] Steps 2-4 (old `BotRunSessionTest`, `BotSessionServerTest`, parity tests updated to the new submit and still green).
- [ ] Step 5: commit `feat: bot session intents run as DSL actions in the engine`.

### Task 7: Fates, events and the new verbs

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/session/IntentLedger.kt` (fates + event queue since last `next`, fed from the engine's order/fill/rejection/halt events for the session's identities), `BotSessionRoutes.kt` (split out of `BotSessionServer.kt`: `/intents`, `/orders`, `/close`, `/cancel`, `/submit`)
- Modify: `BotSessionServer.kt` (`/next` adds `events`; `/positions` rows add `identity, side, entry, unrealized, sl, tp, ticket`; every response adds `"api":2`), `cli/bot/BotCommand.kt` (`SESSION_VERBS` += `close, cancel, orders, intents, submit`), `cli/bot/BotSessionVerbs.kt`, `cli/bot/BotTradeCommand.kt` (`--trail by:<d>|pct:<p>`, `--exit-after <dur>`), new `cli/bot/BotSubmitCommand.kt` (`qkt bot submit '<action>' --as id`)
- Test: `src/test/kotlin/com/qkt/trade/session/IntentLedgerTest.kt`, `src/test/kotlin/com/qkt/cli/bot/BotSessionVerbsTest.kt`

- [ ] Step 1: tests: `risk rejection reaches the intent fate with the rule`, `next carries fills since the previous next`, `close verb in a session closes through the engine`, `submit sends any action text`, `cancel all cancels the identity's pending orders`, `positions rows keep symbol and qty`.
- [ ] Steps 2-4. Step 5: commit `feat: session verbs close cancel orders intents submit with intent fates`.

### Task 8: Several symbols and timeframes

**Files:**
- Modify: `BarHistory.kt` (keyed by series `"<symbol>@<tf>"`, per-series delivered cursor), `BotRunSession.kt` (`next(symbol, tf)`), `BotRunBackend.kt`, `LiveBotRunBackend.kt`, `BotSessionCommand.kt` (`--tf 5m,1h`; base window = smallest, each must be a multiple of it), recorder fed from `CompiledStrategy.observeCandleEvaluations` of the identity strategy (exact hub candles)
- Test: `src/test/kotlin/com/qkt/trade/session/BotSessionSeriesTest.kt`

- [ ] Step 1: tests: `next on two symbols never skips a bar`, `next on 1h returns the hub's hour bar`, `unknown series is refused at once`, `next without tf on a single tf session still works`.
- [ ] Steps 2-4. Step 5: commit `feat: bot sessions over several symbols and timeframes`.

### Task 9: Live sessions built like a deploy

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/daemon/LiveStack.kt` (account open + preflight + identity checks + live-money warning + calendars + registry + insights + persistor + notifier → `StrategyHandle.RealFactory`; moved out of `DaemonCommand`)
- Modify: `DaemonCommand.kt` (uses `LiveStack`), `StrategyHandle.kt` (`RealFactory.createLive(name: String, strategies: List<Pair<String, Strategy>>, symbols: List<String>, candleWindow: TimeWindow?, ignoreMismatches: Boolean): LiveSessionHandle`), `BotSessionCommand.kt` (`startLive` → `LiveStack`; refuses an identity a running daemon deploys; on start recompiles `intents.jsonl` actions without firing; `finish --flatten`)
- Test: `src/test/kotlin/com/qkt/cli/bot/BotLiveSessionTest.kt` (paper broker), existing daemon tests stay green

- [ ] Step 1: tests: `live session passes the configured max order qty`, `identity deployed by the daemon is refused`, `restart over an exit-after intent resumes`, `finish lists open legs and flatten closes them`, `gateway symbol intent compiles in a live session`.
- [ ] Steps 2-4. Step 5: commit `feat: live bot sessions use the deploy pipeline`.

### Task 10: Journals

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/session/IntentJournal.kt` (`intents.jsonl`: `{intentId, identity, source, arrivalSimMs, wallMs, tickSeq, orders, fate}`; fate lines appended as they change), `LiveSessionRecord.kt` (`fills.jsonl`, `summary.json`)
- Modify: `BotSessionServer.kt` reads journal adds bar values; finish copies journals into the report dir
- Test: `src/test/kotlin/com/qkt/trade/session/IntentJournalTest.kt`

- [ ] Step 1: tests: `every intent is journaled with its source and tick sequence`, `reads carry the bar served`, `finish copies intents into the report`.
- [ ] Steps 2-4. Step 5: commit `feat: bot sessions journal every intent in full`.

### Task 11: Replay

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/bot/BotReplayCommand.kt` (`qkt bot session replay <dir> [--out]`), `src/main/kotlin/com/qkt/trade/session/IntentReplay.kt` (re-inject by tick sequence or by arrival time), `ReplayDiff.kt` (`replay-diff.json`)
- Test: `src/test/kotlin/com/qkt/cli/bot/BotReplayTest.kt`

- [ ] Step 1: tests: `replay of a backtest session gives the same trades csv`, `replay diff lists a moved fill`, `replay refuses a record without a window`.
- [ ] Steps 2-4. Step 5: commit `feat: replay a bot session from its journal`.

### Task 12: Parity

**Files:**
- Test: `src/test/kotlin/com/qkt/parity/BotIntentParityTest.kt`

- [ ] Step 1: test: intents (bracket buy, trailing sell, exit-after buy, close, `2 % OF EQUITY`) through a session give the same trades (symbol, side, qty, price, ts, strategyId) as the same actions compiled by `AstCompiler` into a strategy fired by a test hook at the same ticks.
- [ ] Step 2: run, PASS (fix anything it finds). Step 3: commit `test: bot intent parity with compiled strategy actions`.

### Task 13: Any language, docs

**Files:**
- Create: `docs/reference/bot-session-api.md`, `examples/bot/python/qkt_session.py`, `examples/bot/python/agent_loop.py`, `src/test/kotlin/com/qkt/cli/bot/PythonClientTest.kt` (skips when no `python3`)
- Modify: `docs/reference/bot-cli.md`, `examples/bot/SYSTEM_PROMPT.md`, `examples/bot/qkt.config.yaml` (gateway broker example), `docs/backlog.md`, `mkdocs.yml` nav, session `start --json` first line descriptor
- [ ] Step 1: `PythonClientTest`: the client starts nothing itself; the test starts a backtest session, runs `agent_loop.py`, asserts it exits 0 and the report has trades.
- [ ] Steps 2-4. Step 5: commit `docs: bot session http api, python client, agent prompt`.

### Task 14: Verification and PR

- [ ] `./gradlew test ktlintCheck checkFileSize` green.
- [ ] Live: Exness demo (XAUUSD, BTCUSD) one-shot buy+bracket, close, cancel; session buy+bracket, trailing, close, finish. Paper gateway, Deribit testnet (SOL perp, a dated future, one option), Bybit testnet: one-shot buy, close; session buy with bracket, close. Each account flat afterwards.
- [ ] Replay of the live sessions; diffs attached to the PR.
- [ ] Release proofs (`scripts/release-proof/run.sh`) green.
- [ ] PR to dev closing #1390, merge, promotion and attestation.
