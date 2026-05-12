# Phase 26b — MT5 native pending family · Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `Stop`, `Limit`, `StopLimit`, `StandaloneOCO`, `TrailingStop` route natively through `MT5OrderTranslator`. Hedge-straddle (Phase 26a) becomes live-runnable on Exness/ICMarkets/FTMO/Pepperstone.

**Architecture:** Sealed `MT5Translation` return type (Single | Composite) to handle OCO's two-leg shape. Native MT5 wire types for each shape. OrderManager's sibling cancel-on-fill (already wired in Phase 26a) propagates through MT5 cancel events. Capability declaration at the broker level.

**Tech stack:** Kotlin, JUnit 5 + AssertJ. No new dependencies.

---

### Task 1 — Read MT5 broker surface

**Files:**
- Read: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`
- Read: `src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt`
- Read: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`
- Read: `src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt`
- Read: `src/main/kotlin/com/qkt/broker/OrderTypeCapability.kt`
- Read: `src/test/kotlin/com/qkt/broker/mt5/MT5OrderTranslatorTest.kt`
- Read: `src/test/kotlin/com/qkt/broker/mt5/FakeMt5Client.kt` if exists

- [ ] **Step 1:** Confirm `MT5OrderRequest` shape — fields like `type`, `price`, `sl`, `tp`, `stoplimit`, `sl_distance`, etc.
- [ ] **Step 2:** Confirm `MT5BrokerProfile` exposes `point`, `pointsPerPip`, `magic`, `deviationPoints`.
- [ ] **Step 3:** Find existing `MT5Broker.submit` rejection at line 57 and understand the engine-managed fallback comment.
- [ ] **Step 4:** Locate `FakeMt5Client` — pattern for integration tests.
- [ ] **Step 5:** Confirm `OrderTypeCapability` enum values today.

---

### Task 2 — Add `MT5Translation` sealed type

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`

- [ ] **Step 1:** Add a sealed type for the translator return:

```kotlin
sealed interface MT5Translation {
    data class Single(val request: MT5OrderRequest) : MT5Translation
    data class Composite(
        val requests: List<MT5OrderRequest>,
        val groupId: String,
    ) : MT5Translation
}
```

- [ ] **Step 2:** Change `translate` return type to `MT5Translation`. Existing Market and Bracket cases wrap in `Single`.
- [ ] **Step 3:** Update call sites of `translate` in `MT5Broker.kt` to handle `Single`/`Composite`.
- [ ] **Step 4:** Existing translator tests should still pass — verify.

```bash
./gradlew test --tests 'com.qkt.broker.mt5.*' --no-daemon
```

---

### Task 3 — `translateStop` and `translateLimit`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5OrderTranslatorTest.kt`

- [ ] **Step 1:** Add `translateStop(req: OrderRequest.Stop): MT5OrderRequest`.

```kotlin
private fun translateStop(req: OrderRequest.Stop): MT5OrderRequest =
    MT5OrderRequest(
        symbol = symbol.toBroker(req.symbol),
        volume = req.quantity,
        type = if (req.side == Side.BUY) "BUY_STOP" else "SELL_STOP",
        price = req.stopPrice,
        sl = null,
        tp = null,
        deviation = profile.deviationPoints,
        magic = profile.magic,
        comment = req.id,
    )
```

- [ ] **Step 2:** Add `translateLimit(req: OrderRequest.Limit): MT5OrderRequest` parallel form, `type = "BUY_LIMIT"|"SELL_LIMIT"`, `price = req.limitPrice`.

- [ ] **Step 3:** Wire both into the `translate` dispatch.

- [ ] **Step 4:** Add tests:

```kotlin
@Test
fun `translateStop produces BUY_STOP wire request`() {
    val req = OrderRequest.Stop(
        id = "s1", symbol = "XAUUSD", side = Side.BUY, quantity = Money.of("0.20"),
        stopPrice = Money.of("2010"), timeInForce = TimeInForce.GTC, timestamp = 0L,
    )
    val out = translator.translate(req) as MT5Translation.Single
    assertThat(out.request.type).isEqualTo("BUY_STOP")
    assertThat(out.request.price).isEqualByComparingTo("2010")
}

@Test
fun `translateLimit produces SELL_LIMIT wire request`() { /* … */ }
```

- [ ] **Step 5:** Run tests, confirm green, commit.

---

### Task 4 — `translateStopLimit`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`
- Confirm MT5WireTypes supports `stoplimit` field.
- Test: same file as Task 3

- [ ] **Step 1:** If `MT5OrderRequest` doesn't have `stoplimit` / `stopLimitPrice` field, add it. Otherwise use existing.
- [ ] **Step 2:** Add `translateStopLimit(req: OrderRequest.StopLimit): MT5OrderRequest`:

```kotlin
type = if (req.side == Side.BUY) "BUY_STOP_LIMIT" else "SELL_STOP_LIMIT"
price = req.stopPrice
stoplimit = req.limitPrice  // new field
```

