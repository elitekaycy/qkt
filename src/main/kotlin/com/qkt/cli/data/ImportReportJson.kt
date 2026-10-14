package com.qkt.cli.data

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.run.ImportReport
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The `--json` form of an import report or refusal: one object, for the bot CLI, the studio and agents. */
internal object ImportReportJson {
    fun render(r: ImportReport): JsonObject =
        buildJsonObject {
            put("ok", true)
            put("dryRun", r.dryRun)
            put("symbol", r.symbol)
            put("kind", r.kind)
            put("preset", r.preset)
            put("presetSource", r.presetSource)
            put("clock", r.clock)
            put("clockEvidence", r.clockEvidence)
            put("timeframe", r.timeframe)
            put("basis", r.basis)
            put("volume", r.volume)
            put("timeFormats", JsonArray(r.timeFormats.map(::JsonPrimitive)))
            put("rows", r.rows)
            put("records", r.records)
            put("target", r.target)
            putJsonArray("inputs") {
                r.inputs.forEach { s ->
                    add(
                        buildJsonObject {
                            put("name", s.name)
                            put("rows", s.rows)
                            put("records", s.records)
                            put("filtered", s.filtered)
                            put("repeatedHeaders", s.skips.repeatedHeaders)
                            put("textRows", s.skips.textRows)
                        },
                    )
                }
            }
            putJsonObject("checks") {
                put("duplicatesDropped", r.tally.duplicatesDropped)
                put("crossedDropped", r.tally.crossedDropped)
                put("superseded", r.tally.superseded)
                put("spikes", JsonArray(r.tally.spikes.map(::JsonPrimitive)))
            }
            put("warnings", JsonArray(r.warnings.map(::JsonPrimitive)))
            put("days", days(r.days))
            putJsonObject("outcomes") {
                put("written", days(r.outcomes.written))
                put("replaced", days(r.outcomes.replaced))
                put("unchanged", days(r.outcomes.unchanged))
                put("skipped", days(r.outcomes.skipped))
                put("emptyFilled", days(r.outcomes.emptyFilled))
            }
            putJsonArray("preview") {
                r.preview.forEach { (raw, rec) ->
                    add(
                        buildJsonObject {
                            put("raw", raw)
                            put("stored", ImportReportText.stored(rec))
                        },
                    )
                }
            }
            put("plan", r.plan)
        }

    fun refusal(e: ImportRefusal): JsonObject =
        buildJsonObject {
            put("ok", false)
            put("error", e.message)
            put("lines", JsonArray(e.lines.take(ImportRefusal.MAX_LINES).map(::JsonPrimitive)))
        }

    private fun days(days: List<LocalDate>) = JsonArray(days.map { JsonPrimitive(it.toString()) })
}
