package com.qkt.golden

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir

/**
 * Pins today's CFD backtest behaviour byte for byte (spec §8). Each case's normalized report and
 * trade tape must equal the committed golden files. `QKT_GOLDEN_UPDATE=1` rewrites the files and
 * fails, so a regeneration is always a deliberate, reviewed commit.
 */
class GoldenBacktestTest {
    @TempDir
    lateinit var tmp: Path

    private val goldenRoot: Path = Paths.get("src/test/resources/golden/backtest")
    private val updating = System.getenv("QKT_GOLDEN_UPDATE") == "1"

    @TestFactory
    fun `CFD backtests match their golden outputs`(): List<DynamicTest> =
        GoldenCases.all.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val (report, trades) = GoldenRun.run(case, Files.createDirectories(tmp.resolve(case.name)))
                val caseDir = goldenRoot.resolve(case.name)
                if (updating) {
                    Files.createDirectories(caseDir)
                    Files.writeString(caseDir.resolve("result.json"), report)
                    Files.writeString(caseDir.resolve("trades.csv"), trades)
                    fail("golden files written for ${case.name}; rerun without QKT_GOLDEN_UPDATE")
                }
                assertThat(trades.lines().size).describedAs("${case.name} must trade").isGreaterThan(2)
                assertThat(report).isEqualTo(Files.readString(caseDir.resolve("result.json")))
                assertThat(trades).isEqualTo(Files.readString(caseDir.resolve("trades.csv")))
            }
        }
}
