# Symbol Convention Normalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the qkt symbol (`BROKER:BARE`, e.g. `EXNESS:XAUUSD`) the single in-engine identifier so `qkt deploy` works with TV-routable symbols on a fresh install with no MT5 broker configured.

**Architecture:** Engine receives prefixed symbols at every boundary. Sources, brokers, and `AstCompiler` position lookups all key by `qktSymbol`. Wire forms (`XAUUSDm`) live only inside `Mt5MarketSource`/`Mt5TickFeedSource` between subscribe and emit.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, no new dependencies. Branch: `fix-live-symbol-prefix` (already created).

---

### Task 1: Add `qktSymbol` derived getters

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/HubKey.kt`
- Test: `src/test/kotlin/com/qkt/dsl/ast/StreamDeclTest.kt` (extend if exists, else create)

- [ ] **Step 1: Write the failing test**

If `src/test/kotlin/com/qkt/dsl/ast/StreamDeclTest.kt` does not exist, create it. Otherwise add this test to the existing file.

```kotlin
package com.qkt.dsl.ast

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StreamDeclTest {
    @Test
    fun `qktSymbol joins broker and symbol with colon`() {
        val s = StreamDecl(alias = "gold", broker = "EXNESS", symbol = "XAUUSD", timeframe = "5m")
        assertThat(s.qktSymbol).isEqualTo("EXNESS:XAUUSD")
    }

    @Test
    fun `qktSymbol works for bybit-style multi-segment broker`() {
        val s = StreamDecl(alias = "btc", broker = "BYBIT_LINEAR", symbol = "BTCUSDT", timeframe = "1m")
        assertThat(s.qktSymbol).isEqualTo("BYBIT_LINEAR:BTCUSDT")
    }
}
```

- [ ] **Step 2: Run the test, expect FAIL**

Run: `./gradlew test --tests "com.qkt.dsl.ast.StreamDeclTest" -q`
Expected: FAIL — `Unresolved reference: qktSymbol`

- [ ] **Step 3: Add the getter to `StreamDecl`**

Edit `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt`. After the `init` block of `StreamDecl`, add:

```kotlin
    val qktSymbol: String get() = "$broker:$symbol"
```

- [ ] **Step 4: Add the matching getter to `HubKey`**

Edit `src/main/kotlin/com/qkt/dsl/compile/HubKey.kt`. After the `init` block of `HubKey`, add:

```kotlin
    val qktSymbol: String get() = "$broker:$symbol"
```

- [ ] **Step 5: Verify tests pass**

Run: `./gradlew test --tests "com.qkt.dsl.ast.StreamDeclTest" -q`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt src/main/kotlin/com/qkt/dsl/compile/HubKey.kt src/test/kotlin/com/qkt/dsl/ast/StreamDeclTest.kt
git commit -m "feat(dsl): qktSymbol derived getter on StreamDecl and HubKey"
```

---

### Task 2: Regression test against real `TradingViewMarketSource.supports()`

This is the test that would have caught the original bug. It uses the real regex, no fake.

**Files:**
- Modify (or create): `src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewMarketSourceTest.kt`

- [ ] **Step 1: Write the test**

If the file exists, append; otherwise create with this content.

```kotlin
package com.qkt.marketdata.live.tv

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingViewMarketSourceSupportsTest {
    @Test
    fun `bare symbol is rejected — engine must pass prefixed BROKER colon SYMBOL`() {
        // Locks the convention: nothing in the engine should ever hand a bare symbol
        // to a live MarketSource. This is the test whose absence let the bare-vs-prefixed
        // bug ship.
        val tv = TradingViewMarketSource(webSocket = NoopTradingViewWebSocket)
        assertThat(tv.supports("XAUUSD")).isFalse
        assertThat(tv.supports("EURUSD")).isFalse
    }

    @Test
    fun `prefixed exchange colon symbol is accepted`() {
        val tv = TradingViewMarketSource(webSocket = NoopTradingViewWebSocket)
        assertThat(tv.supports("OANDA:XAUUSD")).isTrue
        assertThat(tv.supports("NASDAQ:AAPL")).isTrue
        assertThat(tv.supports("BINANCE:BTCUSDT")).isTrue
    }

    private object NoopTradingViewWebSocket : TradingViewWebSocketLike {
        override fun send(operation: String, payload: List<Any>) = Unit
        override fun close() = Unit
    }
}
```

