package com.qkt.marketdata.ingest.plan

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Reads and writes [ImportDescriptor]s as YAML. Keys are strict: a misspelled key in a preset fails
 * naming it instead of silently meaning nothing. Writing is hand-rolled in a fixed key order with every
 * string double-quoted, so `--plan` output is stable and survives names like `<DATE>` or `$3`.
 */
object DescriptorYaml {
    private val KEYS =
        setOf(
            "id",
            "description",
            "kind",
            "format",
            "delimiter",
            "decimal",
            "rows",
            "where",
            "time",
            "timeFormat",
            "clock",
            "timeframe",
            "label",
            "basis",
            "fields",
            "available",
            "availableLag",
            "availableFormat",
            "digits",
            "volume",
            "carryForward",
            "float32",
            "match",
        )
    private val MATCH_KEYS = setOf("header", "columns", "firstCell", "filename", "delimiter", "json")

    /** The descriptor in [text]; [source] names the file in errors. */
    fun decode(
        text: String,
        source: String,
    ): ImportDescriptor {
        val root = Load(LoadSettings.builder().build()).loadFromString(text)
        require(root is Map<*, *>) { "$source: a preset must be a YAML map" }
        val unknown = root.keys.map { it.toString() } - KEYS
        require(
            unknown.isEmpty(),
        ) { "$source: unknown key(s) ${unknown.joinToString()} (known: ${KEYS.joinToString()})" }

        fun str(key: String) = root[key]?.toString()
        return ImportDescriptor(
            id = str("id") ?: "custom",
            description = str("description"),
            kind = str("kind")?.let(IngestKind::parse),
            json =
                str("format")?.let {
                    require(it == "csv" || it == "json") { "$source: format must be csv or json" }
                    it == "json"
                },
            delimiter = str("delimiter")?.let { char(it, source) },
            decimal = str("decimal")?.let { char(it, source) },
            rows = list(root["rows"]),
            where = map(root["where"], source, "where"),
            time = list(root["time"]),
            timeFormat = str("timeFormat"),
            clock = str("clock"),
            timeframe = str("timeframe"),
            label = str("label")?.let { BarLabel.valueOf(it.uppercase()) },
            basis = str("basis")?.let { PriceBasis.valueOf(it.uppercase()) },
            fields = map(root["fields"], source, "fields"),
            available = str("available"),
            availableLag = str("availableLag"),
            availableFormat = str("availableFormat"),
            digits = str("digits")?.toInt(),
            volume = str("volume"),
            carryForward = str("carryForward")?.toBooleanStrict(),
            float32 = str("float32")?.toBooleanStrict(),
            match = (root["match"] as? Map<*, *>)?.let { match(it, source) },
        )
    }

    /** [descriptor] as YAML that [decode] reads back to an equal descriptor. */
    fun encode(descriptor: ImportDescriptor): String =
        buildString {
            fun line(
                key: String,
                value: Any?,
            ) {
                when (value) {
                    null -> Unit
                    is List<*> ->
                        if (value.isNotEmpty()) {
                            appendLine(
                                "$key: [${value.joinToString { q(it.toString()) }}]",
                            )
                        }
                    is Map<*, *> ->
                        if (value.isNotEmpty()) {
                            appendLine("$key:")
                            value.forEach { (k, v) -> appendLine("  ${q(k.toString())}: ${q(v.toString())}") }
                        }
                    is Int, is Boolean -> appendLine("$key: $value")
                    else -> appendLine("$key: ${q(value.toString())}")
                }
            }
            line("id", descriptor.id)
            line("description", descriptor.description)
            line("kind", descriptor.kind?.spec)
            line("format", descriptor.json?.let { if (it) "json" else "csv" })
            line("delimiter", descriptor.delimiter?.toString())
            line("decimal", descriptor.decimal?.toString())
            line("rows", descriptor.rows)
            line("where", descriptor.where)
            line("time", descriptor.time)
            line("timeFormat", descriptor.timeFormat)
            line("clock", descriptor.clock)
            line("timeframe", descriptor.timeframe)
            line("label", descriptor.label?.name?.lowercase())
            line("basis", descriptor.basis?.name?.lowercase())
            line("fields", descriptor.fields.toSortedMap())
            line("available", descriptor.available)
            line("availableLag", descriptor.availableLag)
            line("availableFormat", descriptor.availableFormat)
            line("digits", descriptor.digits)
            line("volume", descriptor.volume)
            line("carryForward", descriptor.carryForward)
            line("float32", descriptor.float32)
        }

    private fun match(
        raw: Map<*, *>,
        source: String,
    ): PresetMatch {
        val unknown = raw.keys.map { it.toString() } - MATCH_KEYS
        require(unknown.isEmpty()) { "$source: unknown match key(s) ${unknown.joinToString()}" }
        return PresetMatch(
            header = list(raw["header"]),
            columns = raw["columns"]?.toString()?.toInt(),
            firstCell = raw["firstCell"]?.toString(),
            filename = raw["filename"]?.toString(),
            delimiter = raw["delimiter"]?.toString()?.let { char(it, source) },
            json = raw["json"]?.toString()?.toBooleanStrict() ?: false,
        )
    }

    private fun list(raw: Any?): List<String> =
        when (raw) {
            null -> emptyList()
            is List<*> -> raw.map { it.toString() }
            else -> listOf(raw.toString())
        }

    private fun map(
        raw: Any?,
        source: String,
        key: String,
    ): Map<String, String> {
        if (raw == null) return emptyMap()
        require(raw is Map<*, *>) { "$source: '$key' must be a map" }
        return raw.entries.associate { (k, v) -> k.toString() to v.toString() }
    }

    private fun char(
        text: String,
        source: String,
    ): Char {
        val c = if (text == "tab") "\t" else text
        require(c.length == 1) { "$source: '$text' must be a single character (or 'tab')" }
        return c[0]
    }

    private fun q(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "\\t") + "\""
}
