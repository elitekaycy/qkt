package com.qkt.marketdata.hub

import java.nio.file.Path

/**
 * The `hub:` block of `qkt.config.yaml`: where the qkt-data-hub store is, and how much of it to
 * believe.
 *
 * It says only *where* and *how safe*. It never describes fields, parsing or derivations -- that
 * is the hub's schema, read from the store's manifest -- because a config that could teach the
 * engine to parse a provider would move acquisition back into the trading process through a side
 * door. Unknown keys are rejected at load: a typo in a safety setting must fail, not silently
 * turn the setting off.
 *
 * ```yaml
 * hub:
 *   root: /var/lib/qkt-hub
 *   min_lag_ms: 0
 *   refuse_derived: true
 *   stale_after_ms: 900000
 * ```
 */
data class HubStoreConfig(
    val root: Path?,
    val policy: HubPolicy = HubPolicy(),
    val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {
    init {
        require(staleAfterMs > 0L) { "hub.stale_after_ms must be positive, got $staleAfterMs" }
    }

    companion object {
        const val DEFAULT_STALE_AFTER_MS: Long = 900_000L
        private val KEYS = setOf("root", "min_lag_ms", "refuse_derived", "stale_after_ms")

        /** No block: no store configured. `HUB:` streams then need `--hub-root` or the environment. */
        val NONE: HubStoreConfig = HubStoreConfig(root = null)

        @Suppress("UNCHECKED_CAST")
        fun parse(raw: Any?): HubStoreConfig {
            val map = raw as? Map<String, Any?> ?: return NONE
            val unknown = map.keys - KEYS
            require(unknown.isEmpty()) { "unknown hub key(s): ${unknown.sorted().joinToString(", ")}" }
            val root =
                map["root"]
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Path.of(it) }
            return HubStoreConfig(
                root = root,
                policy =
                    HubPolicy(
                        minLagMs = parseLong(map, "min_lag_ms", 0L),
                        refuseDerived = parseBool(map, "refuse_derived", false),
                    ),
                staleAfterMs = parseLong(map, "stale_after_ms", DEFAULT_STALE_AFTER_MS),
            )
        }

        private fun parseLong(
            map: Map<String, Any?>,
            key: String,
            default: Long,
        ): Long {
            val raw = map[key] ?: return default
            return raw.toString().trim().toLongOrNull()
                ?: throw IllegalArgumentException("hub.$key must be an integer, got '$raw'")
        }

        private fun parseBool(
            map: Map<String, Any?>,
            key: String,
            default: Boolean,
        ): Boolean {
            val raw = map[key] ?: return default
            return when (raw.toString().trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("hub.$key must be true or false, got '$raw'")
            }
        }
    }
}

/**
 * Which hub root a run uses, in precedence order: an explicit `--hub-root`, then the config's
 * `hub.root`, then `QKT_HUB_ROOT` in the environment, then `<data_root>/hub` so a bare checkout
 * works with no configuration at all.
 *
 * The order is deliberate. The command line is the operator's most specific intent; the config is
 * the deployment's declared contract; the environment is how a container is told where a mount
 * landed; the default is only for local work.
 */
fun resolveHubRoot(
    cliOption: String?,
    config: HubStoreConfig,
    dataRoot: Path,
): Path =
    cliOption?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        ?: config.root
        ?: hubRoot(dataRoot)

/**
 * The hub root for a LIVE process: the config's `hub.root`, else `QKT_HUB_ROOT`, else none.
 *
 * Unlike a backtest there is no `<data_root>/hub` fallback. A live book that binds a hub stream
 * and has no store configured should fail at deploy, not quietly read an empty directory beside
 * its tick cache and trade as if every fact were unknown.
 */
fun liveHubRoot(config: HubStoreConfig): Path? =
    config.root
        ?: System.getenv(HubMarketSource.ROOT_ENV)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
