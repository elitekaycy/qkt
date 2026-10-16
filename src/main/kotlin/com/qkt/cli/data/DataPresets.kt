package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.ingest.plan.PresetLibrary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `qkt data presets [--json]`: the built-in import presets, each with what it reads. */
internal fun dataPresets(args: Args): Int {
    val presets = PresetLibrary.builtIns
    if (args.flag("json")) {
        val rows =
            presets.map { p ->
                buildJsonObject {
                    put("id", p.id)
                    put("kind", p.kind?.spec)
                    put("description", p.description)
                }
            }
        println(JsonArray(rows))
    } else {
        presets.forEach {
            println(
                "  ${it.id.padEnd(WIDTH)} ${it.kind?.spec.orEmpty().padEnd(KIND_WIDTH)} ${it.description.orEmpty()}",
            )
        }
        println(
            "qkt data presets: ${presets.size} built in; any other source: qkt data import <file> --as <SYMBOL> --map ... --plan",
        )
    }
    return ExitCodes.SUCCESS
}

private const val WIDTH = 24
private const val KIND_WIDTH = 14
