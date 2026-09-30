package com.qkt.cli

import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.stdlib.Constants
import com.qkt.dsl.stdlib.FuncArity
import com.qkt.dsl.stdlib.FuncRegistry
import com.qkt.dsl.stdlib.IndicatorRegistry
import com.qkt.lsp.QktDocs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renders the DSL vocabulary as JSON (schema `qkt-vocabulary-v1`) for tooling that must not
 * keep its own copy of qkt's names: editors, grammar generators, the backtester. Every list is
 * sorted and every object's keys are fixed, so the same qkt build always prints the same bytes.
 */
object VocabularyJson {
    const val SCHEMA = "qkt-vocabulary-v1"

    /** Arity of the two indicators bound outside the registry: at least a series, a peer and a period. */
    private val EXTERNAL_INDICATOR_ARITY = FuncArity(3, variadic = true)

    private val CLOCK_PREDICATE_ARITY: Map<String, FuncArity> =
        mapOf(
            DslVocabulary.CALENDAR_WINDOW to FuncArity(4, variadic = false),
            DslVocabulary.SESSION_WINDOW to FuncArity(4, variadic = false),
            DslVocabulary.LAST_TRADING_DAY_OF_MONTH to FuncArity(0, variadic = false),
        )

    fun render(): String = document().toString()

    fun document(): JsonObject =
        buildJsonObject {
            put("schema", SCHEMA)
            put("keywords", strings(DslVocabulary.keywords))
            put(
                "keywordCategories",
                buildJsonObject {
                    for (category in com.qkt.dsl.parse.KeywordCategory.entries) {
                        put(
                            category.name,
                            strings(
                                com.qkt.dsl.parse.KeywordCategory
                                    .spellings(category)
                                    .sorted(),
                            ),
                        )
                    }
                },
            )
            put("indicators", indicators())
            put("functions", functions())
            put("constants", constants())
            put("streamFields", strings(DslVocabulary.candleFields.sorted()))
            put("metaFields", strings(DslVocabulary.metaFields.sorted()))
            put("seriesSelectors", strings(DslVocabulary.seriesSelectors.sorted()))
            put("members", members())
            put("shorthands", strings(DslVocabulary.rollingShorthands.map { it.lowercase() }.sorted()))
        }

    private fun indicators(): JsonArray {
        val registered =
            IndicatorRegistry.names().map { name ->
                val spec = IndicatorRegistry.spec(name) ?: error("unreachable: $name")
                indicator(name, FuncArity(spec.arity, variadic = false))
            }
        val external = DslVocabulary.externalIndicators.map { indicator(it, EXTERNAL_INDICATOR_ARITY) }
        return array(registered + external)
    }

    private fun functions(): JsonArray {
        val registered = FuncRegistry.names().map { function(it, FuncRegistry.arity(it) ?: error("unreachable: $it")) }
        val predicates = CLOCK_PREDICATE_ARITY.map { (name, arity) -> function(name, arity) }
        return array(registered + predicates)
    }

    private fun constants(): JsonArray =
        buildJsonArray {
            for (name in Constants.names().sorted()) {
                add(
                    buildJsonObject {
                        put("name", name)
                        put("value", Constants.byName(name)?.toPlainString())
                    },
                )
            }
        }

    private fun members(): JsonObject =
        buildJsonObject {
            for ((owner, table) in DslVocabulary.members.toSortedMap()) put(owner, strings(table.sorted()))
        }

    private fun indicator(
        name: String,
        arity: FuncArity,
    ): Entry {
        val doc = QktDocs.indicator(name) ?: ""
        return Entry(
            name.lowercase(),
            buildJsonObject {
                put("name", name.lowercase())
                put("arity", arity.min)
                put("variadic", arity.variadic)
                put("signature", QktDocs.signature(doc))
                put("doc", doc.substringAfter("```\n", ""))
            },
        )
    }

    private fun function(
        name: String,
        arity: FuncArity,
    ): Entry =
        Entry(
            name.lowercase(),
            buildJsonObject {
                put("name", name.lowercase())
                put("arity", arity.min)
                put("variadic", arity.variadic)
            },
        )

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    private fun array(entries: List<Entry>): JsonArray = JsonArray(entries.sortedBy { it.name }.map { it.json })

    /** One named array element, kept with its name so the array can be sorted before encoding. */
    private class Entry(
        val name: String,
        val json: JsonElement,
    )
}
