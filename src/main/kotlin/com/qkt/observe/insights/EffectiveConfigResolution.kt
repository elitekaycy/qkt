package com.qkt.observe.insights

import java.security.MessageDigest
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.qkt.observe.insights.EffectiveConfigResolution")
private val ADAPTER = Regex("[a-z0-9_-]{1,32}")
private val CONNECTORS = setOf("mt5", "vgp", "paper")
private val ENVIRONMENTS = setOf("demo", "real", "contest")

/**
 * [metadata] with its `effectiveConfig` completed by what only a started session knows, and stamped with
 * `effectiveConfigSha256`: each account's connector, venue and environment as the venue states them, its
 * tick poll interval from [tickLagMs] ([com.qkt.marketdata.source.MarketSource.tickDeliveryLagMsFor]; a feed
 * that does not poll states none), and the `tradingMode` those add up to. The hash covers the whole
 * config, so two starts on the same settings share one version.
 *
 * Telemetry only: any failure here sends the start without a config and the strategy trades on regardless.
 */
fun InsightsVenues.withResolvedEffectiveConfig(
    metadata: Map<String, Any?>,
    tickLagMs: (String) -> Long,
): Map<String, Any?> {
    val config = metadata["effectiveConfig"] as? Map<*, *> ?: return metadata
    return try {
        val symbols = (metadata["symbols"] as? List<*>).orEmpty().filterIsInstance<String>()
        val accounts =
            (config["accounts"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { account ->
                resolvedAccount(account, symbols, tickLagMs)
            }
        val resolved =
            config.entries.associateTo(linkedMapOf<String, Any?>()) { (k, v) -> k.toString() to v }
        resolved["accounts"] = accounts
        resolved["tradingMode"] = tradingMode(metadata["runtimeMode"] as? String, accounts)
        metadata + mapOf("effectiveConfig" to resolved, "effectiveConfigSha256" to sha256(resolved))
    } catch (e: Exception) {
        log.warn("effective config not stated: {}", e.javaClass.simpleName)
        metadata - "effectiveConfig"
    }
}

private fun InsightsVenues.resolvedAccount(
    account: Map<*, *>,
    symbols: List<String>,
    tickLagMs: (String) -> Long,
): Map<String, Any?> {
    val name = account["name"] as String
    val identity = describedBy(name)?.venueIdentity()
    val poll = symbols.filter { it.startsWith("$name:", ignoreCase = true) }.maxOfOrNull(tickLagMs)
    return account.entries.associateTo(linkedMapOf<String, Any?>()) { (k, v) -> k.toString() to v }.apply {
        identity?.connector?.takeIf { it in CONNECTORS }?.let { put("connector", it) }
        identity?.adapter?.takeIf { ADAPTER.matches(it) }?.let { put("adapter", it) }
        identity?.tradeMode?.takeIf { it in ENVIRONMENTS }?.let { put("environment", it) }
        poll?.takeIf { it > 0L }?.let { put("tickPollIntervalMs", it) }
    }
}

/** `paper`, `demo`, `contest` or `live`; the riskiest of the strategy's accounts; null when the venue has not said. */
private fun tradingMode(
    runtimeMode: String?,
    accounts: List<Map<String, Any?>>,
): String? {
    val environments = accounts.map { it["environment"] }
    return when {
        runtimeMode == "paper" || accounts.isEmpty() -> "paper"
        "real" in environments -> "live"
        "contest" in environments -> "contest"
        environments.all { it == "demo" } -> "demo"
        accounts.all { it["connector"] == "paper" } -> "paper"
        else -> null
    }
}

private fun sha256(config: Map<String, Any?>): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(InsightsEnvelope.json(config).toByteArray())
        .joinToString("") { "%02x".format(it) }
