# MT5 poller race fix — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the v0.28.4 follow-up bug where the `MT5PendingOrderPoller` and `MT5PositionPoller` race on pending → position transitions, silently losing meta and never emitting `OrderFilled`.

**Architecture:** Two surgical changes inside `MT5Broker`. (A) `onPendingDisappeared` cross-checks `/positions` before treating a tracked-ticket disappearance as a cancel, and routes through `onPendingPositionOpened` when the disappearance is actually a fill. (B) `onPendingPositionOpened` logs a WARN instead of silently returning when a new venue position has no corresponding `pendingByTicket` entry — so any future correlation gap is observable.

**Tech Stack:** Kotlin 2.1, JDK 21, OkHttp 4 + MockWebServer 4 (existing test deps), JUnit 5 + AssertJ. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-18-mt5-poller-race-design.md`.

**Branch:** `fix/mt5-poller-race`, branched from `main` at the v0.28.4 tag.

---

## Task ordering rationale

TDD-first: each fix lands as `failing test → minimal implementation → passing test → regression tests for the unchanged paths → commit`. Fix A is committed first (it's the load-bearing change); Fix B (observability) lands second so its WARN-on-no-meta test isn't confused by fix-A behavior. Final task bumps `BuildInfo` to `0.28.5`, opens PR, merges, tags.

The two tiny enabling changes (exposing internal pollers + the `lookup-vs-remove` change inside `onPendingDisappeared`) ride inside Fix A's task. They are not separate tasks because there is no test that exercises them in isolation.

---

## File structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` | modify | Fix A in `onPendingDisappeared`; Fix B in `onPendingPositionOpened`; promote `poller` + `pendingPoller` fields from `private` to `internal` so tests can drive their ticks. |
| `src/test/kotlin/com/qkt/broker/mt5/MT5PollerRaceTest.kt` | create | Four tests covering both race orderings, real external cancel, and Fix B's WARN. |
| `src/main/kotlin/com/qkt/cli/BuildInfo.kt` | modify (final task) | bump VERSION to `0.28.5`. |

---

### Task 1: Enable test access to broker's internal pollers

