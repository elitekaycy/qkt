package com.qkt.persistence

import com.qkt.execution.OrderRequest
import com.qkt.positions.LegBook
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * On-disk [StatePersistor]. Serializes engine state to atomic JSON files under
 * `<rootDir>/<strategyId>/`.
 *
 * Writes log and count failures; [com.qkt.app.LiveSession] turns a non-zero failure count into
 * an entry-only risk halt. Existing state that cannot be read, parsed, or validated fails startup
 * so a live session cannot silently reset durable risk or execution state.
 *
 * Each state file is owned by one `*File` collaborator (for example [PendingOrdersFile]) that
 * holds its on-disk shape and its save/load rules; this class routes each call to its owner.
 */
class FileStatePersistor(
    rootDir: Path,
) : StatePersistor {
    private val writer = StateFileWriter(rootDir)

    /** Cumulative count of save operations that hit disk. */
    val totalWrites: Long get() = writer.totalWrites.get()

    /** Cumulative count of save operations whose latency exceeded the slow-write threshold (default 100ms). */
    val slowWrites: Long get() = writer.slowWrites.get()

    /** Cumulative count of save operations that threw an IOException (disk full, permission denied, ...). */
    val failedWrites: Long get() = writer.failedWrites.get()

    /** Cumulative JSON bytes written across all save operations. */
    val totalBytesWritten: Long get() = writer.totalBytesWritten.get()

    override fun healthSnapshot(): PersistenceHealth =
        PersistenceHealth(
            enabled = true,
            totalWrites = totalWrites,
            slowWrites = slowWrites,
            failedWrites = failedWrites,
            consecutiveFailures = writer.consecutiveFailures.get(),
            failureEpisodes = writer.failureEpisodes.get(),
        )

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    private val sequences = SequencesFile(writer, json)
    private val exitHooks = ExitHooksFile(writer, json)
    private val tradeHistory = TradeHistoryFile(writer, json)
    private val pnl = PnlFile(writer, json)
    private val riskState = RiskStateFile(writer, json)
    private val legBooks = LegBookFile(writer, json)
    private val excursions = ExcursionFile(writer, json)
    private val bracketPairs = BracketPairsFile(writer, json)
    private val pendingOrders = PendingOrdersFile(writer, json)
    private val pendingStacks = PendingStacksFile(writer, json)
    private val ocoLegs = OcoLegsFile(writer, json)
    private val trailingStops = TrailingStopsFile(writer, json)
    private val timedExits = TimedExitsFile(writer, json)

    override fun saveSequences(
        strategyId: String,
        states: Map<String, PersistedSequenceState>,
    ) = sequences.save(strategyId, states)

    override fun saveExitHooks(
        strategyId: String,
        bindings: List<PersistedExitHookBinding>,
    ) = exitHooks.save(strategyId, bindings)

    override fun loadExitHooks(strategyId: String): List<PersistedExitHookBinding> = exitHooks.load(strategyId)

    override fun loadSequences(strategyId: String): Map<String, PersistedSequenceState> = sequences.load(strategyId)

    override fun saveTradeHistory(
        strategyId: String,
        state: PersistedTradeHistory,
    ) = tradeHistory.save(strategyId, state)

    override fun loadTradeHistory(strategyId: String): PersistedTradeHistory? = tradeHistory.load(strategyId)

    override fun savePnl(
        strategyId: String,
        state: PersistedPnl,
    ) = pnl.save(strategyId, state)

    override fun loadPnl(strategyId: String): PersistedPnl? = pnl.load(strategyId)

    override fun saveRiskState(
        strategyId: String,
        state: PersistedRiskState,
    ) = riskState.save(strategyId, state)

    override fun loadRiskState(strategyId: String): PersistedRiskState? = riskState.load(strategyId)

    override fun saveLegBook(
        strategyId: String,
        symbol: String,
        legBook: LegBook,
    ) = legBooks.save(strategyId, symbol, legBook)

    override fun saveExcursion(
        strategyId: String,
        symbol: String,
        excursion: PersistedExcursion,
    ) = excursions.save(strategyId, symbol, excursion)

    override fun loadExcursion(
        strategyId: String,
        symbol: String,
    ): PersistedExcursion? = excursions.load(strategyId, symbol)

    override fun loadLegBook(
        strategyId: String,
        symbol: String,
    ): PersistedLegBook? = legBooks.load(strategyId, symbol)

    override fun saveBracketPairs(
        strategyId: String,
        pairs: List<BracketPair>,
    ) = bracketPairs.save(strategyId, pairs)

    override fun loadBracketPairs(strategyId: String): List<BracketPair> = bracketPairs.load(strategyId)

    override fun savePendingOrders(
        strategyId: String,
        orders: Map<String, OrderRequest>,
    ) = pendingOrders.save(strategyId, orders)

    override fun savePendingOrdersSync(
        strategyId: String,
        orders: Map<String, OrderRequest>,
    ) {
        val failuresBefore = failedWrites
        savePendingOrders(strategyId, orders)
        check(failedWrites == failuresBefore) {
            "durable pending-order intent write failed for $strategyId"
        }
    }

    override fun loadPendingOrders(strategyId: String): Map<String, OrderRequest> = pendingOrders.load(strategyId)

    override fun savePendingStacks(
        strategyId: String,
        perPrimary: Map<String, PersistedTierState>,
    ) = pendingStacks.save(strategyId, perPrimary)

    override fun loadPendingStacks(strategyId: String): Map<String, PersistedTierState> = pendingStacks.load(strategyId)

    override fun saveOcoLegs(
        strategyId: String,
        legs: List<PersistedOcoLeg>,
    ) = ocoLegs.save(strategyId, legs)

    override fun loadOcoLegs(strategyId: String): List<PersistedOcoLeg> = ocoLegs.load(strategyId)

    override fun saveTrailingStops(
        strategyId: String,
        stops: List<PersistedTrailingStop>,
    ) = trailingStops.save(strategyId, stops)

    override fun loadTrailingStops(strategyId: String): List<PersistedTrailingStop> = trailingStops.load(strategyId)

    override fun saveTimedExits(
        strategyId: String,
        exits: List<PersistedTimeExit>,
    ) = timedExits.save(strategyId, exits)

    override fun loadTimedExits(strategyId: String): List<PersistedTimeExit> = timedExits.load(strategyId)

    override fun clearStrategy(strategyId: String) {
        writer.deleteStrategy(strategyId)
    }
}
