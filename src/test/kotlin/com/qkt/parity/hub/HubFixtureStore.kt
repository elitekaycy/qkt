package com.qkt.parity.hub

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories

/**
 * A hub store written the way qkt-data-hub writes one: an append-only journal per dataset, one
 * file per UTC day of `known_at`, a manifest naming every dataset's fields, and a heartbeat.
 * Scenario tests describe records; this turns them into the bytes the engine reads.
 */
internal class HubFixtureStore(
    val root: Path,
) {
    data class Field(
        val name: String,
        val type: String = "number",
        val strategy: Boolean = true,
    )

    data class Rec(
        val scope: String,
        val knownAt: Long,
        val fields: Map<String, String?>,
        val key: String = "$knownAt|$scope",
        val effectiveAt: Long = knownAt,
        val revision: Int = 1,
        val availability: String = "observed",
    )

    private val datasets = LinkedHashMap<String, Pair<List<Field>, List<Rec>>>()

    fun dataset(
        name: String,
        fields: List<Field>,
        records: List<Rec>,
    ): HubFixtureStore {
        datasets[name] = fields to records
        return this
    }

    fun write(): Path {
        root.resolve("journal").createDirectories()
        val manifest = StringBuilder("""{"v":1,"generated_at":0,"datasets":{""")
        datasets.entries.forEachIndexed { index, (name, spec) ->
            val (fields, records) = spec
            val dir = root.resolve("journal").resolve(name)
            dir.createDirectories()
            records
                .mapIndexed { i, rec -> rec to line(name, rec, i + 1L) }
                .groupBy { (rec, _) -> Instant.ofEpochMilli(rec.knownAt).atZone(ZoneOffset.UTC).toLocalDate() }
                .forEach { (day, lines) ->
                    Files.writeString(dir.resolve("$day.ndjson"), lines.joinToString("") { it.second + "\n" })
                }
            if (index > 0) manifest.append(',')
            manifest.append(""""$name":{"fields":[""")
            manifest.append(
                fields.joinToString(",") {
                    """{"name":"${it.name}","type":"${it.type}","strategy":${it.strategy}}"""
                },
            )
            manifest.append(
                """],"key":["effective_at","scope"],"last_seq":${records.size},""" +
                    """"schema_hash":"sha256:${"0".repeat(64)}","scope_kind":"currency","windows":[]}""",
            )
        }
        manifest.append("}}")
        Files.writeString(root.resolve("manifest.json"), manifest.toString())
        Files.writeString(root.resolve("heartbeat"), "1")
        return root
    }

    private fun line(
        dataset: String,
        rec: Rec,
        seq: Long,
    ): String {
        val fields =
            rec.fields.entries.joinToString(",") { (k, v) ->
                val value =
                    when {
                        v == null -> "null"
                        v.startsWith("#") -> v.removePrefix("#") // a timestamp or enum ordinal: a JSON number
                        else -> "\"$v\""
                    }
                "\"$k\":$value"
            }
        return """{"v":1,"dataset":"$dataset","scope":"${rec.scope}","key":"${rec.key}",""" +
            """"revision":${rec.revision},"known_at":${rec.knownAt},"effective_at":${rec.effectiveAt},""" +
            """"availability":"${rec.availability}","source":"fixture","seq":$seq,"fields":{$fields}}"""
    }
}