If `TradingViewWebSocketLike` has a different signature, adjust the no-op to match. Check the interface in `src/main/kotlin/com/qkt/marketdata/live/tv/TradingViewWebSocket.kt` first.

- [ ] **Step 2: Run test to verify it passes immediately**

Run: `./gradlew test --tests "*TradingViewMarketSourceSupportsTest*" -q`
Expected: PASS (the regex already enforces this; the test locks the contract).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/marketdata/live/tv/TradingViewMarketSourceTest.kt
git commit -m "test(marketdata): lock TradingViewMarketSource.supports regex contract"
```

---

### Task 3: Switch `AstCompiler` position lookups to `qktSymbol`

The strategy compiler currently looks up positions by bare `it.symbol`. After this refactor it must look up by `qktSymbol`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`
- Modify (if any other compile sites read `.symbol` for runtime lookup): `src/main/kotlin/com/qkt/dsl/compile/*.kt`

- [ ] **Step 1: Audit the read sites**

Run:
```bash
grep -n "\.symbol\b" src/main/kotlin/com/qkt/dsl/compile/*.kt | grep -v "qktSymbol\|broker.symbol\|//"
```

The only legitimate hits should be on `HubKey.symbol` / `StreamDecl.symbol` used for things that genuinely want bare (e.g. printing broker prefix separately). Position-tracker lookups MUST move to `qktSymbol`.

Confirm at least these sites move to `qktSymbol`:
- `AstCompiler.kt:207` — `val symbol = streams[alias]!!.symbol` → `val symbol = streams[alias]!!.qktSymbol`
- `AstCompiler.kt:210` — `ctx.positions.positionFor(symbol)` (already uses `symbol` — no change after rename above)
- `AstCompiler.kt:211` — `transitions.observe(symbol, qty)` (already uses `symbol` — no change after rename)
- Any sibling site in `AstCompiler` (lines around 235, 277, 310 per the earlier grep) doing `positionFor` against a stream-derived value.

- [ ] **Step 2: Make the edits**

For each site, change the source-of-truth read from `streams[alias]!!.symbol` to `streams[alias]!!.qktSymbol`. Leave bare reads alone if they're not feeding `positionFor`/`positions[...]`.

- [ ] **Step 3: Build to surface compile errors**

Run: `./gradlew compileKotlin -q`
Expected: no errors. If errors, the change touched a non-runtime read — revert that specific site.

- [ ] **Step 4: Run the full unit test suite**

Run: `./gradlew test -q`
Expected: a handful of `MT5DaemonE2ETest` and `StrategyHandleTest` failures (because fixture ticks still use bare). All other tests pass. We fix those failures in Tasks 5–7.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt
git commit -m "refactor(dsl): AstCompiler reads positions by qktSymbol"
```

---

### Task 4: Call sites pass prefixed symbols to `LiveSession`

`RunCommand`, `StrategyHandle`, and `PortfolioDeployer` currently compute both a bare `symbols` list and a prefixed `tvSymbols` list, passing bare to the engine. Drop the bare list and pass prefixed only.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/RunCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt`
- Modify: `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt`
- Modify: `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt` (line 138 also does bare)

- [ ] **Step 1: RunCommand**

Edit `src/main/kotlin/com/qkt/cli/RunCommand.kt` around lines 95–96. Replace:

```kotlin
val symbols = ast.streams.map { it.symbol }.distinct()
val tvSymbols = ast.streams.map { "${it.broker}:${it.symbol}" }.distinct()
```

With:

```kotlin
val symbols = ast.streams.map { it.qktSymbol }.distinct()
```

Then update all references to `tvSymbols` later in the file (line 123 and 126) to use `symbols`.

- [ ] **Step 2: StrategyHandle**

Edit `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt` around lines 86–87. Replace:

```kotlin
val symbols = ast.streams.map { it.symbol }.distinct()
val tvSymbols = ast.streams.map { "${it.broker}:${it.symbol}" }.distinct()
```

With:

```kotlin
val symbols = ast.streams.map { it.qktSymbol }.distinct()
```

Update the `marketSourceProvider(tvSymbols)` call at line 94 to `marketSourceProvider(symbols)`.

- [ ] **Step 3: PortfolioDeployer**

Edit `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt` around lines 46–49. Replace:

