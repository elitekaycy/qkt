# Bot Trade CLI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One-shot `qkt bot <verb>` command group: trade (buy/sell/close/modify/cancel) and query (account/positions/orders/quote/bars/eval/history) against MT5 via the gateway, with every trade rendered to canonical DSL, journaled, and egressed to qkt-insights.

**Architecture:** CLI args render to a complete canonical `.qkt` strategy source, parsed by the standard `Dsl.parse`, whose single `ActionAst` is compiled by a new one-shot `BotActionCompiler` into an `OrderRequest`. A `BotGateway` wraps the synchronous `MT5Client` endpoints (place/close/modify/cancel/account/positions/tick) plus `Mt5BarFetcher` for bars. A `BotTrail` appends to `OrderJournal` and offers envelopes to `InsightsSink`. Commands live in `com.qkt.cli.bot`, registered as the `bot` group in `Main.kt`.

**Tech Stack:** Kotlin, existing qkt internals only (Dsl parser, IndicatorRegistry, MT5Client, Mt5BarFetcher, OrderJournal, InsightsSink, Config, Args). No new dependencies.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-13-bot-trade-cli-design.md`.
- ktlint-clean at every commit (`./gradlew ktlintFormat` then `ktlintCheck`); files aim ≤150 lines.
- KDoc on every new public type and externally-callable member.
- Money is `BigDecimal` via `Money.CONTEXT`; comparisons via `compareTo`/`signum()`.
- No mocking frameworks — anonymous objects and a stub `HttpServer` for gateway tests.
- Commit per task, conventional commits, no AI attribution.
- Fail-closed: engine-managed shapes (trailing, stack, latch, scale-out, OCO, ON FILL) reject with a message pointing at `qkt deploy`.
- Exit codes: 0 success, 1 user/venue error, 2 arg error (`ExitCodes`).

---

### Task 1: Canonical DSL renderer (`BotIntent` → strategy source)

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/BotIntent.kt`
- Create: `src/main/kotlin/com/qkt/trade/BotDslRenderer.kt`
- Test: `src/test/kotlin/com/qkt/trade/BotDslRendererTest.kt`

**Interfaces:**
- Produces: `data class BotIntent(side: Side, lots: BigDecimal?, sizing: String?, qktSymbol: String, limit: BigDecimal?, stop: BigDecimal?, stopLimit: Pair<BigDecimal, BigDecimal>?, sl: ExitSpec?, tp: ExitSpec?, tif: String?, expiresAt: String?)`; `sealed interface ExitSpec { At(price), By(distance), Pct(percent), Rr(multiplier) }` with `ExitSpec.parse("by:30")` etc.
- Produces: `fun renderBotStrategy(intent: BotIntent): String` — deterministic canonical source.

Canonical shape (exact):

```
STRATEGY bot VERSION 1
SYMBOLS ( x = "EXNESS:XAUUSD" TF 1m )
WHEN true THEN BUY x SIZING 0.5 BRACKET { STOP LOSS BY 30, TAKE PROFIT RR 2 }
```

Order-type clause when `--limit/--stop/--stop-limit` present: `ORDER_TYPE = LIMIT 2680`; TIF clause `TIF DAY` / `TIF GTD <expr>`. Verify exact keyword spellings against `Parser.kt`/`TokenKind.kt` and `examples/*.qkt` before writing the renderer; the test locks them by round-tripping through `Dsl.parse` (Task 2 asserts parseability; this task asserts exact text).

- [ ] Step 1: Write failing table-driven tests: market buy, bracket by/rr, limit sell with TIF DAY, pct sl, at prices, `--sizing "1% RISK"` passthrough. Assert exact rendered text.
- [ ] Step 2: Run `./gradlew test --tests '*BotDslRendererTest*'` — expect compile failure/red.
- [ ] Step 3: Implement `ExitSpec.parse` (`at`/bare number → At, `by:` → By, `pct:` → Pct, `rr:` → Rr; Rr rejected for `--sl`) and the renderer.
- [ ] Step 4: Tests green; ktlintFormat + ktlintCheck clean.
- [ ] Step 5: Commit `feat(strategy): add bot intent canonical dsl renderer` (scope: pick `dsl` if `com.qkt.trade` maps poorly; `feat: ...` unscoped is acceptable).