- [ ] **Step 3:** Add to dispatch.
- [ ] **Step 4:** Test BUY and SELL StopLimit translations.
- [ ] **Step 5:** Run, commit.

---

### Task 5 — `translateTrailingStop`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`
- Test: same file

MT5 uses `sl_distance` in points for trailing stops. `MT5BrokerProfile.point` converts qkt-side prices to MT5 points.

- [ ] **Step 1:** Add `translateTrailingStop(req: OrderRequest.TrailingStop)`. If `req.trailMode == ABSOLUTE`, divide `req.trailAmount` by `profile.point` to get MT5 points. If `PERCENT`, multiply current price by frac then divide by point.

```kotlin
private fun translateTrailingStop(req: OrderRequest.TrailingStop): MT5OrderRequest {
    val distancePoints = when (req.trailMode) {
        TrailMode.ABSOLUTE -> req.trailAmount.divide(profile.point, MathContext.DECIMAL64).toLong()
        TrailMode.PERCENT -> {
            // Caller must supply current price; for now use the limit (entry approximation)
            // and document that PERCENT trailing requires the OrderManager's last-price seed.
            error("PERCENT trail mode requires runtime price context; not yet supported in translator")
        }
    }
    return MT5OrderRequest(
        type = if (req.side == Side.BUY) "BUY" else "SELL",  // trailing attaches to a market order
        ...
        sl_distance = distancePoints,
    )
}
```

- [ ] **Step 2:** PERCENT mode may require a different code path (gather current price from `MarketPriceTracker` first). For Phase 26b, support ABSOLUTE mode; document PERCENT as Phase 26c if it needs more thought.
- [ ] **Step 3:** Tests for ABSOLUTE trailing on BUY and SELL.
- [ ] **Step 4:** Run, commit.

---

### Task 6 — `translateStandaloneOCO`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`
- Test: same file

- [ ] **Step 1:** Translate leg1 and leg2 via the existing dispatch (recursive call). Each leg is an atomic order (`Stop`, `Limit`, etc).
- [ ] **Step 2:** Tag both with the same `groupId` via `comment` field — MT5 native has no "group" concept, so we encode in comment.

```kotlin
private fun translateStandaloneOCO(req: OrderRequest.StandaloneOCO): MT5Translation.Composite {
    val groupTag = "oco:${req.id}"
    val leg1 = (translate(req.leg1) as MT5Translation.Single).request
    val leg2 = (translate(req.leg2) as MT5Translation.Single).request
    return MT5Translation.Composite(
        requests = listOf(
            leg1.copy(comment = "$groupTag/${leg1.comment}"),
            leg2.copy(comment = "$groupTag/${leg2.comment}"),
        ),
        groupId = req.id,
    )
}
```

- [ ] **Step 3:** Tests for OCO with two Stop legs (the hedge-straddle case) and OCO with mixed Stop+Limit legs.

```kotlin
@Test
fun `translateStandaloneOCO produces Composite with both legs tagged`() {
    val req = OrderRequest.StandaloneOCO(
        id = "oco1",
        symbol = "XAUUSD",
        side = Side.BUY,  // arbitrary
        quantity = Money.of("0.20"),
        leg1 = stopBuy(id = "buy1", price = "2010"),
        leg2 = stopSell(id = "sell1", price = "1990"),
        ...
    )
    val out = translator.translate(req) as MT5Translation.Composite
    assertThat(out.requests).hasSize(2)
    assertThat(out.requests[0].comment).contains("oco:oco1")
    assertThat(out.requests[1].comment).contains("oco:oco1")
}
```

- [ ] **Step 4:** Run, commit.

---

### Task 7 — `MT5Broker.submit` expansion

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`

- [ ] **Step 1:** Remove or expand the `if (request !is OrderRequest.Market && ...)` guard.
- [ ] **Step 2:** Switch on `MT5Translation` return:

```kotlin
override fun submit(request: OrderRequest): SubmitAck {
    val translation = translator.translate(request)
    return when (translation) {
        is MT5Translation.Single -> {
            client.send(translation.request, ...)
            SubmitAck(request.id, request.id, accepted = true)
        }
        is MT5Translation.Composite -> {
            translation.requests.forEach { client.send(it, ...) }
            SubmitAck(request.id, request.id, accepted = true)
        }
    }
}
```

- [ ] **Step 3:** Verify the `client.send` signature handles per-leg events back to qkt.
- [ ] **Step 4:** Build + run.

---

### Task 8 — Capability surface

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/OrderTypeCapability.kt`
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` — declare supported set

- [ ] **Step 1:** Add `STOP`, `LIMIT`, `STOP_LIMIT`, `OCO`, `TRAILING_STOP` to the enum if missing.
- [ ] **Step 2:** Declare MT5's supported set.
- [ ] **Step 3:** Tests.

---

### Task 9 — Integration tests via `FakeMt5Client`

**Files:**
- Read: existing `FakeMt5Client` (if exists; else create)
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerPendingTest.kt` (new)
- Test: `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerOcoTest.kt` (new)

