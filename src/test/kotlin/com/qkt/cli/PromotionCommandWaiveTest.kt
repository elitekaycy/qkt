package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PromotionCommandWaiveTest {
    @TempDir
    lateinit var stateHome: Path

    /** Every run gets a temp `--state-dir` so the waiver journal never lands in the real state directory. */
    private fun promotion(vararg argv: String): CapturedCliRun {
        val state = if ("--state-dir" in argv) emptyArray() else arrayOf("--state-dir", stateHome.toString())
        return captureCliRun { PromotionCommand(Args(arrayOf("promotion", *argv, *state))).run() }
    }

    private fun strategy(tmp: Path): Path =
        tmp.resolve("alpha.qkt").also {
            Files.writeString(it, "STRATEGY alpha VERSION 1")
        }

    @Test
    fun `waive without a prior record creates a draft record carrying the waiver and journals it`(
        @TempDir tmp: Path,
    ) {
        val registry = tmp.resolve("promotion")
        val run =
            promotion(
                "waive",
                strategy(tmp).toString(),
                "--gate",
                "paper_days, operator_approval",
                "--reason",
                "short soak accepted",
                "--expires",
                "2030-01-01T00:00:00Z",
                "--actor",
                "ops",
                "--registry-dir",
                registry.toString(),
                "--state-dir",
                tmp.resolve("state").toString(),
            )

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).startsWith("promotion waiver recorded: alpha gates=paper_days,operator_approval sha256:")
        val record = PromotionStore(registry).latest("alpha")
        assertThat(record?.state).isEqualTo(PromotionState.DRAFT)
        assertThat(record?.rationale).isEqualTo("waiver without prior promotion record")
        val waiver = record?.waivers?.single()
        assertThat(waiver?.gates).containsExactly("paper_days", "operator_approval")
        assertThat(waiver?.actor).isEqualTo("ops")
        assertThat(waiver?.expiresAt).isEqualTo("2030-01-01T00:00:00Z")
        assertThat(Files.list(tmp.resolve("state/state/journal")).use { it.count() }).isPositive()
    }

    @Test
    fun `waive on an existing record keeps its state and appends the waiver as json`(
        @TempDir tmp: Path,
    ) {
        val registry = tmp.resolve("promotion").toString()
        val path = strategy(tmp).toString()
        promotion("record", path, "--state", "paper", "--reason", "paper soak", "--registry-dir", registry)

        val run = promotion("waive", path, "--all", "--reason", "emergency", "--registry-dir", registry, "--json")

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("\"strategy\":\"alpha\"").contains("\"gates\":[\"all\"]")
        val record = PromotionStore(Path.of(registry)).latest("alpha")
        assertThat(record?.state).isEqualTo(PromotionState.PAPER)
        assertThat(record?.waivers?.single()?.gates).containsExactly("all")
    }

    @Test
    fun `waive rejects a missing gate a blank reason and a malformed expiry`(
        @TempDir tmp: Path,
    ) {
        val path = strategy(tmp).toString()
        val registry = tmp.resolve("promotion").toString()
        assertThatThrownBy { promotion("waive", path, "--reason", "x", "--registry-dir", registry) }
            .isInstanceOf(ArgError::class.java)
            .hasMessage("missing required flag --gate")
        assertThatThrownBy { promotion("waive", path, "--gate", "all", "--reason", " ", "--registry-dir", registry) }
            .isInstanceOf(ArgError::class.java)
            .hasMessage("--reason must not be blank")
        assertThatThrownBy {
            promotion(
                "waive",
                path,
                "--gate",
                "all",
                "--reason",
                "x",
                "--expires",
                "tomorrow",
                "--registry-dir",
                registry,
            )
        }.isInstanceOf(ArgError::class.java)
            .hasMessage("--expires must be an ISO-8601 instant")
    }

    @Test
    fun `record without approval defaults to candidate and prints the paper summary`(
        @TempDir tmp: Path,
    ) {
        val registry = tmp.resolve("promotion").toString()
        val path = strategy(tmp).toString()

        val run = promotion("record", path, "--reason", "first look", "--paper-days", "3", "--registry-dir", registry)

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("promotion recorded: alpha candidate sha256:").contains("paper: 3 days, 0 trades")
        assertThat(PromotionStore(Path.of(registry)).latest("alpha")?.state).isEqualTo(PromotionState.CANDIDATE)
    }

    @Test
    fun `approve without a state defaults to production and appends to prior approvals`(
        @TempDir tmp: Path,
    ) {
        val registry = tmp.resolve("promotion").toString()
        val path = strategy(tmp).toString()
        promotion("approve", path, "--reason", "first", "--registry-dir", registry)

        val run = promotion("approve", path, "--reason", "second", "--registry-dir", registry)

        assertThat(run.out).contains("promotion approved: alpha production")
        val record = PromotionStore(Path.of(registry)).latest("alpha")
        assertThat(record?.approvals?.map { it.reason }).containsExactly("first", "second")
    }

    @Test
    fun `record rejects an unknown state and a blank reason`(
        @TempDir tmp: Path,
    ) {
        val path = strategy(tmp).toString()
        assertThatThrownBy { promotion("record", path, "--state", "golden", "--reason", "x") }
            .isInstanceOf(ArgError::class.java)
            .hasMessage("unknown promotion state: golden")
        assertThatThrownBy { promotion("record", path, "--reason", "", "--registry-dir", tmp.toString()) }
            .isInstanceOf(ArgError::class.java)
            .hasMessage("--reason must not be blank")
    }

    @Test
    fun `help prints usage and an unknown action is an argument error`() {
        val help = promotion("help")
        assertThat(help.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(help.out).contains("qkt promotion waive <strategy.qkt>")

        val unknown = promotion("promote")
        assertThat(unknown.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(unknown.err).contains("unknown promotion action 'promote'")
        assertThat(unknown.out).contains("qkt promotion record")
    }
}
