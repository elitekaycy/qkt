package com.qkt.cli

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Parsed `qkt.config.yaml` — the operator-facing config file.
 *
 * Source-of-truth schema: see [reference/config-schema](https://elitekaycy.github.io/qkt/reference/config-schema/).
 * Env-var references like `${EXNESS_GATEWAY_URL}` are expanded at load time.
 */
data class Config(
    val source: String = "tv",
    val dataRoot: String = "./data",
    val startingBalance: BigDecimal = BigDecimal.ZERO,
    val logLevel: String = "info",
    val tv: Map<String, String> = emptyMap(),
    val fetchers: Map<String, Map<String, String>> = emptyMap(),
    val brokers: Map<String, Map<String, String>> = emptyMap(),
) {
    companion object {
        // Matches `${NAME}` and `${NAME:-default}`. The default is used when the env
        // var / system property is unset; without it, the literal `${...}` stays in the
        // string and broker init logs a degraded-config warning.
        private val varRegex = Regex("\\$\\{([A-Z_][A-Z_0-9]*)(?::-([^}]*))?}")

        /** Reads the YAML at [path] and parses it. Returns built-in defaults if the file is missing. */
        fun load(path: Path): Config {
            if (!Files.exists(path)) return defaults()
            val raw = Files.readString(path)
            val expanded = expandVars(raw)

            @Suppress("UNCHECKED_CAST")
            val map =
                Load(LoadSettings.builder().build())
                    .loadFromString(expanded) as? Map<String, Any?>
                    ?: return defaults()
            return Config(
                source = (map["source"] as? String) ?: "tv",
                dataRoot = (map["data_root"] as? String) ?: "./data",
                startingBalance =
                    (map["starting_balance"]?.toString() ?: "0").let(::BigDecimal),
                logLevel = (map["log_level"] as? String) ?: "info",
                tv = parseFlat(map["tv"]),
                fetchers = parseNested(map["fetchers"]),
                brokers = parseNested(map["brokers"]),
            )
        }

        private fun defaults(): Config =
            Config(
                source = "local",
                dataRoot = "./data",
                startingBalance = BigDecimal.ZERO,
            )

        @Suppress("UNCHECKED_CAST")
        private fun parseFlat(raw: Any?): Map<String, String> {
            val m = raw as? Map<String, Any?> ?: return emptyMap()
            return m.mapValues { (_, v) -> v?.toString() ?: "" }
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseNested(raw: Any?): Map<String, Map<String, String>> {
            val outer = raw as? Map<String, Any?> ?: return emptyMap()
            return outer.mapValues { (_, v) ->
                (v as? Map<String, Any?> ?: emptyMap())
                    .mapValues { (_, vv) -> vv?.toString() ?: "" }
            }
        }

        private fun expandVars(s: String): String =
            varRegex.replace(s) { m ->
                val name = m.groupValues[1]
                val default = m.groups[2]?.value
                System.getenv(name) ?: System.getProperty(name) ?: default ?: m.value
            }
    }
}
