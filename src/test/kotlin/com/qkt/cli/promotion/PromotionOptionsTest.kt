package com.qkt.cli.promotion

import com.qkt.cli.ArgError
import com.qkt.cli.Args
import com.qkt.cli.PaperValidationMetrics
import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.PromotionRecord
import com.qkt.cli.PromotionState
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Where `qkt promotion` keeps its ledger, and how it reads its shared flags. */
class PromotionOptionsTest {
    @TempDir
    lateinit var tmp: Path

    private fun options(vararg argv: String) = PromotionOptions(Args(arrayOf("promotion", "status", *argv)))

    private val record =
        PromotionRecord(
            id = "r-1",
            strategy = "alpha",
            strategyHash = "sha256:abc",
            state = PromotionState.PAPER,
            createdAt = "2026-11-06T00:00:00Z",
            updatedAt = "2026-11-06T00:00:00Z",
            rationale = "first",
        )

    private fun ledger(dir: Path) = dir.resolve("promotions.jsonl")

    @Test
    fun `the ledger is at registry-dir when given, over the configured registry`() {
        val flag = tmp.resolve("flag")
        val configured = PromotionGateConfig(registryDir = tmp.resolve("configured"))

        options("--registry-dir", flag.toString(), "--state-dir", tmp.resolve("sd").toString())
            .promotionStore(configured)
            .append(record)

        assertThat(ledger(flag)).isRegularFile()
        assertThat(tmp.resolve("configured")).doesNotExist()
    }

    @Test
    fun `the ledger is the configured registry when no flag names one`() {
        val configured = tmp.resolve("configured")

        val store =
            options(
                "--state-dir",
                tmp.resolve("sd").toString(),
            ).promotionStore(PromotionGateConfig(registryDir = configured))
        store.append(record)

        assertThat(ledger(configured)).isRegularFile()
        assertThat(store.latest("alpha")).isEqualTo(record)
    }

    @Test
    fun `the ledger falls back to the state directory's promotion folder`() {
        val stateDir = tmp.resolve("sd")

        options("--state-dir", stateDir.toString()).promotionStore(PromotionGateConfig.DISABLED).append(record)

        assertThat(ledger(stateDir.resolve("state/promotion"))).isRegularFile()
    }

    @Test
    fun `a strategy path is made absolute and a missing file is an argument error`() {
        val file = Files.writeString(tmp.resolve("alpha.qkt"), "STRATEGY alpha VERSION 1\n")

        assertThat(options().strategyPath(file.toString())).isEqualTo(file.toAbsolutePath())
        assertThatThrownBy { options().strategyPath(tmp.resolve("gone.qkt").toString()) }
            .isInstanceOf(ArgError::class.java)
            .hasMessageStartingWith("file not found: ")
    }

    @Test
    fun `evidence is key=value pairs, repeated or comma separated, and refuses a bare key or value`() {
        val parsed = options("--evidence", "wf = run-7 , snapshot=s3://b/k=v", "--evidence", "note=ok").parseEvidence()

        assertThat(parsed).containsExactly(
            entry("wf", "run-7"),
            entry("snapshot", "s3://b/k=v"),
            entry("note", "ok"),
        )
        for (bad in listOf("novalue", "=value", "key=")) {
            assertThatThrownBy { options("--evidence", bad).parseEvidence() }
                .isInstanceOf(ArgError::class.java)
                .hasMessage("--evidence must use key=value")
        }
        assertThat(options().parseEvidence()).isEmpty()
    }

    @Test
    fun `paper metrics come from the flags, then the existing record, then zero`() {
        val existing =
            PaperValidationMetrics(days = 9, trades = 40, avgSlippageBps = 1.5, missedFills = 2, status = "ok")

        assertThat(options().parsePaper(existing)).isSameAs(existing)
        assertThat(options().parsePaper(null)).isNull()
        assertThat(options("--paper-days", "12", "--p95-slippage-bps", "3.5").parsePaper(existing))
            .isEqualTo(existing.copy(days = 12, p95SlippageBps = 3.5))
        assertThat(options("--paper-status", "degraded").parsePaper(null))
            .isEqualTo(PaperValidationMetrics(days = 0, trades = 0, status = "degraded"))
    }

    @Test
    fun `paper metrics refuse negative and unreadable numbers by flag name`() {
        assertThatThrownBy { options("--paper-trades", "-1").parsePaper(null) }
            .isInstanceOf(ArgError::class.java)
            .hasMessage("--paper-trades must be a non-negative integer")
        assertThatThrownBy { options("--missed-fills", "few").parsePaper(null) }
            .hasMessage("--missed-fills must be a non-negative integer")
        assertThatThrownBy { options("--rejection-rate-pct", "-0.5").parsePaper(null) }
            .isInstanceOf(ArgError::class.java)
            .hasMessage("--rejection-rate-pct must be a non-negative number")
    }
}
