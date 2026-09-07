package com.qkt.marketdata.hub

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What a hub store says it holds: which datasets exist, and which fields each one declares.
 *
 * The hub writes the whole field table into its manifest precisely so a consumer needs no YAML
 * parser to know what a dataset contains. That is what makes it possible to catch a mistyped
 * field name before a run starts rather than after it produces a report.
 */
data class HubManifest(
    val datasets: Map<String, HubDataset>,
) {
    companion object {
        fun load(root: Path): HubManifest? {
            val path = root.resolve("manifest.json")
            if (!Files.isRegularFile(path)) return null
            val parsed =
                runCatching { Json.parseToJsonElement(Files.readString(path)) as? JsonObject }
                    .getOrNull() ?: return null
            val datasets = parsed["datasets"] as? JsonObject ?: return HubManifest(emptyMap())
            val out = LinkedHashMap<String, HubDataset>()
            for ((name, element) in datasets) {
                val entry = element as? JsonObject ?: continue
                val fields =
                    (entry["fields"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { field ->
                            val obj = field.jsonObject
                            val fieldName = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                            HubField(
                                name = fieldName,
                                type = (obj["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                                strategy = (obj["strategy"] as? JsonPrimitive)?.booleanOrNull ?: true,
                            )
                        }.orEmpty()
                out[name] =
                    HubDataset(
                        name = name,
                        schemaHash = (entry["schema_hash"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        scopeKind = (entry["scope_kind"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        fields = fields,
                    )
            }
            return HubManifest(out)
        }
    }
}

/** One dataset's declared shape, as the hub recorded it when it last compiled. */
data class HubDataset(
    val name: String,
    val schemaHash: String,
    val scopeKind: String,
    val fields: List<HubField>,
) {
    /**
     * Fields a strategy may read. A free-text field is excluded deliberately: it exists for a
     * human reading a log, and a rule comparing against it would be comparing against prose.
     */
    fun strategyFieldNames(): List<String> = fields.filter { it.strategy }.map { it.name }
}

/** One declared field: enough to say whether a strategy may name it. */
data class HubField(
    val name: String,
    val type: String,
    val strategy: Boolean,
)

/**
 * Check every `HUB:` stream a run declares against what the store actually holds.
 *
 * A mistyped field would otherwise be silently undefined for the whole run: the rule referencing
 * it never fires, the backtest completes, and the report looks like a strategy that found no
 * setups rather than one that was never able to. Failing here, before a single tick is read, turns
 * that into an error an operator sees.
 *
 * Returns the problems found, empty when everything resolves. A store with no manifest yet is not
 * a problem: the journal is authoritative and a freshly collected store may not have compiled, so
 * this reports what it can and stays quiet about what it cannot know.
 */
fun validateHubStreams(
    root: Path,
    symbols: Iterable<String>,
): List<String> {
    val hubSymbols = symbols.filter { it.startsWith(HubMarketSource.PREFIX) }
    if (hubSymbols.isEmpty()) return emptyList()

    val problems = mutableListOf<String>()
    if (!Files.isDirectory(root.resolve("journal"))) {
        problems.add(
            "hub store not found at $root (set ${HubMarketSource.ROOT_ENV}); " +
                "declared hub stream(s): ${hubSymbols.sorted().joinToString(", ")}",
        )
        return problems
    }

    val manifest = HubManifest.load(root) ?: return problems
    if (manifest.datasets.isEmpty()) return problems

    for (symbol in hubSymbols.sorted()) {
        val binding = runCatching { HubStreamSymbol.parse(symbol) }.getOrNull() ?: continue
        val dataset = manifest.datasets[binding.dataset]
        if (dataset == null) {
            problems.add(
                "hub dataset '${binding.dataset}' is not in the store's manifest; " +
                    "known: ${manifest.datasets.keys.sorted().joinToString(", ")}",
            )
            continue
        }
        val known = dataset.strategyFieldNames()
        if (binding.field !in known) {
            problems.add(
                "hub dataset '${binding.dataset}' has no readable field '${binding.field}'; " +
                    "declared: ${known.sorted().joinToString(", ")}",
            )
        }
    }
    return problems
}
