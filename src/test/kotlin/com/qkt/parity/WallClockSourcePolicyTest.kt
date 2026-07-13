package com.qkt.parity

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WallClockSourcePolicyTest {
    @Test
    fun `direct wall-clock reads stay inside reviewed operational boundaries`() {
        val sourceRoot = Path.of("src/main/kotlin")
        val reads =
            Files
                .walk(sourceRoot)
                .use { paths ->
                    paths
                        .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                        .filter { path -> DIRECT_WALL_CLOCK.containsMatchIn(Files.readString(path)) }
                        .map { it.toString().replace('\\', '/') }
                        .toList()
                        .toSet()
                }

        val unexpected = reads - ALLOWED.keys
        val staleAllowlist = ALLOWED.keys - reads
        assertThat(unexpected)
            .withFailMessage(
                "Unreviewed direct wall-clock reads found: %s. Inject com.qkt.common.Clock " +
                    "for production logic, or add an exact-file rationale to this policy.",
                unexpected,
            ).isEmpty()
        assertThat(staleAllowlist)
            .withFailMessage("Remove stale wall-clock allowlist entries: %s", staleAllowlist)
            .isEmpty()
    }

    private companion object {
        val DIRECT_WALL_CLOCK =
            Regex(
                "System\\.(?:currentTimeMillis|nanoTime)\\s*\\(|" +
                    "Instant\\.now\\s*\\(|LocalDate(?:Time)?\\.now\\s*\\(|" +
                    "Clock\\.system(?:UTC|DefaultZone|\\s*\\()",
            )

        val ALLOWED: Map<String, String> =
            mapOf(
                "src/main/kotlin/com/qkt/common/Clock.kt" to "SystemClock adapter boundary",
                "src/main/kotlin/com/qkt/observability/LatencyRegistry.kt" to "monotonic latency measurement",
                "src/main/kotlin/com/qkt/observability/LatencyTracker.kt" to "monotonic latency measurement",
                "src/main/kotlin/com/qkt/marketdata/live/mt5/Mt5TickClient.kt" to "live transport polling deadline",
                "src/main/kotlin/com/qkt/app/TradingPipeline.kt" to "opt-in monotonic latency measurement",
                "src/main/kotlin/com/qkt/app/LiveSession.kt" to "live engine query and shutdown deadlines",
                "src/main/kotlin/com/qkt/app/MaxAudit.kt" to "interactive operator audit utility",
                "src/main/kotlin/com/qkt/persistence/StateFileWriter.kt" to "persistence latency observation",
                "src/main/kotlin/com/qkt/notify/NotificationWorker.kt" to "notification worker deadlines",
                "src/main/kotlin/com/qkt/tools/parity/ParityTicksXauusd.kt" to "external capture timestamp",
                "src/main/kotlin/com/qkt/tools/parity/ParityBarsXauusd.kt" to "external capture timestamp",
                "src/main/kotlin/com/qkt/tools/parity/ParityDukascopyMt5Xauusd.kt" to "external capture timestamp",
                "src/main/kotlin/com/qkt/cli/ExperimentCommand.kt" to "operator-run evidence timestamp",
                "src/main/kotlin/com/qkt/cli/PreflightCommand.kt" to "temporary probe uniqueness",
                "src/main/kotlin/com/qkt/cli/RunCommand.kt" to "live command lifecycle timing",
                "src/main/kotlin/com/qkt/cli/FetchCommand.kt" to "operator-selected current date",
                "src/main/kotlin/com/qkt/cli/IncidentCommand.kt" to "injectable incident capture default",
                "src/main/kotlin/com/qkt/cli/AuditTicksCommand.kt" to "interactive capture deadline",
                "src/main/kotlin/com/qkt/cli/ObserveCommand.kt" to "operator observation window",
                "src/main/kotlin/com/qkt/cli/PromotionCommand.kt" to "operator approval timestamp",
                "src/main/kotlin/com/qkt/cli/Promotion.kt" to "operator approval expiry default",
                "src/main/kotlin/com/qkt/cli/DaemonCommand.kt" to "daemon lifecycle timestamps",
                "src/main/kotlin/com/qkt/cli/editor/EditorManifest.kt" to "editor artifact timestamp default",
                "src/main/kotlin/com/qkt/cli/observe/EventRing.kt" to "operator event receipt timestamp",
                "src/main/kotlin/com/qkt/cli/daemon/StrategyHandleJson.kt" to "live status age calculation",
                "src/main/kotlin/com/qkt/cli/daemon/ControlPlane.kt" to "daemon start timestamp",
                "src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt" to "operator request timestamps",
                "src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt" to "live stop duration measurement",
                "src/main/kotlin/com/qkt/cli/daemon/StrategyRegistry.kt" to "daemon stop deadline",
                "src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt" to "daemon stop deadline",
                "src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt" to "deploy lifecycle timing",
                "src/main/kotlin/com/qkt/observe/insights/InsightsSink.kt" to "async egress deadlines",
                "src/main/kotlin/com/qkt/observe/EngineAuditJournal.kt" to "journal flush deadline",
            )
    }
}
