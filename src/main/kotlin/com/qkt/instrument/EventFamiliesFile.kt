package com.qkt.instrument

import java.nio.file.Files
import java.nio.file.Path
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Reads the `events:` section of an `instruments.yaml`. Keys are checked strictly, like `futures:` and `options:`: a
 * typo in a tick or volume step must fail the load, not fall back to a default.
 */
object EventFamiliesFile {
    private val KEYS = listOf("root", "currency", "tickSize", "volumeStep", "volumeMin")
    private val fields = RootFields("events")

    /** Every family declared in [path], in file order; empty when the file has no `events:` section. */
    fun load(path: Path): List<EventFamily> {
        val document = Load(LoadSettings.builder().build()).loadFromString(Files.readString(path))
        check(document is Map<*, *>) { "$path: top-level must be a map" }
        val nearMiss = RootFields.misspelledSection(document.keys, "events")
        require(nearMiss == null) { "$path: unknown section '$nearMiss'; did you mean 'events:'?" }
        val list = document["events"] ?: return emptyList()
        check(list is List<*>) { "$path: 'events' must be a list" }
        val families =
            list.mapIndexed { i, raw -> parse(raw as? Map<*, *> ?: error("$path: events entry $i must be a map"), i) }
        val duplicate = families.groupBy { it.root }.entries.firstOrNull { it.value.size > 1 }
        check(duplicate == null) { "$path: duplicate events root '${duplicate?.key}'" }
        return families
    }

    private fun parse(
        entry: Map<*, *>,
        index: Int,
    ): EventFamily {
        val name = entry["root"]?.toString() ?: "events entry $index"
        val unknown = entry.keys.map { it.toString() }.filter { it !in KEYS }
        require(unknown.isEmpty()) { "events root $name: unknown key(s) $unknown; allowed: $KEYS" }

        fun req(key: String): String = entry[key]?.toString() ?: error("events root $name: missing required key '$key'")

        fun num(key: String) = fields.number(name, key, req(key))
        try {
            return EventFamily(req("root"), req("currency"), num("tickSize"), num("volumeStep"), num("volumeMin"))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("events root $name: ${e.message}", e)
        }
    }
}