The new race tests need to invoke `pollerTick()` and `pendingPollerTickForTesting()` in a controlled order. Today both pollers are `private` fields of `MT5Broker`. Promote them to `internal` — same-module visibility, no public surface change.

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt:55-78`

- [ ] **Step 1: Promote `poller` and `pendingPoller` to `internal`**

Edit `MT5Broker.kt`. Change:

```kotlin
    private val poller =
        MT5PositionPoller(
```

to:

```kotlin
    internal val poller =
        MT5PositionPoller(
```

And:

```kotlin
    private val pendingPoller =
        MT5PendingOrderPoller(
```

to:

```kotlin
    internal val pendingPoller =
        MT5PendingOrderPoller(
```

- [ ] **Step 2: Verify the project still compiles**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt
git commit -m "refactor(broker): expose mt5 pollers to same-module tests"
```

---

### Task 2: Failing test — pending-poller wins the race (the production bug)

**Files:**
- Create: `src/test/kotlin/com/qkt/broker/mt5/MT5PollerRaceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MT5PollerRaceTest {
    private lateinit var server: MockWebServer
    private lateinit var broker: MT5Broker
    private lateinit var bus: EventBus
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()
    private val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
    private val accepts = mutableListOf<BrokerEvent.OrderAccepted>()

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        // State recovery + both poller seeds: all empty.
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))

        val clock = FixedClock(time = 1_700_000_000_000L)
        bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> cancels.add(e) }
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> accepts.add(e) }

        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        broker = MT5Broker(profile, bus, clock)
    }

    @AfterEach
    fun teardown() {
        broker.shutdown()
        server.shutdown()
    }

    private fun pendingBracket(id: String, side: Side, stop: String) =
        OrderRequest.Bracket(
            id = id,
            entry =
                OrderRequest.Stop(
                    id = "$id-entry",
                    symbol = "EXNESS:EURUSD",
                    side = side,
                    quantity = BigDecimal("0.10"),
                    stopPrice = BigDecimal(stop),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "s1",
                ),
            takeProfit = if (side == Side.BUY) BigDecimal("1.1300") else BigDecimal("1.1100"),
            stopLoss = if (side == Side.BUY) BigDecimal("1.1150") else BigDecimal("1.1250"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "s1",
            symbol = "EXNESS:EURUSD",
            side = side,
            quantity = BigDecimal("0.10"),
        )

    @Test
    fun `pending-poller wins race — fill is recovered, no phantom cancel`() {
        // Placement response: ticket 9001 returned.
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":9001,"deal":0,"price":"1.1200","comment":"ok"}}""",
            ),
        )
        broker.submit(pendingBracket("ord-1", Side.BUY, "1.1200"))
        assertThat(accepts).hasSize(1)
        assertThat(fills).isEmpty()

        // Seed the pending-poller's lastSnapshot with the BUY ticket via one tick where /orders has it.
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9001","symbol":"EURUSDm","type":"4","volume_initial":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","magic":"10001","time_setup":"0"}]""",
            ),
        )
        broker.pendingPoller.tickForTesting()

        // Pending-poller wins the race: next tick sees /orders empty (ticket gone),
        // and BEFORE the position-poller runs, the cross-check inside onPendingDisappeared
        // observes the ticket is now a position.
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9001","symbol":"EURUSDm","type":"0","volume":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","profit":"0","magic":"10001","time":"0"}]""",
            ),
        )
        broker.pendingPoller.tickForTesting()

        // Now the position-poller runs. It should see the same ticket and NOT publish a duplicate fill
        // (the cross-check already drove onPendingPositionOpened).
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9001","symbol":"EURUSDm","type":"0","volume":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","profit":"0","magic":"10001","time":"0"}]""",
            ),
        )
        broker.poller.tick()

        assertThat(fills).hasSize(1)
        assertThat(fills[0].clientOrderId).isEqualTo("ord-1")
        assertThat(fills[0].brokerOrderId).isEqualTo("9001")
        assertThat(cancels).isEmpty()
    }
}
```

- [ ] **Step 2: Run the test, confirm it fails**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5PollerRaceTest`
Expected: FAIL — today's `onPendingDisappeared` publishes `OrderCancelled` and the subsequent position-poller tick produces zero fills (silent return because `pendingByTicket` is empty).

---

### Task 3: Implement Fix A — defensive cross-check in `onPendingDisappeared`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt:585-607`

- [ ] **Step 1: Rewrite `onPendingDisappeared`**

Replace the existing method body with:

```kotlin
    private fun onPendingDisappeared(ticket: Long) {
        val meta = pendingByTicket[ticket] ?: return

        val ttlMs = profile.pollIntervalMs * DISAMBIGUATION_TTL_MULTIPLIER
        val recentlyFilledAt = recentlyFilledTickets[ticket]
        val now = clock.now()
        if (recentlyFilledAt != null && now - recentlyFilledAt < ttlMs) {
            pendingByTicket.remove(ticket)
            pendingTickets.entries.removeIf { it.value == ticket }
            recentlyFilledTickets.remove(ticket)
            return
        }

        // Cross-check /positions before treating as cancel. If the ticket is now a position,
        // the pending-poller observed the transition before the position-poller did —
        // synthesize the fill path here instead of phantom-cancelling.
        val asPosition =
            runCatching { client.getPositions(magic = profile.magic).firstOrNull { it.ticket == ticket } }
                .getOrNull()
        if (asPosition != null) {
            onPendingPositionOpened(asPosition)
            return
        }

        pendingByTicket.remove(ticket)
        pendingTickets.entries.removeIf { it.value == ticket }
        recentlyFilledTickets.entries.removeIf { now - it.value >= ttlMs }
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = meta.orderId,
                brokerOrderId = ticket.toString(),
                reason = "external or gtd-expired (pending disappeared from venue)",
                strategyId = meta.strategyId,
                timestamp = clock.now(),
            ),
        )
    }
```

Note the load-bearing change: `pendingByTicket[ticket] ?: return` (lookup) replaces `pendingByTicket.remove(ticket) ?: return` (lookup-and-remove). The original `remove` is now performed inside each terminal branch that actually ends the pending's lifecycle — so when the cross-check routes to `onPendingPositionOpened`, that callee can still find and remove the meta.

- [ ] **Step 2: Run the race test, confirm it passes**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5PollerRaceTest`
Expected: PASS — `fills` has size 1, `cancels` empty.

- [ ] **Step 3: Run the full broker-mt5 test package, confirm no regression**

Run: `./gradlew test --tests 'com.qkt.broker.mt5.*'`
Expected: all green.

---

### Task 4: Regression tests — position-poller wins; real external cancel

**Files:**
- Modify: `src/test/kotlin/com/qkt/broker/mt5/MT5PollerRaceTest.kt`

- [ ] **Step 1: Add the position-poller-wins test**

Append to `MT5PollerRaceTest`:

```kotlin
    @Test
    fun `position-poller wins race — single fill, ttl marker suppresses pending-poller`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":9002,"deal":0,"price":"1.1200","comment":"ok"}}""",
            ),
        )
        broker.submit(pendingBracket("ord-2", Side.BUY, "1.1200"))

        // Seed pending-poller with the BUY ticket.
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9002","symbol":"EURUSDm","type":"4","volume_initial":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","magic":"10001","time_setup":"0"}]""",
            ),
        )
        broker.pendingPoller.tickForTesting()

        // Position-poller wins: sees the ticket appear in /positions before pending-poller
        // sees it disappear from /orders.
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9002","symbol":"EURUSDm","type":"0","volume":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","profit":"0","magic":"10001","time":"0"}]""",
            ),
        )
        broker.poller.tick()
        assertThat(fills).hasSize(1)
        assertThat(fills[0].clientOrderId).isEqualTo("ord-2")

        // Pending-poller now ticks, sees the ticket gone from /orders, finds the TTL marker
        // and consumes it silently — no duplicate fill, no phantom cancel.
        server.enqueue(MockResponse().setBody("[]"))
        broker.pendingPoller.tickForTesting()
        assertThat(fills).hasSize(1)
        assertThat(cancels).isEmpty()
    }

    @Test
    fun `real external cancel — onPendingDisappeared still publishes OrderCancelled`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":9003,"deal":0,"price":"1.1200","comment":"ok"}}""",
            ),
        )
        broker.submit(pendingBracket("ord-3", Side.BUY, "1.1200"))

        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9003","symbol":"EURUSDm","type":"4","volume_initial":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","magic":"10001","time_setup":"0"}]""",
            ),
        )
        broker.pendingPoller.tickForTesting()

        // Pending disappears from /orders, and the cross-check finds /positions empty
        // (the user cancelled the pending in MetaTrader, or GTD expired).
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        broker.pendingPoller.tickForTesting()

        assertThat(fills).isEmpty()
        assertThat(cancels).hasSize(1)
        assertThat(cancels[0].clientOrderId).isEqualTo("ord-3")
        assertThat(cancels[0].reason).contains("external or gtd-expired")
    }
```

- [ ] **Step 2: Run the full test class, confirm all three pass**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5PollerRaceTest`
Expected: 3 tests, all green.

- [ ] **Step 3: Commit fix A + its tests**

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt src/test/kotlin/com/qkt/broker/mt5/MT5PollerRaceTest.kt
git commit -m "fix(broker): cross-check positions before phantom-cancelling pending"
```

---

### Task 5: Failing test — Fix B WARN on un-correlated venue position

**Files:**
- Modify: `src/test/kotlin/com/qkt/broker/mt5/MT5PollerRaceTest.kt`

- [ ] **Step 1: Add the WARN test**

Add a logback list-appender import block at the top of the file:

```kotlin
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
```

Append to `MT5PollerRaceTest`:

```kotlin
    @Test
    fun `un-correlated venue position — WARN logged, no OrderFilled`() {
        val logger = LoggerFactory.getLogger(MT5Broker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            // Position-poller sees a brand-new ticket that the broker never placed
            // (e.g. manual MT5 trade with the same magic).
            server.enqueue(
                MockResponse().setBody(
                    """[{"ticket":"7777","symbol":"EURUSDm","type":"1","volume":"0.05","price_open":"1.1234","sl":"0","tp":"0","profit":"0","magic":"10001","time":"0"}]""",
                ),
            )
            broker.poller.tick()

            assertThat(fills).isEmpty()
            val warning =
                appender.list.firstOrNull {
                    it.level.toString() == "WARN" && it.formattedMessage.contains("no qkt-side pending meta")
                }
            assertThat(warning).isNotNull
            assertThat(warning!!.formattedMessage).contains("7777")
            assertThat(warning.formattedMessage).contains("EURUSDm")
            assertThat(warning.formattedMessage).contains("SELL") // type=1
        } finally {
            logger.detachAppender(appender)
        }
    }
```

- [ ] **Step 2: Run the test, confirm it fails**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5PollerRaceTest.\"un-correlated venue position — WARN logged, no OrderFilled\"`
Expected: FAIL — today `onPendingPositionOpened` returns silently and no WARN is emitted.

---

### Task 6: Implement Fix B — WARN in `onPendingPositionOpened`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt:537-559`

- [ ] **Step 1: Replace the early-return with a logged WARN**

Replace:

```kotlin
    private fun onPendingPositionOpened(position: MT5Position) {
        val meta = pendingByTicket.remove(position.ticket) ?: return
        pendingTickets.remove(meta.orderId)
        recentlyFilledTickets[position.ticket] = clock.now()
        positionMetaByTicket[position.ticket] = meta
```

with:

```kotlin
    private fun onPendingPositionOpened(position: MT5Position) {
        val meta = pendingByTicket.remove(position.ticket)
        if (meta == null) {
            log.warn(
                "MT5Broker {} saw new position ticket={} symbol={} side={} magic={} with no qkt-side " +
                    "pending meta — either externally placed or pending-poller already consumed the " +
                    "meta (poll-ordering race; see fix A path).",
                profile.name,
                position.ticket,
                position.symbol,
                if (position.type == 0) "BUY" else "SELL",
                profile.magic,
            )
            return
        }
        pendingTickets.remove(meta.orderId)
        recentlyFilledTickets[position.ticket] = clock.now()
        positionMetaByTicket[position.ticket] = meta
```

(Everything below the `positionMetaByTicket` line is unchanged.)

- [ ] **Step 2: Run the WARN test, confirm it passes**

Run: `./gradlew test --tests com.qkt.broker.mt5.MT5PollerRaceTest`
Expected: 4 tests, all green.

- [ ] **Step 3: Run the full broker-mt5 test package, confirm no regression**

Run: `./gradlew test --tests 'com.qkt.broker.mt5.*'`
Expected: all green.

- [ ] **Step 4: Commit fix B + its test**

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt src/test/kotlin/com/qkt/broker/mt5/MT5PollerRaceTest.kt
git commit -m "feat(broker): warn on uncorrelated venue position in mt5 poller"
```

---

### Task 7: Bump version, full build, open PR

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BuildInfo.kt`

- [ ] **Step 1: Bump VERSION to `0.28.5`**

Edit `src/main/kotlin/com/qkt/cli/BuildInfo.kt`:

```kotlin
const val VERSION: String = "0.28.5"
```

- [ ] **Step 2: ktlintFormat + full build**

Run: `./gradlew ktlintFormat build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Inspect commit log**

Run: `git log --oneline main..HEAD`
Expected: three commits:
- `refactor(broker): expose mt5 pollers to same-module tests`
- `fix(broker): cross-check positions before phantom-cancelling pending`
- `feat(broker): warn on uncorrelated venue position in mt5 poller`

- [ ] **Step 4: Commit the version bump**

```bash
git add src/main/kotlin/com/qkt/cli/BuildInfo.kt
git commit -m "release: bump to 0.28.5"
```

- [ ] **Step 5: Ask elitekaycy before pushing + opening the PR**

Do NOT push or open the PR autonomously. Surface the commit list and the spec/plan paths and wait for explicit approval to push.

---

## Acceptance criteria

- All four new tests in `MT5PollerRaceTest` pass.
- `./gradlew build` is green.
- `git log --oneline main..HEAD` matches the four commits above (3 fix commits + 1 release bump).
- The diff to `MT5Broker.kt` covers only the methods named in tasks 1, 3, 6. No incidental edits.
- No public API change; the only signature-level edit is two `private val` → `internal val` field declarations.

## Out of scope (do not address in this PR)

- Empty per-strategy log file in prod (separate v0.28.5 ticket).
- `SELL_STOP price must be below current bid` rejection on tight-offset hours (separate v0.28.5 ticket).
- Long-term unification of the two MT5 pollers into a single broker-state poller (Phase 32+).
- `FileStatePersistor` `StandaloneOCO` skip warning at every OCO submit (backlog).