- [ ] **Step 1:** Locate or create `FakeMt5Client` — captures sent wire requests, can deliver synthetic `OrderFilled` / `OrderCancelled` events back.
- [ ] **Step 2:** Test: submit a `BUY_STOP`, verify wire request shape, deliver a fill, assert position created.
- [ ] **Step 3:** Test: submit OCO with two stops, verify two wire requests with shared group, deliver leg1 fill, assert cancel call for leg2.
- [ ] **Step 4:** Test: submit trailing stop, deliver price movement events, assert trail follows.
- [ ] **Step 5:** Run, fix, commit.

---

### Task 10 — Docs + planned.md cleanup

**Files:**
- Modify: `docs/reference/dsl/actions.md` — remove "Phase 26b" caveats
- Modify: `docs/planned.md` — remove Phase 26b entry
- Modify: `examples/hedge-straddle/README.md` — remove "live runtime: Phase 26b" caveat
- Create: `docs/phases/phase-26b-mt5-pending-family.md` — changelog per §6 template

- [ ] **Step 1:** Update each location.
- [ ] **Step 2:** Build docs via `mkdocs build --strict` if available.
- [ ] **Step 3:** Commit.

---

### Task 11 — Pre-merge verification

- [ ] **Step 1:** Full build: `./gradlew build --no-daemon`. Expected: BUILD SUCCESSFUL, ktlint clean.
- [ ] **Step 2:** Full smoke: `bash tests/smoke-install.sh`.
- [ ] **Step 3:** Read all commits on the branch and confirm message conventions.
- [ ] **Step 4:** Open PR.

---

### Task 12 — End-to-end user-flow validation

**Outside the repo** — in `~/Desktop/personal/qkt-user-test/`.

- [ ] **Step 1:** Build a local distribution: `./gradlew installDist` in the qkt repo.
- [ ] **Step 2:** Set up `~/Desktop/personal/qkt-user-test/` as a fresh user workspace. Copy the dist into `~/Desktop/personal/qkt-user-test/qkt-install/`. Symlink `qkt-install/bin/qkt` to `~/Desktop/personal/qkt-user-test/bin/qkt`.
- [ ] **Step 3:** Walk every CLI command:
  - `qkt --version`
  - `qkt --help`
  - `qkt brokers list`
  - Author `strategies/simple.qkt`, `qkt parse`, `qkt backtest --json`
  - `qkt daemon start` (background)
  - `qkt deploy strategies/simple.qkt`
  - `qkt list`
  - `qkt status simple`
  - `qkt logs simple`
  - `qkt stop simple`
  - `qkt start simple`
  - `qkt daemon stop`
- [ ] **Step 4:** Docker walkthrough: `docker build`, `docker run` for parse + backtest.
- [ ] **Step 5:** Each bug surfaced → fix in the qkt repo, rebuild dist, re-test.
- [ ] **Step 6:** Acceptance: clean run of all steps with no errors.

---

### Task 13 — Production hedge-straddle scaffold

**Outside the repo** — in `~/Desktop/personal/qkt-strategies-live/`.

- [ ] **Step 1:** Create dir structure:
```
qkt-strategies-live/
├── strategies/
│   └── hedge-straddle.qkt
├── data/
│   └── (mt5-gateway will populate)
├── docker-compose.yml      (qkt + mt5-gateway services)
├── .env.example            (MT5_USER, MT5_PASS, MT5_SERVER, MT5_API_URL)
├── README.md               (go-live checklist)
└── deploy.sh               (helper script)
```
- [ ] **Step 2:** Strategy file: production values from pa-quant's `config/strategies.ts`.
- [ ] **Step 3:** docker-compose: qkt + mt5-gateway, shared network, env passthrough.
- [ ] **Step 4:** README with: prerequisites (Exness account, IP-whitelisted VPS, JDK 21 on host), step-by-step deployment, monitoring (log tails, equity check), kill-switch instructions, what to do if reconciliation flags a divergence.

This is SCAFFOLD only — no live MT5 connection. The README quotes what elitekaycy needs to do (credentials, VPS, monitoring).

---

### Task 14 — Handoff note

- [ ] **Step 1:** Write `/tmp/qkt-morning-handoff.md` with:
  - What's green (commits, PRs, test counts)
  - What hit issues (and the fix or open question)
  - What needs elitekaycy's decisions (live MT5 deployment, credentials, anything I couldn't autonomously decide)
  - Current branch + PR state
  - Where the user-test and production-scaffold dirs are
  - Estimated time to live (if all green): how soon hedge-straddle can be paper-traded with credentials, how long to verify before going live

---

## Self-review checklist

- [ ] Every spec requirement maps to a task.
- [ ] No placeholders.
- [ ] Each translation method has a unit test.
- [ ] OCO cancel-on-fill has an integration test.
- [ ] Trailing has an integration test.
- [ ] User-flow validation surfaced and fixed any bugs.
- [ ] Production scaffold has clear go-live steps.
- [ ] Handoff note covers all decision points.
