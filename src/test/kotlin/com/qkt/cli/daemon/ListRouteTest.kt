package com.qkt.cli.daemon

import com.qkt.cli.PromotionApproval
import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.PromotionRecord
import com.qkt.cli.PromotionState
import com.qkt.cli.PromotionStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ListRouteTest : ListRouteFixture() {
    @Test
    fun `list returns array with both deployed strategies and disjoint ports`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(stubFactory(stateDir))
        val plane = ControlPlane(registry, port = 0)
        plane.start()
        opened.add(plane)
        registry.deploy("alpha", tmp.resolve("a.qkt"))
        registry.deploy("beta", tmp.resolve("b.qkt"))
        val client = OkHttpClient()
        val resp =
            client
                .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/list").build())
                .execute()
        assertThat(resp.code).isEqualTo(200)
        val body = resp.body!!.string()
        assertThat(body).contains("\"name\":\"alpha\"")
        assertThat(body).contains("\"name\":\"beta\"")
        // Distinct ports — extract numeric port values and confirm they differ.
        val portRegex = Regex("\"port\":(\\d+)")
        val ports = portRegex.findAll(body).map { it.groupValues[1].toInt() }.toList()
        assertThat(ports).hasSize(2)
        assertThat(ports.toSet()).hasSize(2)
    }

    @Test
    fun `list returns empty array when no strategies are deployed`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val registry =
            StrategyRegistry(StrategyHandle.Factory { _, _, _ -> error("no deploys expected") })
        val plane = ControlPlane(registry, port = 0)
        plane.start()
        opened.add(plane)
        val client = OkHttpClient()
        val resp =
            client
                .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/list").build())
                .execute()
        assertThat(resp.code).isEqualTo(200)
        assertThat(resp.body!!.string().trim()).isEqualTo("[]")
    }

    @Test
    fun `portfolio child inherits parent promotion eligibility`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val parent = "book"
        val registry =
            StrategyRegistry(
                stubFactory(stateDir) { name ->
                    if (name != "$parent/child") return@stubFactory null
                    StrategyHandle.ChildMeta(
                        parent = parent,
                        alias = "child",
                        hold = false,
                        gateActive = AtomicBoolean(true),
                        operatorStop = AtomicBoolean(false),
                    )
                },
            )
        val now = Instant.parse("2026-07-31T00:00:00Z")
        PromotionStore(stateDir.stateRoot.resolve("promotion"))
            .append(
                PromotionRecord.create(
                    strategy = parent,
                    strategyHash = "parent-hash",
                    state = PromotionState.PRODUCTION,
                    rationale = "approved portfolio",
                    now = now,
                    approvals =
                        listOf(
                            PromotionApproval(
                                state = PromotionState.PRODUCTION,
                                actor = "operator",
                                reason = "approved portfolio",
                                approvedAt = now.toString(),
                            ),
                        ),
                ),
            )
        val plane =
            ControlPlane(
                registry = registry,
                port = 0,
                stateDir = stateDir,
                promotionGates = PromotionGateConfig(enforce = true),
            )
        plane.start()
        opened.add(plane)
        registry.deploy("$parent/child", tmp.resolve("child.qkt").also { Files.writeString(it, "child") })

        val response =
            OkHttpClient()
                .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/list").build())
                .execute()
        assertThat(response.code).isEqualTo(200)
        val body = response.body!!.string()
        assertThat(body).contains("\"name\":\"$parent/child\"")
        assertThat(body).contains("\"promotionEligible\":true")
        assertThat(body).contains("\"promotionMissingGates\":[]")
    }
}
