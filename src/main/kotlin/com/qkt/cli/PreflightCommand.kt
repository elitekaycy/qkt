package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.instrument.StandardInstrumentRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

enum class PreflightStatus {
    PASS,
    WARN,
    FAIL,
}

data class PreflightCheck(
    val name: String,
    val status: PreflightStatus,
    val detail: String,
)

private data class PreflightTarget(
    val strategyAsts: List<StrategyAst>,
    val portfolioSymbols: List<String> = emptyList(),
)

object ProductionPreflight {
    fun evaluate(
        configPath: Path,
        stateDir: StateDir,
        strategyPath: Path? = null,
        forceProduction: Boolean = false,
    ): List<PreflightCheck> {
        val cfg =
            try {
                Config.load(configPath)
            } catch (e: Exception) {
                return listOf(
                    PreflightCheck(
                        "config.load",
                        PreflightStatus.FAIL,
                        e.message ?: e.toString(),
                    ),
                )
            }
        val production = forceProduction || cfg.runtimeMode.production
        val checks = mutableListOf<PreflightCheck>()
        checks.add(
            PreflightCheck(
                "runtime.mode",
                PreflightStatus.PASS,
                if (production) "production" else cfg.runtimeMode.name.lowercase(),
            ),
        )

        val target = strategyPath?.let { checks.parseTarget(it) }
        checks.add(stateCheck(cfg, stateDir, production))
        checks.add(journalCheck(stateDir, production))
        checks.add(riskCheck(cfg, production))
        checks.add(brokerConfigCheck(cfg, production))
        checks.add(brokerProfileCheck(cfg, production))
        checks.add(alertsCheck(cfg, production))
        if (target != null) {
            checks.add(symbolMetadataCheck(target, production))
            checks.add(dataFieldCheck(target))
        }
        return checks
    }

    private fun MutableList<PreflightCheck>.parseTarget(path: Path): PreflightTarget? {
        if (!Files.exists(path)) {
            add(PreflightCheck("strategy.parse", PreflightStatus.FAIL, "file not found: $path"))
            return null
        }
        return when (val parsed = Dsl.parseFileAny(path)) {
            is ParseResult.Success -> {
                when (val file = parsed.value) {
                    is ParsedFile.StrategyFile -> {
                        add(
                            PreflightCheck(
                                "strategy.parse",
                                PreflightStatus.PASS,
                                "${file.ast.name} v${file.ast.version}",
                            ),
                        )
                        PreflightTarget(strategyAsts = listOf(file.ast))
                    }
                    is ParsedFile.PortfolioFile ->
                        try {
                            val compiled = PortfolioLoader.load(path)
                            add(
                                PreflightCheck(
                                    "strategy.parse",
                                    PreflightStatus.PASS,
                                    "${file.ast.name} portfolio v${file.ast.version} (${compiled.children.size} child strategies)",
                                ),
                            )
                            PreflightTarget(
                                strategyAsts = compiled.children.map { it.ast },
                                portfolioSymbols = compiled.ast.streams.map { it.qktSymbol },
                            )
                        } catch (e: Exception) {
                            add(
                                PreflightCheck(
                                    "strategy.parse",
                                    PreflightStatus.FAIL,
                                    e.message ?: e.toString(),
                                ),
                            )
                            null
                        }
                }
            }
            is ParseResult.Failure -> {
                val msg = parsed.errors.joinToString("; ") { "${it.line}:${it.col} ${it.message}" }
                add(PreflightCheck("strategy.parse", PreflightStatus.FAIL, msg))
                null
            }
        }
    }

