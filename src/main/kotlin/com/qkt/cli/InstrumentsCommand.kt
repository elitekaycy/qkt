package com.qkt.cli

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.broker.mt5.MT5BrokerProfileLoader
import com.qkt.broker.mt5.MT5Client
import com.qkt.broker.mt5.MT5DefaultProfiles
import com.qkt.broker.mt5.MT5Symbol
import com.qkt.broker.mt5.MT5SymbolInfo
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.YamlInstrumentRegistry
import java.nio.file.Files
import java.nio.file.Path

/** `qkt instruments verify` reconciles static YAML metadata with live MT5 symbol rules. */
class InstrumentsCommand(
    private val args: Args,
    private val symbolInfo: (MT5BrokerProfile, String) -> MT5SymbolInfo? = ::fetchSymbolInfo,
) {
    /** Run the selected instruments operation. */
    fun run(): Int =
        when (args.firstNonOption()) {
            "verify" -> verify()
            else -> {
                System.err.println("qkt: usage: qkt instruments verify [--broker NAME] [--json]")
                ExitCodes.ARG_ERROR
            }
        }

    private fun verify(): Int {
        val configPath =
            args.option("config")?.let(Path::of)
                ?: Config.locate()
                ?: return userError("no qkt.config.yaml found; pass --config <path>")
        val config = Config.load(configPath)
        val instrumentsPath =
            args.option("instruments")?.let(Path::of)
                ?: Path.of(config.dataRoot).resolve("instruments.yaml")
        if (!Files.exists(instrumentsPath)) return userError("instruments.yaml not found at $instrumentsPath")

        val profiles =
            runCatching {
                MT5BrokerProfileLoader().load(
                    raw = config.brokers,
                    defaults = MT5DefaultProfiles.all,
                    env = System.getenv(),
                    calendars = config.brokerCalendars,
                    aliases = config.brokerAliases,
                    capabilityRestrictions = config.brokerCapabilityRestrictions,
                    instrumentOverrides = config.brokerInstrumentOverrides,
                )
            }.getOrElse { return userError("broker profile load failed: ${it.message}") }
        val selectedName = args.option("broker")
        val selected =
            if (selectedName == null) {
                profiles
            } else {
                profiles.filter { it.name.equals(selectedName, ignoreCase = true) }.also {
                    if (it.isEmpty()) return userError("unknown MT5 broker profile '$selectedName'")
                }
            }
        val byPrefix = selected.associateBy { it.name.uppercase() }
        val registry =
            runCatching { YamlInstrumentRegistry.load(instrumentsPath) }
                .getOrElse { return userError(it.message ?: "failed to load $instrumentsPath") }
        val checks =
            registry.all().mapNotNull { expected ->
                val profile = byPrefix[expected.qktSymbol.substringBefore(':').uppercase()] ?: return@mapNotNull null
                val wire = MT5Symbol(profile.symbolPolicy).toBroker(expected.qktSymbol.substringAfter(':'))
                val actual = runCatching { symbolInfo(profile, wire) }.getOrNull()
                compare(expected, profile, wire, actual)
            }
        if (checks.isEmpty()) {
            return userError("no instruments in $instrumentsPath match the selected MT5 profile(s)")
        }

        if (args.flag("json")) {
            println(checks.joinToString(prefix = "[", postfix = "]") { it.json() })
        } else {
            for (check in checks) {
                if (check.mismatches.isEmpty()) {
                    println("OK ${check.qktSymbol} -> ${check.profile}:${check.wireSymbol}")
                } else {
                    println("MISMATCH ${check.qktSymbol} -> ${check.profile}:${check.wireSymbol}")
                    for (mismatch in check.mismatches) {
                        println("  ${mismatch.field}: yaml=${mismatch.expected} venue=${mismatch.actual}")
                    }
                }
            }
        }
        return if (checks.all { it.mismatches.isEmpty() }) ExitCodes.SUCCESS else ExitCodes.USER_ERROR
    }

    private fun compare(
        expected: InstrumentMeta,
        profile: MT5BrokerProfile,
        wire: String,
        actual: MT5SymbolInfo?,
    ): Check {
        if (actual == null) {
            return Check(
                expected.qktSymbol,
                profile.name,
                wire,
                listOf(Mismatch("symbol_info", "available", "unavailable")),
            )
        }
        val mismatches = mutableListOf<Mismatch>()

        fun decimal(
            field: String,
            left: java.math.BigDecimal?,
            right: java.math.BigDecimal?,
        ) {
            if (left == null && right == null) return
            if (left == null || right == null || left.compareTo(right) != 0) {
                mismatches.add(Mismatch(field, left?.toPlainString() ?: "null", right?.toPlainString() ?: "null"))
            }
        }
        decimal("contractSize", expected.contractSize, actual.contractSize)
        decimal("volumeStep", expected.volumeStep, actual.volumeStep)
        decimal("volumeMin", expected.volumeMin, actual.volumeMin)
        decimal("volumeMax", expected.volumeMax, actual.volumeMax)
        decimal("pointSize", expected.pointSize, actual.point)
        if (expected.digits != actual.digits) {
            mismatches.add(Mismatch("digits", expected.digits.toString(), actual.digits.toString()))
        }
        if (expected.tradeStopsLevelPoints != actual.tradeStopsLevel) {
            mismatches.add(
                Mismatch(
                    "tradeStopsLevelPoints",
                    expected.tradeStopsLevelPoints.toString(),
                    actual.tradeStopsLevel.toString(),
                ),
            )
        }
        return Check(expected.qktSymbol, profile.name, wire, mismatches)
    }

    private fun userError(message: String): Int {
        System.err.println("qkt: instruments verify failed: $message")
        return ExitCodes.USER_ERROR
    }

    private data class Check(
        val qktSymbol: String,
        val profile: String,
        val wireSymbol: String,
        val mismatches: List<Mismatch>,
    ) {
        fun json(): String =
            "{\"symbol\":${quote(qktSymbol)},\"profile\":${quote(profile)}," +
                "\"wireSymbol\":${quote(wireSymbol)},\"ok\":${mismatches.isEmpty()}," +
                "\"mismatches\":[${mismatches.joinToString { it.json() }}]}"
    }

    private data class Mismatch(
        val field: String,
        val expected: String,
        val actual: String,
    ) {
        fun json(): String = "{\"field\":${quote(field)},\"expected\":${quote(expected)},\"actual\":${quote(actual)}}"
    }

    private companion object {
        fun fetchSymbolInfo(
            profile: MT5BrokerProfile,
            wireSymbol: String,
        ): MT5SymbolInfo? =
            MT5Client(
                gatewayUrl = profile.gatewayUrl,
                serverTimeZone = profile.serverTimeZone,
                httpTimeoutMs = profile.httpTimeoutMs,
                retryAttempts = profile.retryAttempts,
                apiKey = profile.apiKey,
            ).getSymbolInfo(wireSymbol)

        fun quote(value: String): String =
            buildString {
                append('"')
                for (char in value) {
                    when (char) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(char)
                    }
                }
                append('"')
            }
    }
}
