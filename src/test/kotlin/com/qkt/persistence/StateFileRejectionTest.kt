package com.qkt.persistence

import com.qkt.common.Side
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StateFileRejectionTest {
    private val exit =
        PersistedTimeExit(
            id = "t1",
            strategyId = "alpha",
            symbol = "XAUUSD",
            side = Side.SELL,
            quantity = BigDecimal("0.1"),
            legId = null,
            ticket = "77",
            deadlineMs = 5_000L,
        )

    private val hook =
        PersistedExitHookBinding(
            bindingId = "b1",
            strategyId = "alpha",
            symbol = "XAUUSD",
            entrySide = Side.BUY,
            definitionId = "hook",
            fingerprint = "fp",
            entryOrderIds = listOf("e1"),
            stopOrderIds = emptyList(),
            takeProfitOrderIds = emptyList(),
            activeQuantity = BigDecimal.ONE,
            exitQuantity = BigDecimal.ZERO,
            exitPnl = BigDecimal.ZERO,
        )

    private fun Path.bumpVersion() = writeText(readText().replace(Regex("\"version\":\\s*\\d+"), "\"version\":-1"))

    private fun saveAll(tmp: Path) {
        val p = FileStatePersistor(tmp)
        p.saveTimedExits("alpha", listOf(exit))
        p.saveOrderIdMarks("alpha", mapOf("alpha" to 41L))
        p.saveExitHooks("alpha", listOf(hook))
    }

    @Test
    fun `timed exits, order id marks and exit hooks round trip`(
        @TempDir tmp: Path,
    ) {
        saveAll(tmp)
        val p = FileStatePersistor(tmp)

        assertThat(p.loadTimedExits("alpha")).containsExactly(exit)
        assertThat(p.loadOrderIdMarks("alpha")).containsExactlyEntriesOf(mapOf("alpha" to 41L))
        assertThat(p.loadExitHooks("alpha")).containsExactly(hook)
    }

    @Test
    fun `unparseable timed exit and order id files fail the load`(
        @TempDir tmp: Path,
    ) {
        val dir = Files.createDirectories(tmp.resolve("alpha"))
        dir.resolve("timed-exits.json").writeText("nope")
        dir.resolve("order-ids.json").writeText("nope")
        val p = FileStatePersistor(tmp)

        assertThatThrownBy { p.loadTimedExits("alpha") }.hasMessageContaining("loadTimedExits parse failed for alpha")
        assertThatThrownBy { p.loadOrderIdMarks("alpha") }.hasMessageContaining("loadOrderIdMarks parse failed for alpha")
    }

    @Test
    fun `files from another schema version are rejected`(
        @TempDir tmp: Path,
    ) {
        saveAll(tmp)
        listOf("timed-exits.json", "order-ids.json", "exit-hooks.json").forEach { tmp.resolve("alpha/$it").bumpVersion() }
        val p = FileStatePersistor(tmp)

        assertThatThrownBy { p.loadTimedExits("alpha") }.hasMessageContaining("loadTimedExits schema mismatch")
        assertThatThrownBy { p.loadOrderIdMarks("alpha") }.hasMessageContaining("loadOrderIdMarks schema mismatch")
        assertThatThrownBy { p.loadExitHooks("alpha") }.hasMessageContaining("loadExitHooks schema mismatch")
    }

    @Test
    fun `an exit hook file copied from another strategy is rejected`(
        @TempDir tmp: Path,
    ) {
        saveAll(tmp)
        val beta = Files.createDirectories(tmp.resolve("beta"))
        Files.copy(tmp.resolve("alpha/exit-hooks.json"), beta.resolve("exit-hooks.json"))

        assertThatThrownBy { FileStatePersistor(tmp).loadExitHooks("beta") }
            .hasMessageContaining("loadExitHooks strategy mismatch: file=alpha, requested=beta")
    }
}