    private fun stateCheck(
        cfg: Config,
        stateDir: StateDir,
        production: Boolean,
    ): PreflightCheck {
        if (!cfg.stateEnabled) {
            return PreflightCheck(
                "state.persistence",
                if (production) PreflightStatus.FAIL else PreflightStatus.WARN,
                "state.enabled=false",
            )
        }
        val error = writableDirectory(stateDir.stateRoot)
        return if (error == null) {
            PreflightCheck("state.persistence", PreflightStatus.PASS, stateDir.stateRoot.toString())
        } else {
            PreflightCheck("state.persistence", if (production) PreflightStatus.FAIL else PreflightStatus.WARN, error)
        }
    }

    private fun journalCheck(
        stateDir: StateDir,
        production: Boolean,
    ): PreflightCheck {
        val root = stateDir.stateRoot.resolve("journal")
        val error = appendOnlyProbe(root)
        return if (error == null) {
            PreflightCheck("journal.append_only", PreflightStatus.PASS, root.toString())
        } else {
            PreflightCheck("journal.append_only", if (production) PreflightStatus.FAIL else PreflightStatus.WARN, error)
        }
    }

    private fun riskCheck(
        cfg: Config,
        production: Boolean,
    ): PreflightCheck =
        if (cfg.risk.isNotEmpty()) {
            PreflightCheck("risk.config", PreflightStatus.PASS, "explicit risk block present")
        } else {
            PreflightCheck(
                "risk.config",
                if (production) PreflightStatus.FAIL else PreflightStatus.WARN,
                "production mode requires an explicit risk block",
            )
        }

    private fun brokerConfigCheck(
        cfg: Config,
        production: Boolean,
    ): PreflightCheck =
        if (cfg.brokers.isNotEmpty()) {
            PreflightCheck("broker.config", PreflightStatus.PASS, "${cfg.brokers.size} broker profile(s) configured")
        } else {
            PreflightCheck(
                "broker.config",
                if (production) PreflightStatus.FAIL else PreflightStatus.WARN,
                "production mode requires at least one broker profile",
            )
        }

    private fun brokerProfileCheck(
        cfg: Config,
        production: Boolean,
    ): PreflightCheck {
        val mt5Configured = cfg.brokers.values.any { it["type"] == "mt5" }
        if (!mt5Configured) return PreflightCheck("broker.metadata", PreflightStatus.PASS, "no MT5 profile to validate")
        return try {
            val profiles =
                com.qkt.broker.mt5.MT5BrokerProfileLoader().load(
                    raw = cfg.brokers,
                    defaults = com.qkt.broker.mt5.MT5DefaultProfiles.all,
                    env = System.getenv(),
                    calendars = cfg.brokerCalendars,
                    aliases = cfg.brokerAliases,
                    capabilityRestrictions = cfg.brokerCapabilityRestrictions,
                    instrumentOverrides = cfg.brokerInstrumentOverrides,
                )
            PreflightCheck("broker.metadata", PreflightStatus.PASS, "${profiles.size} MT5 profile(s) resolved")
        } catch (e: Exception) {
            PreflightCheck(
                "broker.metadata",
                if (production) PreflightStatus.FAIL else PreflightStatus.WARN,
                e.message ?: e.toString(),
            )
        }
    }

    private fun alertsCheck(
        cfg: Config,
        production: Boolean,
    ): PreflightCheck {
        val enabled = cfg.notify.enabledChannels()
        val valid =
            enabled.filter { channel ->
                when (channel.type) {
                    "telegram" ->
                        channel.settings["bot_token"]?.isNotBlank() == true &&
                            channel.settings["chat_id"]?.isNotBlank() == true
                    else -> true
                }
            }
        if (valid.isNotEmpty()) {
            return PreflightCheck("notify.alerts", PreflightStatus.PASS, "${valid.size} enabled channel(s)")
        }
        if (enabled.isNotEmpty()) {
            return PreflightCheck(
                "notify.alerts",
                if (production) PreflightStatus.FAIL else PreflightStatus.WARN,
                "enabled alert channel is missing required credentials",
            )
        }
        val waiver = cfg.runtimeWaiver("alerts")
        if (waiver != null) return PreflightCheck("notify.alerts", PreflightStatus.WARN, "waived: $waiver")
        return PreflightCheck(
            "notify.alerts",
            if (production) PreflightStatus.FAIL else PreflightStatus.WARN,
            "no enabled alert channel; set notify.*.enabled=true or runtime.waivers.alerts.reason",
        )
    }

