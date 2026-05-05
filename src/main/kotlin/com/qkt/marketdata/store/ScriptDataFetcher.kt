package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class ScriptDataFetcher(
    private val script: Path,
) : DataFetcher {
    override fun fetch(
        symbol: String,
        day: LocalDate,
        target: Path,
    ) {
        Files.createDirectories(target.parent)
        val rc =
            ProcessBuilder("bash", script.toString(), symbol, day.toString(), target.toString())
                .inheritIO()
                .start()
                .waitFor()
        check(rc == 0) {
            "fetcher script failed: rc=$rc symbol=$symbol day=$day script=$script"
        }
        check(Files.exists(target)) {
            "fetcher script exited 0 but produced no file: $target"
        }
    }

    companion object {
        fun dukascopy(scriptPath: Path = Path.of("scripts/fetch-dukascopy.sh")): ScriptDataFetcher =
            ScriptDataFetcher(scriptPath)
    }
}
