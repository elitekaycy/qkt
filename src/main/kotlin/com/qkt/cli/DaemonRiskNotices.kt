package com.qkt.cli

import com.qkt.cli.daemon.DaemonHardLimitGuard
import com.qkt.cli.daemon.GuardedSession
import com.qkt.cli.daemon.StartupDeploys
import com.qkt.cli.daemon.StrategyRegistry
import com.qkt.risk.DaemonRiskScope
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/** `risk.hard_daily_loss`: realized plus open loss across the daemon that flattens it; null is off. */
val Config.hardDailyLoss: BigDecimal?
    get() = risk["hard_daily_loss"]?.let(::BigDecimal)

/** Fails the config load on a hard limit that is not a positive decimal at or above `max_daily_loss`. */
internal fun validateHardDailyLoss(risk: Map<*, *>?) {
    val raw = risk?.get("hard_daily_loss")?.toString() ?: return
    val hard = raw.toBigDecimalOrNull()
    require(hard != null && hard.signum() > 0) { "risk.hard_daily_loss must be a decimal > 0: $raw" }
    val max = risk["max_daily_loss"]?.toString()?.toBigDecimalOrNull() ?: return
    require(hard >= max) { "risk.hard_daily_loss $raw must be >= risk.max_daily_loss $max" }
}

/** Why a run sending orders through a venue relies on the built-in daily loss limit, or null when it does not. */
internal fun defaultDailyLossWarning(
    cfg: Config,
    tradesThroughVenue: Boolean,
): String? {
    if (!tradesThroughVenue || "max_daily_loss" in cfg.risk) return null
    return "risk.max_daily_loss is not set, so this live run stops at the built-in default of " +
        "${Config.DEFAULT_MAX_DAILY_LOSS} in account currency, whatever the account's size. " +
        "Set risk.max_daily_loss in qkt.config.yaml to the loss you mean, or 0 to turn it off."
}

/**
 * What a daemon says about its risk config at start. `book_risk` is enforced by the portfolio deployer alone: a
 * config declaring exposure limits and deploying single strategies reads as protected while nothing bounds total
 * notional. Measured on a demo account, a 100-leg burst reached roughly 1.16x capital against a declared 0.60
 * (catalog row A23).
 */
internal fun warnDaemonRiskConfig(
    cfg: Config,
    tradesThroughVenue: Boolean,
) {
    val log = LoggerFactory.getLogger("com.qkt.cli.DaemonCommand")
    if (cfg.bookRisk?.limits != null) log.warn(STANDALONE_BOOK_RISK_WARNING)
    defaultDailyLossWarning(cfg, tradesThroughVenue)?.let(log::warn)
}

/** What the hard limit watches: every session of the daemon, a portfolio's children included. */
internal fun StrategyRegistry.guardedSessions(): List<GuardedSession> = list().map { GuardedSession(it.name, it.live) }

/** A `qkt bot` session runs alone, with no daemon to enforce the hard limit: says so, and what a live run warns. */
internal fun warnBotSessionRiskConfig(
    cfg: Config,
    tradesThroughVenue: Boolean,
) {
    defaultDailyLossWarning(cfg, tradesThroughVenue)?.let { System.err.println("[WARN] $it") }
    if (cfg.hardDailyLoss != null) {
        System.err.println(
            "[WARN] risk.hard_daily_loss is enforced by qkt daemon only; this session does not apply it.",
        )
    }
}

/**
 * Points [scope] at what the daemon runs, tells it what `--load-dir` is about to deploy, and starts the hard limit's
 * guard, or returns null with the limit off.
 */
internal fun watchDaemonRisk(
    scope: DaemonRiskScope,
    registry: StrategyRegistry,
    startupDeploys: StartupDeploys,
): DaemonHardLimitGuard? {
    scope.composition.members {
        registry.list().any { it.childMeta == null } to registry.listPortfolios().isNotEmpty()
    }
    startupDeploys.declareComposition(scope)
    if (scope.hardDailyLoss == null) return null
    return DaemonHardLimitGuard(scope, { registry.guardedSessions() }).also { it.start() }
}
