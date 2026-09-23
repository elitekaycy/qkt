package com.qkt.backtest

import com.qkt.broker.FixedPointsSlippage
import com.qkt.broker.FullFill
import com.qkt.broker.InstrumentSlippage
import com.qkt.broker.NoBrokerRejections
import com.qkt.broker.PartialFillModel
import com.qkt.broker.RejectEveryNthOrder
import com.qkt.broker.RejectionModel
import com.qkt.broker.SlippageModel
import com.qkt.broker.TakeProfitFill
import com.qkt.broker.UniformRandomSlippage
import com.qkt.broker.ZeroSlippage
import com.qkt.evidence.ExecutionEvidence
import java.math.BigDecimal

enum class ExecutionPreset(
    val id: String,
) {
    PAPER_FAST("paper-fast"),
    MT5_BASIC("mt5-basic"),
    MT5_REALISTIC("mt5-realistic"),
    STRESS("stress"),
    ;

    companion object {
        fun fromConfig(raw: String?): ExecutionPreset =
            when (raw?.trim()?.lowercase()) {
                null, "", "paper", "paper-fast" -> PAPER_FAST
                "mt5", "mt5-sim", "mt5-basic" -> MT5_BASIC
                "mt5-realistic", "realistic" -> MT5_REALISTIC
                "stress" -> STRESS
                else -> error("unknown execution preset '$raw' (valid: paper-fast, mt5-basic, mt5-realistic, stress)")
            }
    }
}

enum class SlippageSpec {
    ZERO,
    INSTRUMENT,
    FIXED_POINTS,
    UNIFORM_RANDOM,
}

