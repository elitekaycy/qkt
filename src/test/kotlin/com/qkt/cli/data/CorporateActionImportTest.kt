package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import com.qkt.instrument.CorporateAction
import com.qkt.instrument.CorporateActionStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt data import --kind corporate-actions`: any described file in, the store's one file out, nothing guessed. */
class CorporateActionImportTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")

    private fun run(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val (savedOut, savedErr) = System.out to System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        val code =
            try {
                DataCommand(Args(arrayOf("data", "import", *argv, "--data-root", root.toString()))).run()
            } finally {
                System.setOut(savedOut)
                System.setErr(savedErr)
            }
        return Triple(code, out.toString(), err.toString())
    }

    private fun file(
        name: String,
        text: String,
    ): String = dir.resolve(name).also { Files.writeString(it, text) }.toString()

    private fun stored(symbol: String = "EXNESS:NVDA") = CorporateActionStore(root).actions(symbol)

    @Test
    fun `qkt's own file imports with no flags`() {
        val f =
            file(
                "nvda.csv",
                "ex_date,kind,value,currency,pay_date\n2024-06-10,split,10,,\n2024-06-11,dividend,0.01,USD,2024-06-28\n",
            )
        val (code, out, _) = run(f, "--as", "EXNESS:NVDA", "--kind", "corporate-actions")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("1 dividend(s), 1 split(s) read; merged into")
        assertThat(stored()).containsExactly(
            CorporateAction.Split(LocalDate.parse("2024-06-10"), BigDecimal(10)),
            CorporateAction.Dividend(
                LocalDate.parse("2024-06-11"),
                BigDecimal("0.01"),
                "USD",
                LocalDate.parse("2024-06-28"),
            ),
        )
    }

    @Test
    fun `a vendor file is described with map, action and currency, and new-for-old ratios read`() {
        val divs = file("div.csv", "Date,Dividends\n06/11/2024,0.01\n09/12/2024,0.01\n")
        val splits = file("spl.csv", "Date,Stock Splits\n06/10/2024,10:1\n")
        val common = arrayOf("--as", "EXNESS:NVDA", "--kind", "corporate-actions", "--time-format", "MM/dd/yyyy")
        assertThat(
            run(
                divs,
                *common,
                "--map",
                "ex_date=Date,value=Dividends",
                "--action",
                "dividend",
                "--currency",
                "USD",
            ).first,
        ).isEqualTo(ExitCodes.SUCCESS)
        assertThat(
            run(splits, *common, "--map", "ex_date=Date", "--map", "value=Stock Splits", "--action", "split").first,
        ).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stored().map { it.exDate.toString() to it::class.simpleName })
            .containsExactly("2024-06-10" to "Split", "2024-06-11" to "Dividend", "2024-09-12" to "Dividend")
        val reverse = file("rev.csv", "ex_date,kind,value\n2025-01-02,split,1:8\n")
        run(reverse, "--as", "EXNESS:XYZ", "--kind", "corporate-actions")
        assertThat((stored("EXNESS:XYZ").single() as CorporateAction.Split).ratio).isEqualByComparingTo("0.125")
    }

    @Test
    fun `re-importing replaces an action of the same kind and date instead of doubling it`() {
        val f = file("a.csv", "ex_date,kind,value,currency\n2024-06-11,dividend,0.01,USD\n")
        run(f, "--as", "EXNESS:NVDA", "--kind", "corporate-actions")
        val g = file("b.csv", "ex_date,kind,value,currency\n2024-06-11,dividend,0.02,USD\n")
        val (_, out, _) = run(g, "--as", "EXNESS:NVDA", "--kind", "corporate-actions", "--json")
        assertThat(out).contains("\"stored\":1")
        assertThat((stored().single() as CorporateAction.Dividend).amount).isEqualByComparingTo("0.02")
    }

    @Test
    fun `a bad row, a duplicate, a missing currency or a dry run writes nothing`() {
        val bad = file("bad.csv", "ex_date,kind,value,currency\n2024-06-11,dividend,0.01,USD\n2024-06-12,split,-2,\n")
        val (code, _, err) = run(bad, "--as", "EXNESS:NVDA", "--kind", "corporate-actions")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).contains("line 3").contains("ratio must be positive").contains("Nothing was written")
        val twice =
            file("dup.csv", "ex_date,kind,value,currency\n2024-06-11,dividend,0.01,USD\n2024-06-11,dividend,0.01,USD\n")
        assertThat(
            run(twice, "--as", "EXNESS:NVDA", "--kind", "corporate-actions").third,
        ).contains("two dividend actions")
        val noCcy = file("noccy.csv", "ex_date,kind,value\n2024-06-11,dividend,0.01\n")
        assertThat(run(noCcy, "--as", "EXNESS:NVDA", "--kind", "corporate-actions").third).contains("pass --currency")
        val ok = file("ok.csv", "ex_date,kind,value\n2024-06-10,split,10\n")
        val (dry, out, _) = run(ok, "--as", "EXNESS:NVDA", "--kind", "corporate-actions", "--dry-run")
        assertThat(dry).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("would merge into")
        assertThat(stored()).isEmpty()
    }

    @Test
    fun `a file without a kind column needs --action, and an unknown kind is refused`() {
        val f = file("x.csv", "ex_date,value\n2024-06-10,10\n")
        assertThat(run(f, "--as", "EXNESS:NVDA", "--kind", "corporate-actions").third).contains("pass --action")
        val g = file("y.csv", "ex_date,kind,value\n2024-06-10,merger,1\n")
        assertThat(
            run(g, "--as", "EXNESS:NVDA", "--kind", "corporate-actions").third,
        ).contains("neither a dividend nor a split")
    }

    @Test
    fun `a dividend in another currency than the instrument's is refused`() {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("instruments.yaml"),
            "instruments:\n  - qktSymbol: EXNESS:NVDA\n    contractSize: 100\n    volumeStep: 0.01\n" +
                "    volumeMin: 0.01\n    volumeMax: 10\n    pointSize: 0.01\n    digits: 2\n" +
                "    tradeStopsLevelPoints: 0\n    currency: USD\n",
        )
        val f = file("eur.csv", "ex_date,kind,value,currency\n2024-06-11,dividend,0.01,EUR\n")
        val (code, _, err) = run(f, "--as", "EXNESS:NVDA", "--kind", "corporate-actions")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).contains("quoted in USD")
    }
}