### Task 2: Parse + extract (`BotAction`)

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/BotAction.kt`
- Test: `src/test/kotlin/com/qkt/trade/BotActionTest.kt`

**Interfaces:**
- Produces: `data class BotAction(val source: String, val sha256: String, val qktSymbol: String, val action: ActionAst, val opts: ActionOpts)`; `fun parseBotStrategy(source: String): BotAction` — runs `Dsl.parse`, fails on parse errors, requires exactly one `WhenThen` rule whose action is `Buy`/`Sell`, resolves the stream alias to the declared qktSymbol.

- [ ] Step 1: Failing tests: renderer output parses and extracts side/symbol/opts; malformed source surfaces `ParseError` messages; multi-rule source rejected.
- [ ] Step 2: Red run.
- [ ] Step 3: Implement using `Dsl.parse` + `StrategyAst` fields; sha256 via `java.security.MessageDigest`.
- [ ] Step 4: Green + ktlint clean.
- [ ] Step 5: Commit `feat: parse canonical bot strategy into bot action`.

### Task 3: One-shot compiler (`BotActionCompiler`)

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/BotActionCompiler.kt`
- Create: `src/main/kotlin/com/qkt/trade/BotQuoteContext.kt`
- Test: `src/test/kotlin/com/qkt/trade/BotActionCompilerTest.kt`

**Interfaces:**
- Consumes: `BotAction` (Task 2).
- Produces: `data class BotQuoteContext(bid: BigDecimal, ask: BigDecimal, equity: BigDecimal?, balance: BigDecimal?, contractSize: BigDecimal?, accountCurrency: String, quoteCurrency: String?, volumeMin: BigDecimal?, volumeStep: BigDecimal?, volumeMax: BigDecimal?, digits: Int?)`.
- Produces: `fun compileBotAction(bot: BotAction, ctx: BotQuoteContext, id: String, timestamp: Long, strategyId: String): OrderRequest`.

Rules:
- Entry price: BUY sizes/prices against `ask`, SELL against `bid` (sided-price invariant). Pending entries use their literal price.
- Sizing: `SizeQty(NumLit)` → lots; `SizePctEquity/Balance` → `value * frac / (entry * contractSize)`; `SizeRiskFrac/Abs` → `amount / (stopDistance * contractSize)` (stop distance resolved from the SL spec). Percentage/risk forms `require(quoteCurrency == accountCurrency)` and require contractSize — else fail with actionable message. Non-`NumLit` expressions rejected.
- Volume quantization: floor to `volumeStep`, clamp `[volumeMin, volumeMax]`; below-min after floor → reject.
- Exits: replicate `ChildPriceResolver.applyDistance` sign math (SL: BUY→entry−d, SELL→entry+d; TP mirrored); PCT → `entry * pct/100`; RR → `mult * stopDistance` from SL; AT → literal. Round to `digits` when known.
- Shapes: no exits → `Market`/`Limit`/`Stop`/`StopLimit`; any exit → `Bracket(entry=…, takeProfit, stopLoss=StopLossSpec.Fixed)`. TP-only bracket: MT5 attaches TP without SL, so allow `Bracket` only when both present; TP-or-SL-only ride as fields on the wire request (Task 4 handles attach) — represent single-exit market/pending as the base request plus `BotExits(sl, tp)` carried alongside: `data class CompiledBotOrder(val request: OrderRequest, val sl: BigDecimal?, val tp: BigDecimal?)` (absolute venue prices, already resolved). Bracket construction inside the compiler produces the same `CompiledBotOrder` — keep `OrderRequest.Bracket` for both-present so journal/insights see the standard shape.
- TIF: GTC default, DAY, GTD + `expiresAt` epoch ms (ISO-8601 parsed at CLI layer); IOC/FOK rejected (MT5 pending semantics differ) unless trivially supported.
- Fail-closed rejects: `TrailingBy`, `TrailingPct`, `ChildArmedTrail`, `stack`, `stackAts`, `oco`, `onFill` non-empty.

- [ ] Step 1: Failing tests: market buy lots; bracket by/by sign math both sides; rr tp from sl distance; pct; % equity sizing; risk sizing; quantization floor/clamp/reject; fail-closed list; currency-mismatch reject.
- [ ] Step 2: Red run.
- [ ] Step 3: Implement (split files if >150 lines: sizing helper in `BotSizing.kt` if needed).
- [ ] Step 4: Green + ktlint.
- [ ] Step 5: Commit `feat: compile bot actions into order requests one-shot`.