data class ExecutionSimulationConfig(
    val preset: ExecutionPreset = ExecutionPreset.PAPER_FAST,
    val seed: Long? = null,
    val latencyMs: Long = 0L,
    /** Minimum gap between consecutive releases on the venue's send lane ([com.qkt.broker.SendLane]); 0 = none. */
    val orderSpacingMs: Long = 0L,
    /**
     * Execution delay applied to protective stops only (#1135): a crossed stop fills at the
     * first quote at or after `trigger + stopLatencyMs`, sided, plus slippage. Entry latency
     * ([latencyMs]) delays order placement and does not model this. 0 keeps the on-trigger fill.
     */
    val stopLatencyMs: Long = 0L,
    /** Pricing of a gap-crossed protective take-profit (#1135). */
    val takeProfitFill: TakeProfitFill = TakeProfitFill.PRINT,
    /**
     * Replay closes a quiet symbol's ended bar the way the live heartbeat does: at the first
     * [heartbeatIntervalMs] step at or after `windowEnd + candleCloseGraceMs`, in event time
     * (#1138). A tick of the same stream from the next window still closes it immediately.
     * Shares `runtime.candle_close_grace_ms` with the live daemon.
     */
    val candleCloseGraceMs: Long = com.qkt.app.LiveSession.DEFAULT_CANDLE_CLOSE_GRACE_MS,
    val heartbeatIntervalMs: Long = 1_000L,
    val slippage: SlippageSpec = SlippageSpec.ZERO,
    val slippagePoints: Int = 0,
    val rejectEvery: Int? = null,
    val partialFillFraction: BigDecimal? = null,
    val enforceStopsLevel: Boolean = false,
    /**
     * Venue position model the simulated broker books (#1071). HEDGING gives every
     * entry its own coexisting position leg with exits closing that leg (the retail-MT5
     * semantic); NETTING collapses opposite fills into one signed position. The library
     * default stays NETTING so embedded [com.qkt.backtest.Backtest] callers keep their
     * semantics; the CLI defaults to HEDGING (both production venues hedge) unless
     * `--position-mode netting` / `execution.position_mode` says otherwise.
     */
    val positionMode: com.qkt.broker.PositionAccountingMode =
        com.qkt.broker.PositionAccountingMode.NETTING,
) {
    init {
        require(latencyMs >= 0L) { "execution latencyMs must be >= 0: $latencyMs" }
        require(orderSpacingMs >= 0L) { "execution orderSpacingMs must be >= 0: $orderSpacingMs" }
        require(stopLatencyMs >= 0L) { "execution stopLatencyMs must be >= 0: $stopLatencyMs" }
        require(candleCloseGraceMs >= 0L) { "candleCloseGraceMs must be >= 0: $candleCloseGraceMs" }
        require(heartbeatIntervalMs > 0L) { "heartbeatIntervalMs must be > 0: $heartbeatIntervalMs" }
        require(slippagePoints >= 0) { "execution slippagePoints must be >= 0: $slippagePoints" }
        rejectEvery?.let { require(it > 0) { "execution rejectEvery must be > 0: $it" } }
        partialFillFraction?.let {
            require(it > BigDecimal.ZERO && it < BigDecimal.ONE) {
                "execution partialFillFraction must be in (0, 1): $it"
            }
        }
    }

    val brokerKind: BrokerKind
        get() =
            when (preset) {
                ExecutionPreset.PAPER_FAST -> BrokerKind.PAPER
                ExecutionPreset.MT5_BASIC, ExecutionPreset.MT5_REALISTIC, ExecutionPreset.STRESS -> BrokerKind.MT5_SIM
            }

    fun slippageModel(): SlippageModel =
        when (slippage) {
            SlippageSpec.ZERO -> ZeroSlippage
            SlippageSpec.INSTRUMENT -> InstrumentSlippage
            SlippageSpec.FIXED_POINTS -> FixedPointsSlippage(slippagePoints)
            SlippageSpec.UNIFORM_RANDOM -> UniformRandomSlippage(slippagePoints, seed ?: DEFAULT_SEED)
        }

    fun rejectionModel(): RejectionModel = rejectEvery?.let { RejectEveryNthOrder(it) } ?: NoBrokerRejections

    fun partialFillModel(): PartialFillModel =
        partialFillFraction?.let { com.qkt.broker.FractionalPartialFill(it) } ?: FullFill

    fun toEvidence(): ExecutionEvidence =
        ExecutionEvidence(
            preset = preset.id,
            broker = brokerKind.id,
            seed = seed,
            realistic = preset == ExecutionPreset.MT5_REALISTIC || preset == ExecutionPreset.STRESS,
            warning =
                when (preset) {
                    ExecutionPreset.PAPER_FAST ->
                        "Optimistic fills: no spread, slippage, latency, rejection, queue, or partial-fill model."
                    ExecutionPreset.MT5_BASIC ->
                        "MT5 basic models spread/slippage and venue sizing but not strict stop-distance/latency stress."
                    ExecutionPreset.MT5_REALISTIC -> null
                    ExecutionPreset.STRESS -> "Adverse stress execution; use for robustness, not base-case expectation."
                },
            fillPriceSource =
                when (preset) {
                    ExecutionPreset.PAPER_FAST -> "latest tracked price / trigger level for bars"
                    else -> "bid/ask when available, synthetic spread fallback"
                },
            latencyModel = latencyLabel(),
            stopLatencyModel = if (stopLatencyMs == 0L) "on-trigger" else "fixed:${stopLatencyMs}ms",
            takeProfitFillModel = takeProfitFill.id,
            candleCloseModel = "heartbeat:${heartbeatIntervalMs}ms grace:${candleCloseGraceMs}ms",
            slippageModel = slippageLabel(),
            rejectionModel = rejectEvery?.let { "reject-every:$it" } ?: "none",
            partialFillModel = partialFillFraction?.let { "fraction:$it" } ?: "none",
            venueRules =
                if (enforceStopsLevel) {
                    "volume step/min, price digits, bid/ask, tradeStopsLevel"
                } else if (brokerKind == BrokerKind.MT5_SIM) {
                    "volume step/min, price digits, bid/ask"
                } else {
                    "none"
                },
            commissionModel = "per-lot instruments.yaml commissionPerLot",
            financingModel = "signed swap points at configured UTC rollover; triple configured weekday",
            ocoMode = "engine-managed deterministic siblings",
        )

    companion object {
        private const val DEFAULT_SEED = 42L

        fun forBrokerKind(kind: BrokerKind): ExecutionSimulationConfig =
            when (kind) {
                BrokerKind.PAPER -> defaultsFor(ExecutionPreset.PAPER_FAST, null)
                BrokerKind.MT5_SIM -> defaultsFor(ExecutionPreset.MT5_BASIC, null)
            }

        fun defaultsFor(
            preset: ExecutionPreset,
            seed: Long?,
        ): ExecutionSimulationConfig =
            when (preset) {
                ExecutionPreset.PAPER_FAST ->
                    ExecutionSimulationConfig(preset = preset, seed = seed)
                ExecutionPreset.MT5_BASIC ->
                    ExecutionSimulationConfig(
                        preset = preset,
                        seed = seed,
                        slippage = SlippageSpec.INSTRUMENT,
                    )
                ExecutionPreset.MT5_REALISTIC ->
                    ExecutionSimulationConfig(
                        preset = preset,
                        seed = seed,
                        latencyMs = 250L,
                        slippage = SlippageSpec.INSTRUMENT,
                        enforceStopsLevel = true,
                    )
                ExecutionPreset.STRESS ->
                    ExecutionSimulationConfig(
                        preset = preset,
                        seed = seed ?: DEFAULT_SEED,
                        latencyMs = 500L,
                        slippage = SlippageSpec.UNIFORM_RANDOM,
                        slippagePoints = 20,
                        rejectEvery = 10,
                        partialFillFraction = BigDecimal("0.50"),
                        enforceStopsLevel = true,
                    )
            }
    }
}

val BrokerKind.id: String
    get() =
        when (this) {
            BrokerKind.PAPER -> "paper"
            BrokerKind.MT5_SIM -> "mt5-sim"
        }