```kotlin
val tvSymbols =
    compiled.ast.streams
        .map { "${it.broker}:${it.symbol}" }
        .distinct()
```

With:

```kotlin
val symbols = compiled.ast.streams.map { it.qktSymbol }.distinct()
```

Update the use at line 54 to `if (symbols.isEmpty()) null else marketSourceProvider(symbols)`.

- [ ] **Step 4: PortfolioSupervisor**

Edit `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt` line 138. Replace:

```kotlin
val symbols = ast.streams.map { it.symbol }.distinct()
```

With:

```kotlin
val symbols = ast.streams.map { it.qktSymbol }.distinct()
```

- [ ] **Step 5: Compile and run tests**

Run: `./gradlew compileKotlin -q && ./gradlew test -q`
Expected: same set of failures as Task 3 (MT5 e2e + position-keying tests). No new compile errors.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/RunCommand.kt src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt
git commit -m "refactor(cli): pass qktSymbol to LiveSession at every call site"
```

---

### Task 5: `Mt5MarketSource` and `Mt5TickFeedSource` emit qkt symbols

The feed source currently emits ticks with the wire form (`XAUUSDm`). Thread a `wire → qkt` map through `Mt5MarketSource` so the feed source emits qkt.

**Files:**
- Modify: `src/main/kotlin/com/qkt/marketdata/live/mt5/Mt5MarketSource.kt`
- Modify: `src/main/kotlin/com/qkt/marketdata/live/mt5/Mt5TickFeedSource.kt`
- Modify: `src/test/kotlin/com/qkt/marketdata/live/mt5/Mt5TickFeedSourceTest.kt` (if exists)

- [ ] **Step 1: Update `Mt5TickFeedSource` to take a wire→qkt map**

Edit `src/main/kotlin/com/qkt/marketdata/live/mt5/Mt5TickFeedSource.kt`. Replace the `symbols: List<String>` constructor parameter with:

```kotlin
private val symbolMap: Map<String, String>,
```

where the key is the wire form and the value is the qkt form. Inside the class, derive `private val symbols = symbolMap.keys.toList()` if a list is still needed for polling.

At the tick emit site around line 67, change:

```kotlin
Tick(
    symbol = sym,
    ...
)
```

to:

```kotlin
Tick(
    symbol = symbolMap[sym] ?: sym,
    ...
)
```

If there's a session-gate check at line 49 using `symbols.first()`, leave it — it's checking trading-session gating against the wire symbol, which is correct.

- [ ] **Step 2: Update `Mt5MarketSource.liveTicks`**

Edit `src/main/kotlin/com/qkt/marketdata/live/mt5/Mt5MarketSource.kt` around line 46–61:

```kotlin
override fun liveTicks(symbols: List<String>): TickFeed {
    require(symbols.all { supports(it) }) { "$name cannot serve $symbols" }
    val wireToQkt: Map<String, String> = symbols.associateBy { qkt ->
        symbolMap.toBroker(qkt.removePrefix(prefix))
    }
    return LiveTickFeed(
        source = Mt5TickFeedSource(
            baseUrl = profile.gatewayUrl,
            symbolMap = wireToQkt,
            pollIntervalMs = profile.pollIntervalMs,
            http = http,
            clock = clock,
            calendar = calendar,
        ),
        queueCapacity = 10_000,
    )
}
```

Note: `symbols.associateBy { wireKey }` reads "for each qkt symbol, key it by its computed wire form" — exactly the map we need.

- [ ] **Step 3: Update existing Mt5TickFeedSource tests**

If `src/test/kotlin/com/qkt/marketdata/live/mt5/Mt5TickFeedSourceTest.kt` exists, update its constructor calls from `symbols = listOf("XAUUSDm")` to `symbolMap = mapOf("XAUUSDm" to "EXNESS:XAUUSD")` (or whatever the test expects). Update tick assertions to expect qkt-form `symbol`.

- [ ] **Step 4: Compile and run tests**

Run: `./gradlew test -q`
Expected: Mt5 source tests pass; e2e test still fails (next task).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/live/mt5/Mt5MarketSource.kt src/main/kotlin/com/qkt/marketdata/live/mt5/Mt5TickFeedSource.kt src/test/kotlin/com/qkt/marketdata/live/mt5/Mt5TickFeedSourceTest.kt
git commit -m "refactor(marketdata): Mt5TickFeedSource emits qkt symbol via wire-to-qkt map"
```

