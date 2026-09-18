package com.qkt.cli

import java.time.Instant
import kotlinx.serialization.Serializable

/** An operator waiver of named promotion gates (or `all`), optionally expiring at [expiresAt]. */
@Serializable
data class PromotionWaiver(
    val gates: List<String>,
    val reason: String,
    val actor: String,
    val createdAt: String,
    val expiresAt: String? = null,
) {
    fun active(now: Instant = Instant.now()): Boolean =
        expiresAt
            ?.let { runCatching { Instant.parse(it).isAfter(now) }.getOrDefault(false) }
            ?: true

    fun waives(gate: String): Boolean {
        val normalized = gates.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        if ("all" in normalized) return true
        val candidate = gate.lowercase()
        return candidate in normalized || candidate.substringBefore(':') in normalized
    }
}
