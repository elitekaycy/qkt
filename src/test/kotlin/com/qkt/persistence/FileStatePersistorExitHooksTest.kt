package com.qkt.persistence

import com.qkt.common.Side
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorExitHooksTest {
    @Test
    fun `exit hook bindings round trip exactly`(
        @TempDir temp: Path,
    ) {
        val binding =
            PersistedExitHookBinding(
                bindingId = "order-1:hook-1",
                strategyId = "alpha",
                symbol = "EXNESS:XAUUSD",
                entrySide = Side.BUY,
                definitionId = "hook-1",
                fingerprint = "abc123",
                entryOrderIds = listOf("order-1"),
                stopOrderIds = listOf("order-1-sl"),
                takeProfitOrderIds = listOf("order-1-tp"),
                closeOrderIds = listOf("manual-close-1"),
                brokerTickets = listOf("424242"),
                activeQuantity = BigDecimal("0.75"),
                exitQuantity = BigDecimal("0.25"),
                exitPnl = BigDecimal("-12.34"),
            )

        FileStatePersistor(temp).saveExitHooks("alpha", listOf(binding))

        assertThat(FileStatePersistor(temp).loadExitHooks("alpha")).containsExactly(binding)
        assertThat(temp.resolve("alpha/exit-hooks.json")).exists()
    }

    @Test
    fun `corrupt exit hook journal fails closed`(
        @TempDir temp: Path,
    ) {
        val strategyDir = Files.createDirectories(temp.resolve("alpha"))
        Files.writeString(strategyDir.resolve("exit-hooks.json"), "{not-json")

        assertThatThrownBy { FileStatePersistor(temp).loadExitHooks("alpha") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("loadExitHooks parse failed")
    }
}
