package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import com.qkt.cli.captureCliRun
import com.qkt.marketdata.ingest.plan.PresetLibrary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DataPresetsJsonTest {
    @Test
    fun `json lists every built-in preset with its id`() {
        val run = captureCliRun { DataCommand(Args(arrayOf("data", "presets", "--json"))).run() }

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        val ids = Json.parseToJsonElement(run.out).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertThat(ids).containsExactlyElementsOf(PresetLibrary.builtIns.map { it.id })
    }
}
