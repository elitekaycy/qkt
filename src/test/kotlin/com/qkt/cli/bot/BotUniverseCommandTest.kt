package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BotUniverseCommandTest {
    @TempDir
    lateinit var tmp: Path

    private val instruments =
        """
        instruments:
          - { qktSymbol: EXNESS:XAUUSD, contractSize: 100, volumeStep: 0.01, volumeMin: 0.01, pointSize: 0.001, digits: 3, tradeStopsLevelPoints: 0, category: Metals, currency: USD }
          - { qktSymbol: EXNESS:EURUSD, contractSize: 100000, volumeStep: 0.01, volumeMin: 0.01, pointSize: 0.00001, digits: 5, tradeStopsLevelPoints: 0, category: Forex }
          - { qktSymbol: EXNESS:AAPLm, contractSize: 1, volumeStep: 1, volumeMin: 1, pointSize: 0.01, digits: 2, tradeStopsLevelPoints: 0, category: Stocks\US, equityCosts: {} }
        """.trimIndent()

    private class Outcome(
        val code: Int,
        val out: String,
        val err: String,
    )

    private fun universe(vararg argv: String): Outcome {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val (origOut, origErr) = System.out to System.err
        val code =
            try {
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(err))
                BotUniverseCommand(Args(arrayOf("universe") + argv)).run()
            } finally {
                System.setOut(origOut)
                System.setErr(origErr)
            }
        return Outcome(code, out.toString(), err.toString())
    }

    private fun yaml(): String = tmp.resolve("instruments.yaml").also { Files.writeString(it, instruments) }.toString()

    @Test
    fun `a filtered spec lists its members as JSON with every catalog field`() {
        val result =
            universe(
                "EXNESS:* WHERE category GLOB \"S*\" OR category = Metals TOP 5 BY contract_size ASC",
                "--instruments",
                yaml(),
                "--as-of",
                "2026-01-02",
                "--json",
            )
        assertThat(result.code).describedAs(result.err).isEqualTo(ExitCodes.SUCCESS)
        val root = Json.parseToJsonElement(result.out.trim()).jsonObject
        assertThat(root.getValue("ok").jsonPrimitive.content).isEqualTo("true")
        assertThat(root.getValue("spec").jsonPrimitive.content)
            .isEqualTo("EXNESS:* WHERE (category GLOB \"S*\" OR category = \"Metals\") TOP 5 BY contract_size ASC")
        assertThat(root.getValue("asOf").jsonPrimitive.content).isEqualTo("1767312000000")
        assertThat(root.getValue("count").jsonPrimitive.content).isEqualTo("2")
        val members = root.getValue("members").jsonArray.map { it.jsonObject }
        assertThat(
            members.map {
                it.getValue("qktSymbol").jsonPrimitive.content
            },
        ).containsExactly("EXNESS:AAPLm", "EXNESS:XAUUSD")
        val gold = members[1]
        assertThat(gold.getValue("kind").jsonPrimitive.content).isEqualTo("spot")
        assertThat(gold.getValue("contract_size").jsonPrimitive.content).isEqualTo("100")
        assertThat(gold.getValue("currency").jsonPrimitive.content).isEqualTo("USD")
        assertThat(gold.getValue("dte").toString()).isEqualTo("null")
        assertThat(members[0].getValue("kind").jsonPrimitive.content).isEqualTo("equity")
    }

    @Test
    fun `a picked list is checked name by name and printed as text`() {
        val result = universe("EXNESS:{XAUUSD, EURUSD}", "--instruments", yaml(), "--data-root", tmp.toString())
        assertThat(result.code).describedAs(result.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(result.out.lines()[0]).isEqualTo("EXNESS:{XAUUSD, EURUSD}: 2 member(s)")
        assertThat(
            result.out.lines()[1],
        ).isEqualTo(
            "  EXNESS:XAUUSD  kind=spot  category=Metals  currency=USD  contract_size=100  tick_size=0.001  " +
                "trade_mode=full",
        )
        assertThat(
            result.out.lines()[2],
        ).startsWith("  EXNESS:EURUSD  kind=spot  category=Forex  contract_size=100000")

        val missing = universe("EXNESS:{XAUUSD, GBPUSD}", "--instruments", yaml(), "--json")
        assertThat(missing.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(
            missing.out,
        ).isEqualTo("{\"ok\":false,\"error\":\"'EXNESS:GBPUSD' is not in the instruments file\"}\n")
    }

    @Test
    fun `qkt bot dispatches the universe verb`() {
        val out = ByteArrayOutputStream()
        val origOut = System.out
        val code =
            try {
                System.setOut(PrintStream(out))
                com.qkt.cli.bot
                    .BotCommand(Args(arrayOf("bot", "universe", "EXNESS:{XAUUSD}", "--instruments", yaml(), "--json")))
                    .run()
            } finally {
                System.setOut(origOut)
            }
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out.toString()).contains("\"count\":1").contains("\"qktSymbol\":\"EXNESS:XAUUSD\"")
    }

    @Test
    fun `unions, bad specs and a missing catalog fail cleanly`() {
        val union = universe("fx + crypto", "--instruments", yaml())
        assertThat(union.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(union.err).contains("a union names sets declared in a strategy; resolve each set's own spec")

        val bad = universe("EXNESS:* WHERE open_interest > 1 TOP 5 BY symbol", "--instruments", yaml(), "--json")
        assertThat(bad.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(bad.out).contains("unknown catalog field 'open_interest'")

        val empty = tmp.resolve("empty").also { Files.createDirectories(it) }
        val noCatalog =
            universe("EXNESS:* WHERE kind = spot TOP 5 BY symbol", "--data-root", empty.toString(), "--json")
        assertThat(noCatalog.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(noCatalog.out).contains("no instruments file at ${empty.resolve("instruments.yaml")}")

        val none = universe("EXNESS:* WHERE kind = future TOP 5 BY symbol", "--instruments", yaml(), "--json")
        assertThat(none.out).contains("symbol set 'universe': no EXNESS instrument passes kind = \\\"future\\\"")
    }
}
