package com.qkt.validation

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.dsl.stdlib.IndicatorRegistry
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
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

class CapabilityCatalogTest {
    private val catalog: JsonObject =
        requireNotNull(javaClass.getResourceAsStream("/validation/capability-catalog.json")) {
            "missing validation capability catalog"
        }.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }

    @Test
    fun `catalog classifies every required evidence axis`() {
        assertThat(catalog.getValue("schema").jsonPrimitive.content)
            .isEqualTo("qkt-validation-capability-catalog-v2")
        assertThat(catalog.getValue("evidenceStatus").jsonPrimitive.content).isEqualTo("classified-inventory")

        val axes = strings(catalog.getValue("axes").jsonArray)
        assertThat(axes).containsExactly(
            "oracle",
            "dsl",
            "ticks",
            "bars",
            "tickResolvedBars",
            "livePaper",
            "mt5Demo",
            "reports",
            "journal",
            "insights",
            "portfolio",
        )

        val profiles = catalog.getValue("evidenceProfiles").jsonObject
        for ((name, value) in catalog.getValue("categories").jsonObject) {
            val category = value.jsonObject
            val capabilities = strings(category.getValue("capabilities").jsonArray)
            val requiredAxes = strings(category.getValue("requiredAxes").jsonArray)
            val profileName = category.getValue("evidenceProfile").jsonPrimitive.content
            val profile = profiles.getValue(profileName).jsonObject
            val passed = profile.getValue("passed").jsonObject
            val gaps = profile.getValue("gaps").jsonObject
            val notApplicable = profile.getValue("notApplicable").jsonObject
            val classifiedAxes = passed.keys.toList() + gaps.keys + notApplicable.keys

            assertThat(category.getValue("source").jsonPrimitive.content).`as`("%s source", name).isNotBlank()
            assertThat(capabilities).`as`("%s capabilities", name).isNotEmpty.doesNotHaveDuplicates()
            assertThat(requiredAxes).`as`("%s required axes", name).isNotEmpty.doesNotHaveDuplicates()
            assertThat(requiredAxes).`as`("%s unknown axes", name).isSubsetOf(axes)
            assertThat(classifiedAxes).`as`("%s classified axes", name).doesNotHaveDuplicates()
            assertThat(classifiedAxes.toSet()).`as`("%s evidence coverage", name).isEqualTo(requiredAxes.toSet())

            for ((axis, evidenceValue) in passed) {
                val evidence = strings(evidenceValue.jsonArray)
                assertThat(evidence).`as`("%s %s pass evidence", name, axis).isNotEmpty.doesNotHaveDuplicates()
                evidence.forEach { path ->
                    assertThat(Files.isRegularFile(Path.of(path)))
                        .`as`("%s %s evidence %s", name, axis, path)
                        .isTrue()
                }
            }
            for ((axis, reason) in gaps) {
                assertThat(reason.jsonPrimitive.content).`as`("%s %s gap reason", name, axis).isNotBlank()
            }
            for ((axis, reason) in notApplicable) {
                assertThat(reason.jsonPrimitive.content).`as`("%s %s not-applicable reason", name, axis).isNotBlank()
            }
        }
    }

    @Test
    fun `catalog exactly matches registered and sealed runtime surfaces`() {
        assertCategory("indicators", IndicatorRegistry.names())
        assertCategory("numericFunctions", FuncRegistry.names())
        assertCategory("expressions", permittedNames(ExprAst::class.java))
        assertCategory("actions", permittedNames(ActionAst::class.java))
        assertCategory("sizing", permittedNames(SizingAst::class.java))
        assertCategory("dslOrderTypes", permittedNames(OrderTypeAst::class.java))
        assertCategory("normalizedOrders", permittedNames(OrderRequest::class.java))
        assertCategory("timeInForce", TimeInForce.entries.map { it.name }.toSet())
    }

    private fun assertCategory(
        category: String,
        actual: Set<String>,
    ) {
        val expected =
            strings(
                catalog
                    .getValue("categories")
                    .jsonObject
                    .getValue(category)
                    .jsonObject
                    .getValue("capabilities")
                    .jsonArray,
            ).toSet()
        assertThat(expected).`as`("%s catalog drift", category).isEqualTo(actual)
    }

    private fun permittedNames(type: Class<*>): Set<String> = type.permittedSubclasses.map { it.simpleName }.toSet()

    private fun strings(array: JsonArray): List<String> = array.map { it.jsonPrimitive.content }
}
