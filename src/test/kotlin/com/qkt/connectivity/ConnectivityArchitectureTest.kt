package com.qkt.connectivity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Keeps connectors swappable: core never names a connector, connectors never reach above the
 * shared model, and neither the contracts nor one connector depend on another connector.
 * Comments are ignored — only code dependencies count.
 */
class ConnectivityArchitectureTest {
    private val root: Path = Path.of("src/main/kotlin/com/qkt")

    /**
     * Operator tools that still speak to a connector directly, and which connectors each may
     * name. They are not on the trading path; migrating them is future work. This map may only
     * shrink — the stale-entry test fails when an entry is no longer needed.
     */
    private val toolAllowList: Map<String, Set<String>> =
        mapOf(
            "cli/AuditTicksCommand.kt" to setOf("mt5"),
            "cli/BrokersCommand.kt" to setOf("mt5"),
            "cli/FetchCommand.kt" to setOf("mt5", "bybit"),
            "cli/InstrumentsCommand.kt" to setOf("mt5"),
            "cli/Mt5FeedAudit.kt" to setOf("mt5"),
            "cli/PreflightCommand.kt" to setOf("mt5"),
            "tools/parity/ParityBarsXauusd.kt" to setOf("mt5"),
            "tools/parity/ParityDukascopyMt5Xauusd.kt" to setOf("mt5"),
            "tools/parity/ParityTicksXauusd.kt" to setOf("mt5"),
            "trade/BotGateway.kt" to setOf("mt5"),
            "trade/BotGatewayResult.kt" to setOf("mt5"),
        )

    private val forbiddenForConnectors =
        listOf("app", "cli", "risk", "observe", "dsl", "backtest", "trade", "research")

    private val sources: Map<String, String> by lazy {
        Files.walk(root).use { paths ->
            paths
                .filter { it.extension == "kt" }
                .toList()
                .associate { root.relativize(it).invariantSeparatorsPathString to codeOnly(it.readText()) }
        }
    }

    private fun codeOnly(text: String): String =
        text
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    private fun connectorOf(path: String): String? = Regex("^connector/([^/]+)/").find(path)?.groupValues?.get(1)

    private fun connectorsNamedIn(code: String): Set<String> =
        Regex("com\\.qkt\\.connector\\.([a-z0-9]+)").findAll(code).map { it.groupValues[1] }.toSet()

    @Test
    fun `nothing outside a connector references it, except the listed operator tools`() {
        val offenders =
            sources.flatMap { (path, code) ->
                (connectorsNamedIn(code) - setOfNotNull(connectorOf(path)) - toolAllowList[path].orEmpty())
                    .map { "$path -> connector.$it" }
            }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `every allow-listed tool still needs its entry`() {
        val stale =
            toolAllowList.flatMap { (path, allowed) ->
                val named = sources[path]?.let(::connectorsNamedIn).orEmpty()
                (allowed - named).map { "$path no longer names connector.$it" }
            }

        assertThat(stale).`as`("remove these from the allow-list").isEmpty()
    }

    @Test
    fun `connectors use only the shared model and the contracts`() {
        val offenders =
            sources.filterKeys { connectorOf(it) != null }.flatMap { (path, code) ->
                forbiddenForConnectors
                    .filter { Regex("com\\.qkt\\.$it\\.").containsMatchIn(code) }
                    .map { "$path -> $it" }
            }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the contracts reference no connector`() {
        val offenders =
            sources
                .filterKeys { it.startsWith("connectivity/") }
                .filterValues { connectorsNamedIn(it).isNotEmpty() }
                .keys

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `every connector package registers a connector service`() {
        val packages = sources.keys.mapNotNull(::connectorOf).toSet()
        val services =
            Path
                .of("src/main/resources/META-INF/services/com.qkt.connectivity.Connector")
                .readText()
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { Regex("^com\\.qkt\\.connector\\.([a-z0-9]+)\\.").find(it)?.groupValues?.get(1) }
                .toSet()

        assertThat(services).isEqualTo(packages)
    }
}