### Task 4: Venue gateway (`BotGateway`)

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/BotGateway.kt`
- Create: `src/main/kotlin/com/qkt/trade/BotGatewayResult.kt`
- Test: `src/test/kotlin/com/qkt/trade/BotGatewayTest.kt` (stub `com.sun.net.httpserver.HttpServer` gateway)

**Interfaces:**
- Consumes: `CompiledBotOrder` (Task 3).
- Produces: `class BotGateway(profile: MT5BrokerProfile, client: MT5Client)` with:
  - `companion fun forSymbol(cfg: Config, qktSymbol: String): BotGateway` — resolves broker prefix against `MT5BrokerProfileLoader().load(...)` exactly as `DaemonCommand` does; unknown prefix fails closed.
  - `fun quoteContext(qktSymbol: String, cfg: Config): BotQuoteContext` — `getTick` + `getSymbolInfo` + `getAccount` (null reads → error, never zero).
  - `fun place(order: CompiledBotOrder, comment: String): BotPlaceResult` — map to `MT5OrderRequest` (type from side+entry shape: BUY/SELL/BUY_LIMIT/SELL_LIMIT/BUY_STOP/SELL_STOP/stop-limit; `sl`/`tp` attach; expiration+typeTime for GTD/DAY; broker symbol via `MT5Symbol(profile.symbolPolicy).toBroker`; comment ≤31 chars) → `client.placeOrder`; success iff retcode ∈ {10008, 10009, 10010}; result carries ticket, deal, fill price, retcode, errorMessage.
  - `fun close(qktSymbol/ticket, partialLots?): …` via `client.closePosition`; `fun modify(ticket, sl?, tp?)` via `client.modifyPosition`; `fun cancel(ticket)` via `client.cancelOrder`.
  - Reads: `account()`, `positions()`, `pendingOrders()`, `deals(from,to)`, `tick(qktSymbol)`, `bars(qktSymbol, tf, count)` (via `Mt5BarFetcher.fetchRange`, timeframe map like `FetchCommand`).
- Before writing, read `MT5OrderTranslator.kt` — if its OrderRequest→MT5OrderRequest mapping is constructible without `MT5Broker`, reuse it instead of re-mapping (one-translation-boundary invariant); only hand-map if it is entangled.

- [ ] Step 1: Failing stub-server tests: place market buy with sl/tp → request body asserted (symbol mapped, volume, sl/tp, comment) and retcode 10009 parsed; reject path surfaces errorMessage; close/modify/cancel round-trips; account/positions parse; null-read → error.
- [ ] Step 2: Red run.
- [ ] Step 3: Implement.
- [ ] Step 4: Green + ktlint.
- [ ] Step 5: Commit `feat(broker): add one-shot bot gateway over mt5 client`.

### Task 5: Trail (journal + insights egress)

**Files:**
- Create: `src/main/kotlin/com/qkt/trade/BotTrail.kt`
- Test: `src/test/kotlin/com/qkt/trade/BotTrailTest.kt`

**Interfaces:**
- Consumes: `BotAction`, `CompiledBotOrder`, `BotPlaceResult`.
- Produces: `class BotTrail(stateRoot: Path, insights: InsightsConfig, clock: Clock) : AutoCloseable` with `fun recordCommand(asName: String, action: BotAction, argv: List<String>)` (journal kind `bot.command`; insights envelope type `bot.command` payload: canonical source, sha256, `BuildInfo.VERSION`, argv), `fun recordResult(asName: String, order: CompiledBotOrder, result: BotPlaceResult)` (journal + insights `order.submitted` / `order.accepted` / `order.rejected` shaped like `InsightsTranslate` order payloads — read `InsightsTranslate.kt` order envelope fields first and match them so qkt-insights ingests without changes), `close()` flushes sink.
- Journal root: `StateDir.resolve().stateRoot/"bot"` → `OrderJournal(root, clock)`, strategyId = `--as` name. Insights disabled → journal only.

- [ ] Step 1: Failing tests: journal lines written under `bot/<as>/journal-*.jsonl` with expected kinds/fields (temp dir); envelope payload fields asserted via a capturing stub sink URL (stub HttpServer) or by extracting envelope-build into a pure function and asserting on it (prefer the pure function).
- [ ] Step 2: Red.
- [ ] Step 3: Implement; envelope construction as pure `fun botCommandEnvelope(...): InsightsEnvelope` for testability.
- [ ] Step 4: Green + ktlint.
- [ ] Step 5: Commit `feat: journal and egress bot trade commands`.

### Task 6: CLI command group

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/bot/BotCommand.kt` (dispatcher + help)
- Create: `src/main/kotlin/com/qkt/cli/bot/BotTradeCommands.kt` (buy/sell/close/modify/cancel)
- Create: `src/main/kotlin/com/qkt/cli/bot/BotQueryCommands.kt` (account/positions/orders/quote/bars/history)
- Create: `src/main/kotlin/com/qkt/cli/bot/BotJson.kt` (minimal JSON emitter — match existing CLI JSON style; check how `--json` is emitted elsewhere, e.g. BacktestCommand, and reuse that helper if one exists)
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt` (add `"bot" -> BotCommand(args).run()` + help section)
- Test: `src/test/kotlin/com/qkt/cli/bot/BotCommandTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Flow per trade verb: parse args → `BotIntent` → render → `parseBotStrategy` → `BotGateway.forSymbol` → `quoteContext` → `compileBotAction` → (`--dry-run`: print canonical source + resolved order, exit 0) → `trail.recordCommand` → `gateway.place` → `trail.recordResult` → print human or `--json` result → exit code (accepted → 0, rejected → 1).
- `--json` object (stable schema, document in Task 8): `{ok, ticket, deal, fillPrice, retcode, error, canonicalDsl, sha256, symbol, side, lots, sl, tp, as, qktVersion}`.
- Query verbs print venue truth; `--json` arrays/objects with documented fields; read failure → `{ok:false,error}` exit 1.

