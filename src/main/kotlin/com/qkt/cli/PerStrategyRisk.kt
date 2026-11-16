package com.qkt.cli

import com.qkt.risk.SetCaps
import com.qkt.risk.SetRiskLimits
import com.qkt.risk.StrategyRiskLimits
import java.math.BigDecimal

/**
 * Phase 25D: per-strategy risk caps loaded from `qkt.config.yaml`.
 *
 * Each field is optional — null means "no override, the daemon-global rule (if any)
 * still applies." `LiveSession.start` constructs rule instances from the populated
 * fields and attaches them to that strategy's evaluation path.
 *
 * YAML shape (under `risk.per_strategy.<strategy_name>`):
 * ```yaml
 * risk:
 *   max_daily_loss: "1000"     # global, the daemon's strategies together
 *   per_strategy:
 *     ema_cross:
 *       max_daily_loss: "500"        # caps ema_cross at $500 even though global is $1000
 *       max_position_size: "1.0"     # rejects any order that would push |position| above 1.0
 *       max_open_positions: 3        # rejects new-symbol entries past 3 concurrent positions
 *       sets:
 *         stocks: { max_open_positions: 5, max_position_size: "2000" }   # over the symbol set `stocks` only
 * ```
 *
 * [sets] caps apply to the members of one symbol set the strategy declares ([SetCaps]); the members themselves
 * are known only once the strategy is parsed, so [toLimits] takes them.
 */
