package com.qkt.persistence

import com.qkt.common.Side
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes `exit-hooks.json`: the bindings between an entry and the exit-hook orders
 * (stops, take-profits, closes) armed for it, with the quantity and PnL already exited.
 */
internal class ExitHooksFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    fun save(
        strategyId: String,
        bindings: List<PersistedExitHookBinding>,
    ) {
        val dto =
            ExitHooksDto(
                version = STATE_SCHEMA_VERSION,
                strategyId = strategyId,
                bindings = bindings.map(ExitHookBindingDto::fromDomain),
            )
        runCatching { json.encodeToString(ExitHooksDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, EXIT_HOOKS_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveExitHooks encode for $strategyId", e) }
    }

    fun load(strategyId: String): List<PersistedExitHookBinding> {
        val raw = writer.read(strategyId, EXIT_HOOKS_FILE) ?: return emptyList()
        val dto =
            try {
                json.decodeFromString(ExitHooksDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadExitHooks parse failed for $strategyId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadExitHooks schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        require(dto.strategyId == strategyId) {
            "loadExitHooks strategy mismatch: file=${dto.strategyId}, requested=$strategyId"
        }
        return dto.bindings.map { it.toDomain() }
    }
}

private const val EXIT_HOOKS_FILE = "exit-hooks.json"

@Serializable
private data class ExitHooksDto(
    val version: Int,
    val strategyId: String,
    val bindings: List<ExitHookBindingDto>,
)

@Serializable
private data class ExitHookBindingDto(
    val bindingId: String,
    val strategyId: String,
    val symbol: String,
    val entrySide: String,
    val definitionId: String,
    val fingerprint: String,
    val entryOrderIds: List<String>,
    val stopOrderIds: List<String>,
    val takeProfitOrderIds: List<String>,
    val closeOrderIds: List<String> = emptyList(),
    val brokerTickets: List<String> = emptyList(),
    val activeQuantity: String,
    val exitQuantity: String,
    val exitPnl: String,
) {
    fun toDomain(): PersistedExitHookBinding =
        PersistedExitHookBinding(
            bindingId = bindingId,
            strategyId = strategyId,
            symbol = symbol,
            entrySide = Side.valueOf(entrySide),
            definitionId = definitionId,
            fingerprint = fingerprint,
            entryOrderIds = entryOrderIds,
            stopOrderIds = stopOrderIds,
            takeProfitOrderIds = takeProfitOrderIds,
            closeOrderIds = closeOrderIds,
            brokerTickets = brokerTickets,
            activeQuantity = BigDecimal(activeQuantity),
            exitQuantity = BigDecimal(exitQuantity),
            exitPnl = BigDecimal(exitPnl),
        )

    companion object {
        fun fromDomain(binding: PersistedExitHookBinding): ExitHookBindingDto =
            ExitHookBindingDto(
                bindingId = binding.bindingId,
                strategyId = binding.strategyId,
                symbol = binding.symbol,
                entrySide = binding.entrySide.name,
                definitionId = binding.definitionId,
                fingerprint = binding.fingerprint,
                entryOrderIds = binding.entryOrderIds,
                stopOrderIds = binding.stopOrderIds,
                takeProfitOrderIds = binding.takeProfitOrderIds,
                closeOrderIds = binding.closeOrderIds,
                brokerTickets = binding.brokerTickets,
                activeQuantity = binding.activeQuantity.toPlainString(),
                exitQuantity = binding.exitQuantity.toPlainString(),
                exitPnl = binding.exitPnl.toPlainString(),
            )
    }
}
