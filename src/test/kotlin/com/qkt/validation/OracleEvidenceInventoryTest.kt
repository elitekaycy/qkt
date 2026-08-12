package com.qkt.validation

import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.dsl.stdlib.IndicatorRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OracleEvidenceInventoryTest {
    private val inventory: JsonObject =
        requireNotNull(javaClass.getResourceAsStream("/validation/oracle-evidence.json")) {
            "missing oracle evidence inventory"
        }.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }

    @Test
    fun `oracle inventory covers every registered indicator and numeric function exactly once`() {
        assertThat(inventory.getValue("schema").jsonPrimitive.content)
            .isEqualTo("qkt-validation-oracle-evidence-v1")
        assertThat(inventory.getValue("evidenceStatus").jsonPrimitive.content)
            .isEqualTo("behavioral-oracle-only")

        val categories = inventory.getValue("categories").jsonObject
        assertCoverage(categories.getValue("indicators").jsonArray, IndicatorRegistry.names())
        assertCoverage(categories.getValue("numericFunctions").jsonArray, FuncRegistry.names())
    }

    private fun assertCoverage(
        records: JsonArray,
        expected: Set<String>,
    ) {
        val covered = mutableListOf<String>()
        for (element in records) {
            val record = element.jsonObject
            val capabilities = record.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }
            val strength = record.getValue("strength").jsonPrimitive.content
            val evidence = record.getValue("evidence").jsonArray.map { it.jsonPrimitive.content }

            assertThat(capabilities).isNotEmpty.doesNotHaveDuplicates()
            assertThat(strength).isIn("exact_numeric", "state_transition")
            assertThat(evidence).isNotEmpty.doesNotHaveDuplicates()
            evidence.forEach { path ->
                assertThat(Files.isRegularFile(Path.of(path)))
                    .`as`("oracle evidence %s", path)
                    .isTrue()
            }
            covered += capabilities
        }

        assertThat(covered).doesNotHaveDuplicates()
        assertThat(covered.toSet()).isEqualTo(expected)
    }
}