- [ ] Step 1: Failing tests for arg parsing → `BotIntent` (positional lots + symbol, flags), unknown verb → ARG_ERROR, `--dry-run` prints canonical source without network (inject a fake gateway factory; keep `BotCommand` constructible with a gateway-provider function for testability).
- [ ] Step 2: Red.
- [ ] Step 3: Implement; keep each file ≤150 lines.
- [ ] Step 4: Green + ktlint. Manual smoke: `./gradlew installDist` (or the repo's run path) `qkt bot buy --help`, `qkt bot buy 0.5 EXNESS:XAUUSD --dry-run` with a test config.
- [ ] Step 5: Commit `feat(cli): add qkt bot command group`.

### Task 7: `bot eval` (indicators over bars)

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/bot/BotEvalCommand.kt`
- Test: `src/test/kotlin/com/qkt/cli/bot/BotEvalCommandTest.kt`

**Interfaces:**
- `qkt bot eval "ema(21)" EXNESS:XAUUSD --tf 1h [--count 500] [--json]`. Parse `name(args…)` (regex, numeric literal args), `IndicatorRegistry.create(name.uppercase(), args)`; NUMERIC_SERIES → feed closes, CANDLE_SERIES → feed candles; `seriesCount > 1` → reject ("two-series indicators not supported one-shot"). Output value + isReady + warmupBars + bar count + last bar time. Not-ready → `{ok:false, error:"insufficient bars"}`.
- Bars via `BotGateway.bars`.

- [ ] Step 1: Failing tests: expression parse (valid/invalid), eval over synthetic candles for EMA and ATR (deterministic expected values via the indicator itself is circular — assert against hand-computed 3-bar SMA instead), insufficient-bars path.
- [ ] Step 2: Red. Step 3: Implement. Step 4: Green + ktlint.
- [ ] Step 5: Commit `feat(cli): add bot eval indicator command`.

### Task 8: Docs + ai-overlay example

**Files:**
- Create: `docs/bot-cli.md` (cookbook: every verb, flags, JSON schemas, exit codes, fail-closed list, risk divergences, prereqs)
- Create: `examples/ai-overlay/SYSTEM_PROMPT.md` (paste-ready AI overlay prompt: command surface, schemas, always `--json`, check account before sizing, dry-run first)
- Create: `examples/ai-overlay/qkt.config.yaml` (starter config: one MT5 broker, insights block commented)
- Modify: `Main.kt` help text already done in Task 6 — verify it mentions `bot`.
- Modify: `docs/superpowers/specs/2026-07-13-bot-trade-cli-design.md` — status → Implemented, note deltas.

- [ ] Step 1: Write docs; every JSON schema field matches Task 6/7 emitters exactly (copy from code).
- [ ] Step 2: `mkdocs build --strict` if docs site config includes new page (check `mkdocs.yml`; add nav entry if needed).
- [ ] Step 3: Commit `docs: bot cli cookbook and ai overlay template`.

### Task 9: Verification sweep

- [ ] Step 1: `./gradlew ktlintCheck test` full run — green, no new warnings in output.
- [ ] Step 2: `./gradlew build` — BUILD SUCCESSFUL.
- [ ] Step 3: Regression scan: `git diff origin/dev --stat` — confirm zero modifications to engine/live-session/backtest files beyond `Main.kt` registration (the feature must be purely additive).
- [ ] Step 4: End-to-end against stub gateway: run the built CLI with a config pointing at a local stub for one buy → assert journal file + insights journal contents.
- [ ] Step 5: Push branch, open PR to `dev` with the repo template.
