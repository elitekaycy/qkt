package com.qkt.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads and writes `risk-state.json`: daily and monthly realized PnL, halts, drawdown
 * references, peak equity and entry-pacer counters that risk rules must not reset on restart.
 * An unparseable file throws the serialization error as-is, unlike the other state files.
 */
internal class RiskStateFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    fun save(
        strategyId: String,
        state: PersistedRiskState,
    ) {
        val dto =
            RiskStateDto(
                version = STATE_SCHEMA_VERSION,
                strategyId = strategyId,
                epochDay = state.epochDay,
                realizedToday = state.realizedToday.toPlainString(),
                perStrategyRealizedToday = state.perStrategyRealizedToday.mapValues { it.value.toPlainString() },
                halted = state.halted,
                haltReason = state.haltReason,
                haltScope = state.haltScope,
                haltEpochDay = state.haltEpochDay,
                strategyHalts =
                    state.strategyHalts.map {
                        StrategyHaltDto(it.strategyId, it.reason, it.scope, it.epochDay)
                    },
                globalRealizedTotal = state.globalRealizedTotal?.toPlainString(),
                dailyDrawdownEpochDay = state.dailyDrawdownEpochDay,
                globalDailyDrawdownRef = state.globalDailyDrawdownRef?.toPlainString(),
                perStrategyDailyDrawdownRefs =
                    state.perStrategyDailyDrawdownRefs.mapValues { it.value.toPlainString() },
                peakTotalEquity = state.peakTotalEquity?.toPlainString(),
                perStrategyPeakEquity = state.perStrategyPeakEquity.mapValues { it.value.toPlainString() },
                pacerEntryFillsByStrategy = state.pacerEntryFillsByStrategy,
                pacerLossStreakByStrategy = state.pacerLossStreakByStrategy,
                pacerLastLossAtByStrategy = state.pacerLastLossAtByStrategy,
                monthKey = state.monthKey,
                realizedMonth = state.realizedMonth?.toPlainString(),
                perStrategyRealizedMonth = state.perStrategyRealizedMonth.mapValues { it.value.toPlainString() },
            )
        runCatching { json.encodeToString(RiskStateDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, RISK_STATE_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveRiskState encode for $strategyId", e) }
    }

    fun load(strategyId: String): PersistedRiskState? {
        val raw = writer.read(strategyId, RISK_STATE_FILE) ?: return null
        val dto = json.decodeFromString(RiskStateDto.serializer(), raw)
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadRiskState schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return PersistedRiskState(
            epochDay = dto.epochDay,
            realizedToday = dto.realizedToday.toBigDecimal(),
            perStrategyRealizedToday = dto.perStrategyRealizedToday.mapValues { it.value.toBigDecimal() },
            halted = dto.halted,
            haltReason = dto.haltReason,
            haltScope = dto.haltScope,
            haltEpochDay = dto.haltEpochDay,
            strategyHalts =
                dto.strategyHalts.map {
                    PersistedStrategyHalt(it.strategyId, it.reason, it.scope, it.epochDay)
                },
            globalRealizedTotal = dto.globalRealizedTotal?.toBigDecimal(),
            dailyDrawdownEpochDay = dto.dailyDrawdownEpochDay,
            globalDailyDrawdownRef = dto.globalDailyDrawdownRef?.toBigDecimal(),
            perStrategyDailyDrawdownRefs =
                dto.perStrategyDailyDrawdownRefs.mapValues { it.value.toBigDecimal() },
            peakTotalEquity = dto.peakTotalEquity?.toBigDecimal(),
            perStrategyPeakEquity = dto.perStrategyPeakEquity.mapValues { it.value.toBigDecimal() },
            pacerEntryFillsByStrategy = dto.pacerEntryFillsByStrategy,
            pacerLossStreakByStrategy = dto.pacerLossStreakByStrategy,
            monthKey = dto.monthKey,
            realizedMonth = dto.realizedMonth?.toBigDecimal(),
            perStrategyRealizedMonth = dto.perStrategyRealizedMonth.mapValues { it.value.toBigDecimal() },
            pacerLastLossAtByStrategy = dto.pacerLastLossAtByStrategy,
        )
    }
}

private const val RISK_STATE_FILE = "risk-state.json"

@Serializable
private data class RiskStateDto(
    val version: Int,
    val strategyId: String,
    val epochDay: Long,
    val realizedToday: String,
    val perStrategyRealizedToday: Map<String, String>,
    val halted: Boolean,
    val haltReason: String?,
    val haltScope: String,
    val haltEpochDay: Long,
    val strategyHalts: List<StrategyHaltDto>,
    val globalRealizedTotal: String? = null,
    val dailyDrawdownEpochDay: Long? = null,
    val globalDailyDrawdownRef: String? = null,
    val perStrategyDailyDrawdownRefs: Map<String, String> = emptyMap(),
    val peakTotalEquity: String? = null,
    val perStrategyPeakEquity: Map<String, String> = emptyMap(),
    val pacerEntryFillsByStrategy: Map<String, List<Long>> = emptyMap(),
    val pacerLossStreakByStrategy: Map<String, Int> = emptyMap(),
    val pacerLastLossAtByStrategy: Map<String, Long> = emptyMap(),
    val monthKey: Long? = null,
    val realizedMonth: String? = null,
    val perStrategyRealizedMonth: Map<String, String> = emptyMap(),
)

@Serializable
private data class StrategyHaltDto(
    val strategyId: String,
    val reason: String,
    val scope: String,
    val epochDay: Long,
)