---

### Task 6: `MT5Broker` and `MT5PositionPoller` emit prefixed positions

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5PositionPoller.kt`
- Modify: `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerTest.kt`

- [ ] **Step 1: Update `MT5Broker.getOpenPositions`**

Edit `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` line 249. Replace:

```kotlin
val qktSymbol = mt5Symbol.toQkt(p.symbol)
```

With:

```kotlin
val qktSymbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(p.symbol)}"
```

Also update line 343 (same pattern, position-side translation):

```kotlin
val qktSymbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(position.symbol)}"
```

- [ ] **Step 2: Update `MT5PositionPoller`**

Edit `src/main/kotlin/com/qkt/broker/mt5/MT5PositionPoller.kt` line 88. Replace:

```kotlin
val qktSymbol = symbol.toQkt(p.symbol)
```

With:

```kotlin
val qktSymbol = "${profile.name.uppercase()}:${symbol.toQkt(p.symbol)}"
```

Apply the same transform to any other emit site in this file that currently produces a bare qkt symbol for events.

- [ ] **Step 3: Update `MT5BrokerTest`**

Find the existing positions test in `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerTest.kt`. Update the expected map key from bare (`"XAUUSD"`) to prefixed (`"EXNESS:XAUUSD"` — the profile name used in the fixture).

- [ ] **Step 4: Run broker tests**

Run: `./gradlew test --tests "*MT5BrokerTest*" --tests "*MT5PositionPollerTest*" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt src/main/kotlin/com/qkt/broker/mt5/MT5PositionPoller.kt src/test/kotlin/com/qkt/broker/mt5/MT5BrokerTest.kt
git commit -m "refactor(broker): MT5 emits prefixed qkt symbols on positions and events"
```

---

### Task 7: Update `MT5DaemonE2ETest` fixture

The end-to-end test currently feeds ticks with bare symbols. Now that the engine expects prefixed, update the fixture.

**Files:**
- Modify: `src/test/kotlin/com/qkt/parity/MT5DaemonE2ETest.kt`
- Possibly modify: `src/test/resources/parity/mt5_e2e_strategy.qkt` (only if it references symbols incorrectly — likely fine as-is since DSL already uses `EXNESS:EURUSD`)

- [ ] **Step 1: Update fixture ticks**

Edit `src/test/kotlin/com/qkt/parity/MT5DaemonE2ETest.kt` around line 93. Replace:

```kotlin
Tick(
    symbol = "EURUSD",
    ...
)
```

With:

```kotlin
Tick(
    symbol = "EXNESS:EURUSD",
    ...
)
```

- [ ] **Step 2: Run the e2e test**

Run: `./gradlew test --tests "com.qkt.parity.MT5DaemonE2ETest" -q`
Expected: PASS — the strategy now sees `POSITION.gold` (mapped to `EXNESS:EURUSD`), fires the BUY rule, and the MT5 gateway receives the wire-form `EURUSDm` order.

- [ ] **Step 3: Run the whole test suite**

Run: `./gradlew test -q`
Expected: PASS across the board.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/qkt/parity/MT5DaemonE2ETest.kt
git commit -m "test(parity): MT5DaemonE2ETest feeds prefixed qkt-symbol ticks"
```

---

### Task 8: Manual CLI verification

This is the smoke test that motivated the whole refactor. It exercises the path the unit tests don't cover: a real `TradingViewMarketSource` regex check on a real deploy.

- [ ] **Step 1: Fresh build**

Run: `./gradlew installDist -q`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Start daemon in a scratch state dir**

```bash
export PATH="$PWD/build/install/qkt/bin:$PATH"
export QKT_STATE_DIR=/tmp/qkt-verify/state
rm -rf /tmp/qkt-verify
mkdir -p /tmp/qkt-verify
cd /tmp/qkt-verify
qkt daemon &
sleep 3
qkt list
```

Expected: empty list, daemon running.

- [ ] **Step 3: Deploy a TV-only strategy**

Write `/tmp/qkt-verify/live-smoke.qkt`:

```
STRATEGY live_smoke VERSION 1

SYMBOLS
    gold = OANDA:XAUUSD EVERY 1m

RULES
    WHEN gold.close > 0
     AND POSITION.gold = 0
    THEN LOG "tick" price=gold.close
```

