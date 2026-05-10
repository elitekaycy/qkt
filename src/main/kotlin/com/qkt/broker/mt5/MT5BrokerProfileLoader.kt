package com.qkt.broker.mt5

/**
 * Resolves raw YAML broker entries from `qkt.config.yaml` into [MT5BrokerProfile]s.
 *
 * Handles `extends:` chains (a profile inherits from a default or another profile),
 * env-var substitution (`${EXNESS_GATEWAY_URL}`), and merges per-profile overrides
 * over the base. Output is deterministic — same input → same profile list.
 */
class MT5BrokerProfileLoader {
    /**
     * Returns one [MT5BrokerProfile] per `type: mt5` entry in [raw].
     *
     * [defaults] supplies the built-in templates available for `extends:` references.
     * [env] is consulted for `${VAR}` substitutions in raw values.
     */
    fun load(
        raw: Map<String, Map<String, String>>,
        defaults: Map<String, MT5BrokerProfile>,
        env: Map<String, String>,
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
                resolved[name] = applyOverrides(name, base, fields, env)
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
        val aliases = base?.symbolPolicy?.aliases ?: emptyMap()
        return MT5BrokerProfile(
            name = name,
            gatewayUrl = gatewayUrl,
            symbolPolicy = SymbolPolicy(suffix = suffix, aliases = aliases),
            serverTzOffsetHours = tz,
            magic = magic,
            instrumentOverrides = base?.instrumentOverrides ?: emptyMap(),
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
            capabilityRestrictions = base?.capabilityRestrictions ?: emptySet(),
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
}