    private fun symbolMetadataCheck(
        target: PreflightTarget,
        production: Boolean,
    ): PreflightCheck {
        val symbols =
            (target.strategyAsts.flatMap { ast -> ast.streams.map { it.qktSymbol } } + target.portfolioSymbols)
                .distinct()
                .filter { it.isNotBlank() }
        val missing = symbols.filter { StandardInstrumentRegistry.lookup(it) == null && !it.startsWith("MACRO:") }
        return if (missing.isEmpty()) {
            val symbolCount = symbols.size
            PreflightCheck(
                "symbol.metadata",
                PreflightStatus.PASS,
                "$symbolCount symbol(s)",
            )
        } else {
            PreflightCheck(
                "symbol.metadata",
                if (production) PreflightStatus.FAIL else PreflightStatus.WARN,
                "missing instrument metadata for ${missing.joinToString()}",
            )
        }
    }

    private fun dataFieldCheck(target: PreflightTarget): PreflightCheck {
        val requirements =
            target.strategyAsts
                .map { StrategyDataRequirementScanner.scan(it) }
                .fold(StrategyDataRequirements(emptySet(), emptySet())) { acc, next ->
                    StrategyDataRequirements(
                        quoteAliases = acc.quoteAliases + next.quoteAliases,
                        volumeAliases = acc.volumeAliases + next.volumeAliases,
                    )
                }
        val parts = mutableListOf<String>()
        if (requirements.quoteAliases.isNotEmpty()) {
            parts.add(
                "quote=${
                    requirements.quoteAliases
                        .sorted()
                        .joinToString(",")
                }",
            )
        }
        if (requirements.volumeAliases.isNotEmpty()) {
            parts.add(
                "volume=${
                    requirements.volumeAliases
                        .sorted()
                        .joinToString(",")
                }",
            )
        }
        return PreflightCheck(
            "data.fields",
            PreflightStatus.PASS,
            if (parts.isEmpty()) "close/price only" else parts.joinToString(" "),
        )
    }

    private fun writableDirectory(path: Path): String? =
        try {
            Files.createDirectories(path)
            val probe = path.resolve(".qkt-preflight-${System.nanoTime()}.tmp")
            Files.writeString(probe, "ok\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.deleteIfExists(probe)
            null
        } catch (e: Exception) {
            "not writable: $path (${e.message})"
        }

    private fun appendOnlyProbe(root: Path): String? =
        try {
            Files.createDirectories(root)
            val probe = root.resolve(".qkt-preflight-${System.nanoTime()}.jsonl")
            Files.writeString(
                probe,
                "{\"probe\":1}\n",
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC,
            )
            Files.writeString(probe, "{\"probe\":2}\n", StandardOpenOption.APPEND, StandardOpenOption.DSYNC)
            Files.deleteIfExists(probe)
            null
        } catch (e: Exception) {
            "not appendable: $root (${e.message})"
        }
}

class PreflightCommand(
    private val args: Args,
) {
    fun run(): Int {
        val strategy = args.requirePositional(0, "<strategy.qkt>")
        val configPath = args.option("config")?.let(Path::of) ?: Config.locate() ?: Path.of("./qkt.config.yaml")
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val checks =
            ProductionPreflight.evaluate(
                configPath = configPath,
                stateDir = stateDir,
                strategyPath = Path.of(strategy),
                forceProduction = args.flag("production"),
            )
        checks.forEach { println("${it.status.name} ${it.name}: ${it.detail}") }
        return if (checks.any { it.status == PreflightStatus.FAIL }) ExitCodes.USER_ERROR else ExitCodes.SUCCESS
    }
}
