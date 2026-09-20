package com.qkt.cli.daemon

import com.qkt.cli.PromotionApproval
import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.PromotionGateEvaluator
import com.qkt.cli.PromotionRecord
import com.qkt.cli.PromotionState
import com.qkt.cli.PromotionStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DeployCommandPromotionGateTest : DeployCommandFixture() {
    @Test
    fun `production deploy blocks an unpromoted strategy`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val testPlane = newPlaneWithRegistry(stateDir = stateDir, promotionGates = PromotionGateConfig(enforce = true))
        val plane = testPlane.plane
        val client = OkHttpClient()
        val file = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        val resp = postDeploy(client, plane, file, "alpha")

        assertThat(resp.code).isEqualTo(409)
        val body = resp.body!!.string()
        assertThat(body).contains("\"kind\":\"promotion-gate\"")
        assertThat(body).contains("promotion_record")
    }

    @Test
    fun `production resync blocks an unpromoted replacement`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val testPlane = newPlaneWithRegistry(stateDir = stateDir, promotionGates = PromotionGateConfig(enforce = true))
        val plane = testPlane.plane
        val client = OkHttpClient()
        val oldFile = tmp.resolve("alpha-v1.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        val newFile = tmp.resolve("alpha-v2.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 2") }
        appendApprovedPromotion(stateDir, "alpha", oldFile)
        postDeploy(client, plane, oldFile, "alpha").close()

        val resp = postResync(client, plane, newFile, "alpha")

        assertThat(resp.code).isEqualTo(409)
        val body = resp.body!!.string()
        assertThat(body).contains("\"kind\":\"promotion-gate\"")
        assertThat(body).contains("strategy_hash")
        assertThat(testPlane.registry.get("alpha")?.sourceFile).isEqualTo(oldFile)
    }

    @Test
    fun `production deploy accepts a promoted matching strategy hash`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(stateDir = stateDir, promotionGates = PromotionGateConfig(enforce = true))
        val client = OkHttpClient()
        val file = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        appendApprovedPromotion(stateDir, "alpha", file)

        val resp = postDeploy(client, plane, file, "alpha")

        assertThat(resp.code).isEqualTo(200)
        val body = resp.body!!.string()
        assertThat(body).contains("\"eligibleForProduction\":true")
        assertThat(body).contains("\"state\":\"production\"")
        waitForJournalAction(stateDir, "deploy")
    }

    @Test
    fun `production deploy rejects a promoted name when the strategy file hash changed`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(stateDir = stateDir, promotionGates = PromotionGateConfig(enforce = true))
        val client = OkHttpClient()
        val file = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        appendApprovedPromotion(stateDir, "alpha", file)
        Files.writeString(file, "STRATEGY alpha VERSION 2")

        val resp = postDeploy(client, plane, file, "alpha")

        assertThat(resp.code).isEqualTo(409)
        assertThat(resp.body!!.string()).contains("strategy_hash")
    }

    @Test
    fun `production deploy accepts waiver with reason and journals it`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(stateDir = stateDir, promotionGates = PromotionGateConfig(enforce = true))
        val client = OkHttpClient()
        val file = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        val resp = postDeploy(client, plane, file, "alpha", query = "waive=all&reason=emergency+cutover")

        assertThat(resp.code).isEqualTo(200)
        val record = PromotionStore(stateDir.stateRoot.resolve("promotion")).latest("alpha")
        assertThat(record?.waivers?.single()?.reason).isEqualTo("emergency cutover")
        val journalText = StringBuilder()
        Files.walk(stateDir.stateRoot.resolve("journal")).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .forEach { journalText.append(Files.readString(it)) }
        }
        assertThat(journalText.toString()).contains("promotion.waive")
        assertThat(journalText.toString()).contains("emergency cutover")
        waitForJournalAction(stateDir, "deploy")
    }

    private fun appendApprovedPromotion(
        stateDir: StateDir,
        name: String,
        file: Path,
    ) {
        val now = Instant.now()
        PromotionStore(stateDir.stateRoot.resolve("promotion"))
            .append(
                PromotionRecord.create(
                    strategy = name,
                    strategyHash = PromotionGateEvaluator.strategyHash(file),
                    state = PromotionState.PRODUCTION,
                    rationale = "approved for production",
                    now = now,
                    approvals =
                        listOf(
                            PromotionApproval(
                                state = PromotionState.PRODUCTION,
                                actor = "test",
                                reason = "approved",
                                approvedAt = now.toString(),
                            ),
                        ),
                ),
            )
    }
}
