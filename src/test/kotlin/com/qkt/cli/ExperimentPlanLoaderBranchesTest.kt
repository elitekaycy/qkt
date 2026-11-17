package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExperimentPlanLoaderBranchesTest {
    private val splits =
        """
        splits:
          train: 2020-01-01/2021-01-01
          validation: 2021-01-01/2022-01-01
          test: 2022-01-01T00:00:00/2023-01-01T00:00:00Z
        """.trimIndent()

    private fun load(yaml: String): ExperimentPlan {
        val file = createTempDirectory("plan").resolve("plan.yaml")
        Files.writeString(file, yaml)
        return ExperimentPlanLoader.load(file)
    }

    private fun refusal(yaml: String): String =
        assertFailsWith<IllegalArgumentException> { load(yaml) }.message.orEmpty()

    private val base = "name: p\nprimary_metric: sharpe\n$splits\n"

    @Test
    fun minimalPlanGetsDefaults() {
        val plan = load(base)
        assertEquals("p", plan.name)
        assertNull(plan.objective)
        assertNull(plan.strategy)
        assertNull(plan.dataset)
        assertNull(plan.promotion)
        assertNull(plan.seed)
        assertTrue(plan.secondaryMetrics.isEmpty() && plan.constraints.isEmpty() && plan.parameterGrid.isEmpty())
        assertEquals(ExperimentSelectionPlan(), plan.selection)
        assertEquals(Instant.parse("2022-01-01T00:00:00Z"), plan.splits.test.from)
    }

    @Test
    fun fullPlanInSnakeCase() {
        val plan =
            load(
                """
                name: " full "
                objective: o
                strategy: s.qkt
                dataset: d
                primary_metric: sharpe
                secondary_metrics: [calmar, " ", sortino]
                constraints: {maxDd: 5, note: null}
                param_grid:
                  fast: [1, 2, 2]
                  slow: "10, 20"
                  gap: [3, ""]
                selection: {method: m, top_n: 2, large_search_threshold: 9}
                promotion: {state: candidate, rationale: ok}
                seed: 42
                """.trimIndent() + "\n$splits\n",
            )
        assertEquals("full", plan.name)
        assertEquals(listOf("calmar", "sortino"), plan.secondaryMetrics)
        assertEquals(mapOf("maxDd" to "5", "note" to ""), plan.constraints)
        assertEquals(listOf("1", "2"), plan.parameterGrid["fast"])
        assertEquals(listOf("10", "20"), plan.parameterGrid["slow"])
        assertEquals(listOf("3"), plan.parameterGrid["gap"])
        assertEquals(ExperimentSelectionPlan("m", 2, 9), plan.selection)
        assertEquals(ExperimentPromotionPlan("candidate", "ok"), plan.promotion)
        assertEquals(42L, plan.seed)
    }

    @Test
    fun camelCaseAliasesAreAccepted() {
        val plan =
            load(
                "name: p\nprimaryMetric: sharpe\nsecondaryMetrics: 'a, b'\nparameter_grid: {x: [1]}\n" +
                    "selection: {topN: 4, largeSearchThreshold: 7}\ngrid: {y: [9]}\nseed: abc\n$splits\n",
            )
        assertEquals(listOf("a", "b"), plan.secondaryMetrics)
        assertEquals(listOf("x"), plan.parameterGrid.keys.toList())
        assertEquals(ExperimentSelectionPlan(topN = 4, largeSearchThreshold = 7), plan.selection)
        assertNull(plan.seed)
        val viaGrid = load("name: p\nprimary_metric: sharpe\ngrid: {y: [9]}\n$splits\n")
        assertEquals(listOf("y"), viaGrid.parameterGrid.keys.toList())
    }

    @Test
    fun blankSelectionAndPromotionFieldsFallBack() {
        val plan = load("$base" + "selection: {method: ' ', top_n: x}\npromotion: {state: ' ', rationale: ''}\n")
        assertEquals(ExperimentSelectionPlan(), plan.selection)
        assertEquals(ExperimentPromotionPlan(null, null), plan.promotion)
        assertEquals(ExperimentSelectionPlan(), load("$base" + "selection: nope\n").selection)
    }

    @Test
    fun missingOrMalformedFieldsAreRefused() {
        assertTrue(
            "plan file not found" in
                assertFailsWith<IllegalArgumentException> {
                    ExperimentPlanLoader.load(Path.of("/nonexistent/plan.yaml"))
                }.message.orEmpty(),
        )
        assertEquals("plan must be a YAML mapping", refusal("- a\n- b\n"))
        assertEquals("missing required field: name", refusal("primary_metric: sharpe\n$splits\n"))
        assertEquals("missing required field: primary_metric", refusal("name: p\n$splits\n"))
        assertEquals("missing required field: splits", refusal("name: p\nprimary_metric: sharpe\n"))
        assertEquals(
            "missing required field: splits.validation",
            refusal("name: p\nprimary_metric: sharpe\nsplits: {train: 2020-01-01/2021-01-01}\n"),
        )
        assertEquals(
            "splits.train must be formatted as from/to",
            refusal("name: p\nprimary_metric: sharpe\nsplits: {train: 2020-01-01, validation: a, test: b}\n"),
        )
        assertEquals(
            "parameter_grid contains an empty parameter name",
            refusal("$base" + "param_grid: {' ': [1]}\n"),
        )
        assertEquals(
            "parameter_grid.a must have at least one value",
            refusal("$base" + "param_grid: {a: []}\n"),
        )
        assertEquals(
            "parameter_grid.b must have at least one value",
            refusal("$base" + "param_grid: {b: null}\n"),
        )
    }
}