Then:

```bash
qkt parse live-smoke.qkt
qkt deploy live-smoke.qkt --as live-smoke
qkt list
qkt status live-smoke
qkt logs live-smoke | head -20
qkt stop live-smoke
qkt daemon stop
```

Expected: deploy succeeds; `list` shows `live-smoke` running; `status` shows healthy; `logs` shows tick events; stop succeeds.

- [ ] **Step 4: Capture the smoke output to the plan file as evidence**

If anything fails here, return to the relevant task and fix the regression. Do NOT proceed to Task 9 until manual smoke passes.

---

### Task 9: Push and PR

- [ ] **Step 1: Confirm branch state**

```bash
git status              # working tree clean
git log --oneline main..HEAD
```

Expected: 7 conventional commits (one per task 1–7).

- [ ] **Step 2: Push**

```bash
git push -u origin fix-live-symbol-prefix
```

- [ ] **Step 3: Open PR**

```bash
gh pr create --title "[fix] normalize qkt symbol form to BROKER:SYMBOL across engine" --body "$(cat <<'EOF'
## Phase
Bug-driven refactor. Spec: docs/superpowers/specs/2026-05-13-symbol-convention-normalization-design.md. Plan: docs/superpowers/plans/2026-05-13-symbol-convention-normalization.md.

## Summary
Fixes a latent live-deploy bug surfaced by a fresh-install CLI verification: the engine passed bare symbols (`XAUUSD`) into `MarketSource.liveTicks`, which the prod `TradingViewMarketSource` regex rejects. Unit tests missed it because every test source set `supports = true` and ignored its `symbols` argument. This PR converges every in-engine symbol reference on the qkt convention (`BROKER:BARE`, e.g. `EXNESS:XAUUSD`) — the form that `Mt5MarketSource`/`Bybit*MarketSource`/`CompositeMarketSource` were already designed around.

## Changes
- `StreamDecl` and `HubKey` gain `qktSymbol` derived getters.
- `AstCompiler` reads positions by `qktSymbol`.
- `RunCommand`, `StrategyHandle`, `PortfolioDeployer`, `PortfolioSupervisor` all pass `qktSymbol` to `LiveSession` and downstream sources.
- `Mt5MarketSource` builds a `wire → qkt` map and threads it through `Mt5TickFeedSource` so ticks emit with the qkt symbol form rather than the wire form.
- `MT5Broker.getOpenPositions` and `MT5PositionPoller` prefix the position-map keys with `profile.name.uppercase()` to match Bybit's existing convention.
- New regression test against the real `TradingViewMarketSource.supports()` regex — the kind of test whose absence let the original bug ship.

## Tests
- Unit: `TradingViewMarketSourceSupportsTest`, `StreamDeclTest`, broker tests updated to expect prefixed keys.
- Integration: `MT5DaemonE2ETest` fixture updated to feed prefixed ticks; assertion path verified.
- Manual: `qkt daemon` + `qkt deploy live-smoke.qkt --as live-smoke` against `OANDA:XAUUSD` reaches `running` state. (Output captured in PR comment.)

## Backwards compatibility
**None.** Phase 29 state files keyed by bare symbol will not reconcile after this change — wipe `state/` between versions. Acceptable: no live production users yet.

## Out of scope
- Persisted state migration.
- DSL syntax changes.
- Wire-symbol policy refactor for MT5 (already correct).

## Risk
**Low–medium.** Touches the live-deploy seam, but the failure mode is loud (regex rejection at deploy) rather than silent. The new regression test locks the convention.
EOF
)"
```

---

## Self-review checklist

- [x] Every task lists explicit file paths and line numbers where relevant.
- [x] Every task ends with a commit using a conventional-commits subject (no Claude footer, no AI references per qkt skill §3).
- [x] Steps are bite-sized (write test → run test → edit → re-run → commit).
- [x] The regression test in Task 2 uses the **real** `TradingViewMarketSource`, not a fake — directly addressing why the bug shipped.
- [x] Task 8 (manual smoke) is the acceptance criterion that motivated the whole spec.
- [x] No task introduces a new abstraction layer (rejected `SymbolRouter` per spec).
- [x] No task touches DSL surface.
- [x] No task introduces backwards-compat shims.
