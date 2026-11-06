package com.qkt.persistence

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StateFileRejectionMoreTest {
    private fun Path.bumpVersion() = writeText(readText().replace(Regex("\"version\":\\s*\\d+"), "\"version\":-1"))

    @Test
    fun `order fills and structures files that do not parse fail the load`(
        @TempDir tmp: Path,
    ) {
        val dir = Files.createDirectories(tmp.resolve("alpha"))
        dir.resolve("order-fills.json").writeText("]")
        dir.resolve("structures.json").writeText("]")
        val p = FileStatePersistor(tmp)

        assertThatThrownBy { p.loadOrderFills("alpha") }.hasMessageContaining("loadOrderFills parse failed for alpha")
        assertThatThrownBy { p.loadStructures("alpha") }.hasMessageContaining("loadStructures parse failed for alpha")
    }

    @Test
    fun `order fills, structures and sequences from another schema version are rejected`(
        @TempDir tmp: Path,
    ) {
        val p = FileStatePersistor(tmp)
        p.saveOrderFills("alpha", mapOf("o-1" to PersistedOrderFill(BigDecimal("0.1"), BigDecimal("4700"), "77")))
        p.saveStructures("alpha", emptyList())
        p.saveSequences("alpha", emptyMap())
        assertThat(
            p.loadOrderFills("alpha"),
        ).containsEntry("o-1", PersistedOrderFill(BigDecimal("0.1"), BigDecimal("4700"), "77"))
        listOf(
            "order-fills.json",
            "structures.json",
            "sequences.json",
        ).forEach { tmp.resolve("alpha/$it").bumpVersion() }

        assertThatThrownBy { p.loadOrderFills("alpha") }.hasMessageContaining("loadOrderFills schema mismatch")
        assertThatThrownBy { p.loadStructures("alpha") }.hasMessageContaining("loadStructures schema mismatch")
        assertThatThrownBy { p.loadSequences("alpha") }.hasMessageContaining("loadSequences schema mismatch")
    }

    @Test
    fun `a pending order intent that cannot be written durably throws`(
        @TempDir tmp: Path,
    ) {
        val root = Files.writeString(tmp.resolve("not-a-dir"), "x")

        assertThatThrownBy { FileStatePersistor(root).savePendingOrdersSync("alpha", emptyMap()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("durable pending-order intent write failed for alpha")
    }

    @Test
    fun `an unknown carry step kind is rejected by name`() {
        val dto = CarryStepDto(kind = "Drifting", strategyId = "alpha", quantity = "1")

        assertThatThrownBy { dto.toDomain() }.hasMessageContaining("unknown carry step in persisted state: Drifting")
    }
}