data class PerStrategyRisk(
    val maxDailyLoss: BigDecimal? = null,
    val maxPositionSize: BigDecimal? = null,
    val maxOpenPositions: Int? = null,
    val maxDrawdownPct: BigDecimal? = null,
    val maxDailyDrawdownPct: BigDecimal? = null,
    val maxTradesPerDay: Int? = null,
    val cooldownAfterLossMs: Long? = null,
    val cooldownAfterLossAfterConsecutive: Int = 1,
    val lossStreakHalt: Int? = null,
    val lossStreakHaltScope: com.qkt.risk.HaltScope = com.qkt.risk.HaltScope.PERSISTENT,
    val sets: Map<String, SetCaps> = emptyMap(),
) {
    /**
     * Convert CLI configuration into the runtime-neutral replay limits model. [setMembers] gives each declared
     * symbol set's member symbols; a cap on a set the strategy does not declare is refused by name.
     */
    fun toLimits(setMembers: Map<String, Set<String>> = emptyMap()): StrategyRiskLimits =
        StrategyRiskLimits(
            maxDailyLoss = maxDailyLoss,
            maxPositionSize = maxPositionSize,
            maxOpenPositions = maxOpenPositions,
            maxDrawdownPct = maxDrawdownPct,
            maxDailyDrawdownPct = maxDailyDrawdownPct,
            maxTradesPerDay = maxTradesPerDay,
            cooldownAfterLossMs = cooldownAfterLossMs,
            cooldownAfterLossAfterConsecutive = cooldownAfterLossAfterConsecutive,
            lossStreakHalt = lossStreakHalt,
            lossStreakHaltScope = lossStreakHaltScope,
            sets =
                sets.mapValues { (alias, caps) ->
                    val members =
                        setMembers[alias]
                            ?: error("sets.$alias: the strategy declares no symbol set '$alias'")
                    SetRiskLimits(members, caps.maxOpenPositions, caps.maxPositionSize)
                },
        )

    init {
        if (maxDailyLoss != null) {
            require(maxDailyLoss.signum() > 0) { "PerStrategyRisk.maxDailyLoss must be > 0: $maxDailyLoss" }
        }
        if (maxPositionSize != null) {
            require(maxPositionSize.signum() > 0) { "PerStrategyRisk.maxPositionSize must be > 0: $maxPositionSize" }
        }
        if (maxOpenPositions != null) {
            require(maxOpenPositions > 0) { "PerStrategyRisk.maxOpenPositions must be > 0: $maxOpenPositions" }
        }
        if (maxDrawdownPct != null) {
            require(maxDrawdownPct.signum() > 0 && maxDrawdownPct <= BigDecimal.ONE) {
                "PerStrategyRisk.maxDrawdownPct must be in (0, 1]: $maxDrawdownPct"
            }
        }
        if (maxDailyDrawdownPct != null) {
            require(maxDailyDrawdownPct.signum() > 0 && maxDailyDrawdownPct <= BigDecimal.ONE) {
                "PerStrategyRisk.maxDailyDrawdownPct must be in (0, 1]: $maxDailyDrawdownPct"
            }
        }
        if (maxTradesPerDay != null) {
            require(maxTradesPerDay > 0) { "PerStrategyRisk.maxTradesPerDay must be > 0: $maxTradesPerDay" }
        }
        if (cooldownAfterLossMs != null) {
            require(cooldownAfterLossMs > 0) {
                "PerStrategyRisk.cooldownAfterLossMs must be > 0: $cooldownAfterLossMs"
            }
        }
        require(cooldownAfterLossAfterConsecutive > 0) {
            "PerStrategyRisk.cooldownAfterLossAfterConsecutive must be > 0: $cooldownAfterLossAfterConsecutive"
        }
        if (lossStreakHalt != null) {
            require(lossStreakHalt > 0) { "PerStrategyRisk.lossStreakHalt must be > 0: $lossStreakHalt" }
        }
    }

    companion object {
        /** The keys `risk.per_strategy.<name>` accepts. */
        internal val KEYS =
            setOf(
                "max_daily_loss",
                "max_position_size",
                "max_open_positions",
                "max_drawdown_pct",
                "max_daily_drawdown_pct",
                "max_trades_per_day",
                "cooldown_after_loss",
                "cooldown_after_loss_ms",
                "cooldown_after_loss_after_consecutive",
                "loss_streak_halt",
                "loss_streak_halt_scope",
                "sets",
            )

        /** Every `risk.per_strategy` entry of a config's `risk:` block ([raw]), by strategy name. */
        @Suppress("UNCHECKED_CAST")
        internal fun parseAll(raw: Any?): Map<String, PerStrategyRisk> {
            val risk = raw as? Map<String, Any?> ?: return emptyMap()
            val perStrat = risk["per_strategy"] as? Map<String, Any?> ?: return emptyMap()
            return perStrat.mapValues { (_, v) ->
                val m = v as? Map<String, Any?> ?: return@mapValues PerStrategyRisk()
                PerStrategyRisk(
                    maxDailyLoss = m["max_daily_loss"]?.toString()?.let(::BigDecimal),
                    maxPositionSize = m["max_position_size"]?.toString()?.let(::BigDecimal),
                    maxOpenPositions = m["max_open_positions"]?.toString()?.toIntOrNull(),
                    maxDrawdownPct = Config.pctFraction(m["max_drawdown_pct"]?.toString()),
                    maxDailyDrawdownPct = Config.pctFraction(m["max_daily_drawdown_pct"]?.toString()),
                    maxTradesPerDay = m["max_trades_per_day"]?.toString()?.toIntOrNull(),
                    cooldownAfterLossMs =
                        (m["cooldown_after_loss"] ?: m["cooldown_after_loss_ms"])
                            ?.toString()
                            ?.let(Config::durationMs),
                    cooldownAfterLossAfterConsecutive =
                        m["cooldown_after_loss_after_consecutive"]
                            ?.toString()
                            ?.toIntOrNull()
                            ?: 1,
                    lossStreakHalt = m["loss_streak_halt"]?.toString()?.toIntOrNull(),
                    lossStreakHaltScope = Config.parseHaltScope(m["loss_streak_halt_scope"]?.toString()),
                    sets = parseSets(m["sets"]),
                )
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseSets(raw: Any?): Map<String, SetCaps> {
            val sets = raw as? Map<String, Any?> ?: return emptyMap()
            return sets.mapValues { (alias, v) ->
                val caps = v as? Map<String, Any?> ?: error("risk.per_strategy sets.$alias must be a key-value block")
                val unknown = caps.keys - SET_KEYS
                require(unknown.isEmpty()) {
                    "unknown risk.per_strategy sets.$alias key(s): ${unknown.sorted().joinToString(", ")}"
                }
                SetCaps(
                    maxOpenPositions = caps["max_open_positions"]?.toString()?.toIntOrNull(),
                    maxPositionSize = caps["max_position_size"]?.toString()?.let(::BigDecimal),
                )
            }
        }

        private val SET_KEYS = setOf("max_open_positions", "max_position_size")
    }
}
