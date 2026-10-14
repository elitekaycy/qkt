package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.ingest.IngestFixtures
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataImportCommandTest {
    @TempDir
    lateinit var dir: Path

    private fun run(vararg argv: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val saved = System.out
        System.setOut(PrintStream(out, true))
        val code =
            try {
                DataCommand(Args(arrayOf("data", *argv))).run()
            } finally {
                System.setOut(saved)
            }
        return code to out.toString()
    }

    @Test
    fun `an import writes and reports in JSON for tools`() {
        val file = IngestFixtures.sample("mt5-bars.csv", dir.resolve("in"))
        val (code, out) =
            run(
                "import",
                file.toString(),
                "--as",
                "EXNESS:XAUUSD",
                "--data-root",
                dir.resolve("root").toString(),
                "--json",
            )

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        val json = Json.parseToJsonElement(out).jsonObject
        assertThat(json["preset"]?.jsonPrimitive?.content).isEqualTo("mt5-bars")
        assertThat(json["records"]?.jsonPrimitive?.content).isEqualTo("5")
        assertThat(Files.exists(dir.resolve("root/bars/EXNESS/XAUUSD/1m/2024-03-10.csv"))).isTrue()
    }

    @Test
    fun `a refusal exits 1 and writes nothing`() {
        val file = IngestFixtures.sample("mt5-bars.csv", dir.resolve("in"))
        val (code, out) =
            run(
                "import",
                file.toString(),
                "--as",
                "EXNESS:XAUUSD",
                "--tz",
                "Mars/Olympus",
                "--data-root",
                dir.resolve("root").toString(),
                "--json",
            )

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(
            Json
                .parseToJsonElement(out)
                .jsonObject["ok"]
                ?.jsonPrimitive
                ?.content,
        ).isEqualTo("false")
        assertThat(Files.exists(dir.resolve("root"))).isFalse()
    }

    @Test
    fun `missing --as is an argument error`() {
        val file = IngestFixtures.sample("mt5-bars.csv", dir.resolve("in"))

        assertThat(run("import", file.toString(), "--data-root", dir.toString()).first).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(run("import").first).isEqualTo(ExitCodes.ARG_ERROR)
    }

    @Test
    fun `--plan prints a preset that re-imports the file identically`() {
        val file = IngestFixtures.sample("mt5-bars.csv", dir.resolve("in"))
        val (_, plan) =
            run(
                "import",
                file.toString(),
                "--as",
                "EXNESS:XAUUSD",
                "--data-root",
                dir.resolve("a").toString(),
                "--plan",
            )
        val saved = dir.resolve("my.yaml").also { Files.writeString(it, plan) }

        run(
            "import",
            file.toString(),
            "--as",
            "EXNESS:XAUUSD",
            "--data-root",
            dir.resolve("b").toString(),
            "--preset",
            saved.toString(),
        )

        assertThat(plan).contains("clock: \"ny-close\"").contains("timeframe: \"1m\"")
        assertThat(Files.exists(dir.resolve("a"))).isFalse()
        assertThat(Files.readString(dir.resolve("b/bars/EXNESS/XAUUSD/1m/2024-03-10.csv")))
            .contains("1710104400000,2180.59200000")
    }

    @Test
    fun `presets lists every built-in`() {
        val (code, out) = run("presets")

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("mt5-bars").contains("binance-klines").contains("qkt-cot")
    }
}
