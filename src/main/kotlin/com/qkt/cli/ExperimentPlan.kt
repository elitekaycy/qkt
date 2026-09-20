package com.qkt.cli

import java.time.Instant

/** A half-open research time window `[from, to)`; [spec] renders it as `from/to`. */
data class ResearchWindow(
    val from: Instant,
    val to: Instant,
) {
    init {
        require(from.isBefore(to)) { "split window must have from < to: $from/$to" }
    }

    val spec: String
        get() = "$from/$to"
}

/** Train, validation and test windows, required to run in that order without overlap. */
data class ResearchSplits(
    val train: ResearchWindow,
    val validation: ResearchWindow,
    val test: ResearchWindow,
) {
    init {
        require(!train.to.isAfter(validation.from)) { "train split overlaps validation split" }
        require(!validation.to.isAfter(test.from)) { "validation split overlaps test split" }
    }

    fun asEvidenceMap(): Map<String, String> =
        linkedMapOf(
            "train" to train.spec,
            "validation" to validation.spec,
            "test" to test.spec,
        )
}

/** How an experiment picks its candidate: train leaders sent to validation and the large-search threshold. */
data class ExperimentSelectionPlan(
    val method: String = "validation_rank_then_test_once",
    val topN: Int = 3,
    val largeSearchThreshold: Int = ResearchGovernance.DEFAULT_LARGE_SEARCH_THRESHOLD,
) {
    init {
        require(method.isNotBlank()) { "selection.method must not be blank" }
        require(topN > 0) { "selection.top_n must be positive" }
        require(largeSearchThreshold > 0) { "selection.large_search_threshold must be positive" }
    }
}

/** Optional promotion intent recorded with an experiment; [state] must be a known lifecycle state. */
data class ExperimentPromotionPlan(
    val state: String? = null,
    val rationale: String? = null,
) {
    init {
        val valid =
            setOf(
                "draft",
                "research",
                "candidate",
                "paper",
                "shadow-live",
                "small-capital",
                "production",
                "retired",
            )
        require(state == null || state in valid) {
            "promotion.state must be one of ${valid.joinToString(", ")}"
        }
    }
}

/** A governed research plan: strategy, dataset, metric, splits, parameter grid and selection rules. */
data class ExperimentPlan(
    val name: String,
    val objective: String? = null,
    val strategy: String? = null,
    val dataset: String? = null,
    val primaryMetric: String,
    val secondaryMetrics: List<String> = emptyList(),
    val splits: ResearchSplits,
    val constraints: Map<String, String> = emptyMap(),
    val parameterGrid: Map<String, List<String>> = emptyMap(),
    val selection: ExperimentSelectionPlan = ExperimentSelectionPlan(),
    val promotion: ExperimentPromotionPlan? = null,
    val seed: Long? = null,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        RankMetric.fromFlag(primaryMetric)
        ParamGrid.expand(parameterGrid)
    }
}
