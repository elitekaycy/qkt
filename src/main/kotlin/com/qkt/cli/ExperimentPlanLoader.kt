package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/** Loads an [ExperimentPlan] from a research-plan YAML file, accepting snake_case and camelCase keys. */
object ExperimentPlanLoader {
    fun load(path: Path): ExperimentPlan {
        require(Files.exists(path)) { "plan file not found: $path" }
        val raw = Files.readString(path)

        @Suppress("UNCHECKED_CAST")
        val root =
            Load(LoadSettings.builder().build()).loadFromString(raw) as? Map<String, Any?>
                ?: throw IllegalArgumentException("plan must be a YAML mapping")
        return ExperimentPlan(
            name = string(root, "name", required = true)!!,
            objective = string(root, "objective", required = false),
            strategy = string(root, "strategy", required = false),
            dataset = string(root, "dataset", required = false),
            primaryMetric =
                string(root, "primary_metric", required = false)
                    ?: string(root, "primaryMetric", required = false)
                    ?: throw IllegalArgumentException("missing required field: primary_metric"),
            secondaryMetrics = stringList(root["secondary_metrics"] ?: root["secondaryMetrics"]),
            splits = splits(root["splits"]),
            constraints = stringMap(root["constraints"]),
            parameterGrid =
                grid(
                    root["parameter_grid"]
                        ?: root["param_grid"]
                        ?: root["grid"],
                ),
            selection = selection(root["selection"]),
            promotion = promotion(root["promotion"]),
            seed = root["seed"]?.toString()?.toLongOrNull(),
        )
    }

    private fun string(
        root: Map<String, Any?>,
        key: String,
        required: Boolean,
    ): String? {
        val value = root[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (required && value == null) throw IllegalArgumentException("missing required field: $key")
        return value
    }

    @Suppress("UNCHECKED_CAST")
    private fun splits(raw: Any?): ResearchSplits {
        val map = raw as? Map<String, Any?> ?: throw IllegalArgumentException("missing required field: splits")
        return ResearchSplits(
            train = window("splits.train", map["train"]),
            validation = window("splits.validation", map["validation"]),
            test = window("splits.test", map["test"]),
        )
    }

    private fun window(
        label: String,
        raw: Any?,
    ): ResearchWindow {
        val spec =
            raw
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("missing required field: $label")
        val parts = spec.split("/", limit = 2)
        require(parts.size == 2) { "$label must be formatted as from/to" }
        return ResearchWindow(
            from = BacktestContext.parseInstant(parts[0].trim()),
            to = BacktestContext.parseInstant(parts[1].trim()),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun selection(raw: Any?): ExperimentSelectionPlan {
        val map = raw as? Map<String, Any?> ?: return ExperimentSelectionPlan()
        return ExperimentSelectionPlan(
            method = map["method"]?.toString()?.takeIf { it.isNotBlank() } ?: "validation_rank_then_test_once",
            topN = map["top_n"]?.toString()?.toIntOrNull() ?: map["topN"]?.toString()?.toIntOrNull() ?: 3,
            largeSearchThreshold =
                map["large_search_threshold"]?.toString()?.toIntOrNull()
                    ?: map["largeSearchThreshold"]?.toString()?.toIntOrNull()
                    ?: ResearchGovernance.DEFAULT_LARGE_SEARCH_THRESHOLD,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun promotion(raw: Any?): ExperimentPromotionPlan? {
        val map = raw as? Map<String, Any?> ?: return null
        return ExperimentPromotionPlan(
            state = map["state"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            rationale = map["rationale"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringMap(raw: Any?): Map<String, String> {
        val map = raw as? Map<String, Any?> ?: return emptyMap()
        return map.entries.associate { it.key to (it.value?.toString() ?: "") }
    }

    private fun stringList(raw: Any?): List<String> =
        when (raw) {
            null -> emptyList()
            is List<*> -> raw.map { it?.toString() ?: "" }.filter { it.isNotBlank() }
            else ->
                raw
                    .toString()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
        }

    @Suppress("UNCHECKED_CAST")
    private fun grid(raw: Any?): Map<String, List<String>> {
        val map = raw as? Map<String, Any?> ?: return emptyMap()
        return map.entries.associate { (name, values) ->
            val axisName = name.trim()
            require(axisName.isNotEmpty()) { "parameter_grid contains an empty parameter name" }
            val axisValues =
                when (values) {
                    is List<*> -> values.map { it?.toString() ?: "" }
                    null -> emptyList()
                    else -> values.toString().split(",")
                }.map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            require(axisValues.isNotEmpty()) { "parameter_grid.$axisName must have at least one value" }
            axisName to axisValues
        }
    }
}
