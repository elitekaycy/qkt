package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.qkt.cli.PromotionApproval
import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.PromotionGateEvaluator
import com.qkt.cli.PromotionRecord
import com.qkt.cli.PromotionState
import com.qkt.cli.PromotionStore
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PositionDto
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DeployCommandTest {
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanup() {
        for (c in opened.reversed()) runCatching { c.close() }
        opened.clear()
    }

    private fun stubFactory(stateDir: StateDir): StrategyHandle.Factory =
        StrategyHandle.Factory { name, _, _ ->
            val ring = EventRing(capacity = 8)
            val running = AtomicBoolean(true)
            val live =
                object : LiveSessionHandle {
                    override val running: Boolean get() = running.get()
                    override val droppedTicks: Long = 0L

                    override fun stop() {
                        running.set(false)
                    }

                    override fun awaitTermination(timeout: Duration): Boolean = true

                    override fun recentTrades(): List<Trade> = emptyList()

                    override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> =
                        emptyList()

                    override fun flatten() = Unit
                }
            val server =
                ObservabilityServer(
                    ring = ring,
                    statusProvider = {
                        StatusSnapshot(
                            strategy = name,
                            version = 1,
                            uptimeMs = 0L,
                            startedAt = Instant.EPOCH.toString(),
                            equity = BigDecimal.ZERO,
                            balance = BigDecimal.ZERO,
                            realized = BigDecimal.ZERO,
                            unrealized = BigDecimal.ZERO,
                            positions = emptyList<PositionDto>(),
                            lastTrade = null,
                        )
                    },
                    running = { running.get() },
                    onStop = { running.set(false) },
                    bind = "127.0.0.1",
                    port = 0,
                ).also { it.start() }
            opened.add(server)
            val ast =
                StrategyAst(
                    name = name,
                    version = 1,
                    streams =
                        listOf(StreamDecl(alias = "s", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m")),
                    constants = emptyList(),
                    lets = emptyList(),
                    defaults = null,
                    rules = emptyList<WhenThen>(),
                )
            StrategyHandle(
                name = name,
                ast = ast,
                live = live,
                observability = server,
                ring = ring,
                logFile = stateDir.logFile(name),
                startedAt = Instant.now(),
            )
        }

    private fun newPlane(
        @TempDir tmp: Path? = null,
        stateDir: StateDir,
        promotionGates: PromotionGateConfig = PromotionGateConfig.DISABLED,
    ): ControlPlane {
        val registry = StrategyRegistry(stubFactory(stateDir))
        val routeStateDir = if (promotionGates == PromotionGateConfig.DISABLED) null else stateDir
        val plane = ControlPlane(registry, port = 0, stateDir = routeStateDir, promotionGates = promotionGates)
        plane.start()
        opened.add(plane)
        stateDir.writeControlPort(plane.boundPort)
        return plane
    }

    @Test
    fun `POST deploy returns name port state startedAt`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(tmp, stateDir)
        val client = OkHttpClient()
        val file = tmp.resolve("foo.qkt").also { Files.writeString(it, "STRATEGY x VERSION 1") }
        val body =
            """{"file":"${file.toAbsolutePath()}","name":"foo"}"""
                .toRequestBody("application/json".toMediaType())
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/deploy")
                        .post(body)
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(200)
        val responseBody = resp.body!!.string()
        assertThat(responseBody).contains("\"name\":\"foo\"")
        assertThat(responseBody).contains("\"port\":")
        assertThat(responseBody).contains("\"state\":\"running\"")
    }

    @Test
    fun `POST deploy with bad body returns 400`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(tmp, stateDir)
        val client = OkHttpClient()
        val body = """not-json""".toRequestBody("application/json".toMediaType())
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/deploy")
                        .post(body)
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(400)
    }

    @Test
    fun `POST deploy with missing file field returns 400`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(tmp, stateDir)
        val client = OkHttpClient()
        val body = """{"name":"foo"}""".toRequestBody("application/json".toMediaType())
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/deploy")
                        .post(body)
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(400)
    }

    @Test
    fun `POST deploy with duplicate name returns 409`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(tmp, stateDir)
        val client = OkHttpClient()
        val file = tmp.resolve("foo.qkt").also { Files.writeString(it, "STRATEGY x VERSION 1") }
        val body =
            """{"file":"${file.toAbsolutePath()}","name":"foo"}"""
                .toRequestBody("application/json".toMediaType())
        val first =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/deploy")
                        .post(body)
                        .build(),
                ).execute()
        first.close()
        val second =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/deploy")
                        .post(
                            """{"file":"${file.toAbsolutePath()}","name":"foo"}"""
                                .toRequestBody("application/json".toMediaType()),
                        ).build(),
                ).execute()
        assertThat(second.code).isEqualTo(409)
    }

    @Test
    fun `ControlClient deploy round-trips through the daemon`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(tmp, stateDir)
        val controlClient = ControlClient(stateDir)
        val file = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY a VERSION 1") }
        val body = controlClient.deploy("alpha", file)
        assertThat(body).contains("\"name\":\"alpha\"")
        assertThat(plane.boundPort).isGreaterThan(0)
    }

    @Test
    fun `ControlClient raises when no daemon is running`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val client = ControlClient(stateDir)
        assertThat(
            runCatching { client.health() }.exceptionOrNull(),
        ).isInstanceOf(ControlClient.NoDaemonRunningException::class.java)
    }

    @Test
    fun `production deploy blocks an unpromoted strategy`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(stateDir = stateDir, promotionGates = PromotionGateConfig(enforce = true))
        val client = OkHttpClient()
        val file = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY alpha VERSION 1") }
        val resp = postDeploy(client, plane, file, "alpha")

        assertThat(resp.code).isEqualTo(409)
        val body = resp.body!!.string()
        assertThat(body).contains("\"kind\":\"promotion-gate\"")
        assertThat(body).contains("promotion_record")
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

    private fun postDeploy(
        client: OkHttpClient,
        plane: ControlPlane,
        file: Path,
        name: String,
        query: String = "",
    ): okhttp3.Response {
        val body =
            """{"file":"${file.toAbsolutePath()}","name":"$name"}"""
                .toRequestBody("application/json".toMediaType())
        val suffix = query.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
        return client
            .newCall(
                Request
                    .Builder()
                    .url("http://127.0.0.1:${plane.boundPort}/deploy$suffix")
                    .post(body)
                    .build(),
            ).execute()
    }

    private fun waitForJournalAction(
        stateDir: StateDir,
        action: String,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (System.nanoTime() < deadline) {
            val text = journalText(stateDir)
            if (text.contains(""""action":"$action"""")) return
            Thread.sleep(20)
        }
        assertThat(journalText(stateDir)).contains(""""action":"$action"""")
    }

    private fun journalText(stateDir: StateDir): String {
        val root = stateDir.stateRoot.resolve("journal")
        if (!Files.exists(root)) return ""
        val out = StringBuilder()
        Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .forEach { out.append(Files.readString(it)) }
        }
        return out.toString()
    }
}
