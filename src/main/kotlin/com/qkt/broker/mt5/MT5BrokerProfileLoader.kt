package com.qkt.broker.mt5

import com.qkt.broker.OrderTypeCapability
import com.qkt.common.TradingCalendar
import java.math.BigDecimal

/**
 * Resolves raw YAML broker entries from `qkt.config.yaml` into [MT5BrokerProfile]s.
 *
 * Handles `extends:` chains (a profile inherits from a default or another profile),
 * env-var substitution (`${EXNESS_GATEWAY_URL}`), and merges per-profile overrides
 * over the base. Output is deterministic — same input → same profile list.
 *
 * Scalar fields arrive in [raw] (flat `key: value`). The nested config blocks — per-symbol
 * calendar rules, symbol aliases, disabled capabilities, and per-instrument venue specs — are
 * parsed separately (the flat YAML reader can't carry nested maps) and passed in keyed by
 * broker name. Each defaults to empty, so a profile that declares none behaves exactly as before
 * (all-FX calendar, base-inherited aliases/overrides).
 */
class MT5BrokerProfileLoader {
    /**
     * Returns one [MT5BrokerProfile] per `type: mt5` entry in [raw].
     *
     * [defaults] supplies the built-in templates available for `extends:` references.
     * [env] is consulted for `${VAR}` substitutions in raw values. [calendars] maps broker name →
     * ordered `(pattern, calendarName)` rules; [aliases] → `(qktSymbol, brokerSymbol)`;
     * [capabilityRestrictions] → disabled [OrderTypeCapability] names; [instrumentOverrides] →
     * `symbol → (field → value)` venue specs.
     */
    fun load(
        raw: Map<String, Map<String, String>>,
        defaults: Map<String, MT5BrokerProfile>,
        env: Map<String, String>,
        calendars: Map<String, List<Pair<String, String>>> = emptyMap(),
        aliases: Map<String, Map<String, String>> = emptyMap(),
        capabilityRestrictions: Map<String, List<String>> = emptyMap(),
        instrumentOverrides: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    ): List<MT5BrokerProfile> {
        val mt5Entries = raw.filterValues { it["type"] == "mt5" }
        val resolved = mutableMapOf<String, MT5BrokerProfile>()
        val pending = LinkedHashMap(mt5Entries)
        var madeProgress = true
        while (pending.isNotEmpty() && madeProgress) {
            madeProgress = false
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val (name, fields) = it.next()
                val extendsName = fields["extends"]
                val base: MT5BrokerProfile? =
                    when {
                        extendsName != null -> resolved[extendsName] ?: defaults[extendsName]
                        name in defaults -> defaults[name]
                        else -> null
                    }
                if (extendsName != null && base == null) continue
                resolved[name] =
                    applyOverrides(
                        name = name,
                        base = base,
                        fields = fields,
                        env = env,
                        calendarRules = calendars[name].orEmpty(),
                        yamlAliases = aliases[name].orEmpty(),
                        yamlCapabilityRestrictions = capabilityRestrictions[name].orEmpty(),
                        yamlInstrumentOverrides = instrumentOverrides[name].orEmpty(),
                    )
                it.remove()
                madeProgress = true
            }
        }
        check(pending.isEmpty()) {
            "MT5 profiles have unresolvable extends chain: ${pending.keys}"
        }
        val magicGroups = resolved.values.groupBy { it.magic }.filterValues { it.size > 1 }
        check(magicGroups.isEmpty()) {
            "MT5 profile magic numbers must be unique; collisions: $magicGroups"
        }
        return resolved.values.toList()
    }

    private fun applyOverrides(
        name: String,
        base: MT5BrokerProfile?,
        fields: Map<String, String>,
        env: Map<String, String>,
        calendarRules: List<Pair<String, String>>,
        yamlAliases: Map<String, String>,
        yamlCapabilityRestrictions: List<String>,
        yamlInstrumentOverrides: Map<String, Map<String, String>>,
    ): MT5BrokerProfile {
        val gatewayUrl =
            pick("gateway_url", fields, env, name, base?.gatewayUrl)
                ?: error("MT5 profile '$name' missing required field: gateway_url")
        val suffix = pick("symbol_suffix", fields, env, name, base?.symbolPolicy?.suffix) ?: ""
        val magic =
            pick("magic", fields, env, name, base?.magic?.toString())?.toInt()
                ?: error("MT5 profile '$name' missing required field: magic")
        val tz =
            pick("server_tz_offset_hours", fields, env, name, base?.serverTzOffsetHours?.toString())?.toInt()
                ?: error("MT5 profile '$name' missing required field: server_tz_offset_hours")
        // YAML wins over the inherited base on key conflict.
        val aliases = (base?.symbolPolicy?.aliases ?: emptyMap()) + yamlAliases
        val capabilityRestrictions =
            (base?.capabilityRestrictions ?: emptySet()) +
                yamlCapabilityRestrictions.map { parseCapability(name, it) }
        val instrumentOverrides =
            (base?.instrumentOverrides ?: emptyMap()) +
                yamlInstrumentOverrides.mapValues { (symbol, spec) -> parseInstrumentSpec(name, symbol, spec) }
        val symbolCalendars =
            if (calendarRules.isNotEmpty()) {
                SymbolCalendars(
                    calendarRules.map { (pattern, cal) -> SymbolCalendars.Rule(pattern, calendarByName(name, cal)) },
                    default = TradingCalendar.fxDefault(),
                )
            } else {
                base?.symbolCalendars ?: SymbolCalendars.fxDefault()
            }
        return MT5BrokerProfile(
            name = name,
            gatewayUrl = gatewayUrl,
            symbolPolicy = SymbolPolicy(suffix = suffix, aliases = aliases),
            serverTzOffsetHours = tz,
            magic = magic,
            instrumentOverrides = instrumentOverrides,
            pollIntervalMs =
                pick("poll_interval_ms", fields, env, name, base?.pollIntervalMs?.toString())?.toLong()
                    ?: 1000L,
            httpTimeoutMs =
                pick("http_timeout_ms", fields, env, name, base?.httpTimeoutMs?.toString())?.toLong()
                    ?: 5000L,
            retryAttempts =
                pick("retry_attempts", fields, env, name, base?.retryAttempts?.toString())?.toInt()
                    ?: 3,
            deviationPoints =
                pick("deviation_points", fields, env, name, base?.deviationPoints?.toString())?.toInt()
                    ?: 20,
            capabilityRestrictions = capabilityRestrictions,
            symbolCalendars = symbolCalendars,
        )
    }

    private fun pick(
        field: String,
        fields: Map<String, String>,
        env: Map<String, String>,
        name: String,
        baseValue: String?,
    ): String? {
        val envKey = "QKT_BROKER_${name.uppercase().replace("-", "_")}_${field.uppercase()}"
        return env[envKey]
            ?: fields[field]
            ?: baseValue
    }

    private fun calendarByName(
        profile: String,
        cal: String,
    ): TradingCalendar =
        when (cal.trim().lowercase()) {
            "fx" -> TradingCalendar.fxDefault()
            "crypto" -> TradingCalendar.crypto()
            "nyse" -> TradingCalendar.nyse()
            else -> error("MT5 profile '$profile' has unknown calendar '$cal' (expected fx|crypto|nyse)")
        }

    private fun parseCapability(
        profile: String,
        cap: String,
    ): OrderTypeCapability =
        runCatching { OrderTypeCapability.valueOf(cap.trim().uppercase()) }
            .getOrElse { error("MT5 profile '$profile' has unknown capability '$cap'") }

    private fun parseInstrumentSpec(
        profile: String,
        symbol: String,
        spec: Map<String, String>,
    ): InstrumentSpec {
        fun req(key: String): String = spec[key] ?: error("MT5 profile '$profile' instrument '$symbol' missing '$key'")
        return InstrumentSpec(
            minVolume = BigDecimal(req("min_volume")),
            volumeStep = BigDecimal(req("volume_step")),
            pointSize = BigDecimal(req("point_size")),
            digits = req("digits").toInt(),
            tradeStopsLevelPoints = req("trade_stops_level_points").toInt(),
        )
    }
}
