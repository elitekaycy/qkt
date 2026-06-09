package com.qkt.cli

/** Parses repeated `--param NAME=v1,v2,...` flags into cartesian-product combos. */
object ParamGrid {
    data class Combo(
        val label: String,
        val overrides: Map<String, String>,
    )

    /** NAME -> ordered, de-duplicated value list. */
    fun parseAxes(tokens: List<String>): Map<String, List<String>> {
        val axes = LinkedHashMap<String, MutableList<String>>()
        for (tok in tokens) {
            val eq = tok.indexOf('=')
            require(eq > 0) { "bad --param '$tok'; expected NAME=VALUE" }
            val name = tok.substring(0, eq).trim()
            require(name.isNotEmpty()) { "bad --param '$tok'; empty name" }
            val values =
                tok
                    .substring(eq + 1)
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            require(values.isNotEmpty()) { "bad --param '$tok'; no value" }
            axes.getOrPut(name) { mutableListOf() }.addAll(values)
        }
        return axes.mapValues { (_, v) -> v.distinct() }
    }

    fun expand(axes: Map<String, List<String>>): List<Combo> {
        if (axes.isEmpty()) return listOf(Combo(label = "default", overrides = emptyMap()))
        var combos = listOf(emptyMap<String, String>())
        for ((name, values) in axes) {
            val next = mutableListOf<Map<String, String>>()
            for (acc in combos) {
                for (v in values) next.add(acc + (name to v))
            }
            combos = next
        }
        return combos.map { ov ->
            Combo(
                label = ov.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" },
                overrides = ov,
            )
        }
    }

    fun parse(tokens: List<String>): List<Combo> = expand(parseAxes(tokens))
}
