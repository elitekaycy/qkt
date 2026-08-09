package com.qkt.validation

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.dsl.stdlib.IndicatorRegistry
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
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
    fun `catalog is an inventory and every category declares valid evidence axes`() {
        assertThat(catalog.getValue("schema").jsonPrimitive.content)
            .isEqualTo("qkt-validation-capability-catalog-v1")
        assertThat(catalog.getValue("evidenceStatus").jsonPrimitive.content).isEqualTo("inventory-only")

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

        for ((name, value) in catalog.getValue("categories").jsonObject) {
            val category = value.jsonObject
            val capabilities = strings(category.getValue("capabilities").jsonArray)
            val requiredAxes = strings(category.getValue("requiredAxes").jsonArray)
            assertThat(category.getValue("source").jsonPrimitive.content).`as`("%s source", name).isNotBlank()
            assertThat(capabilities).`as`("%s capabilities", name).isNotEmpty.doesNotHaveDuplicates()
            assertThat(requiredAxes).`as`("%s required axes", name).isNotEmpty.doesNotHaveDuplicates()
            assertThat(requiredAxes).`as`("%s unknown axes", name).isSubsetOf(axes)
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
