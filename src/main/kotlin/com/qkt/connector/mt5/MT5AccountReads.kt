package com.qkt.connector.mt5

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * Reads gateway readiness, the account, symbol specs and the open position and pending-order snapshots.
 */
internal class MT5AccountReads(
    private val http: OkHttpClient,
    private val gatewayUrl: String,
    private val apiKey: String?,
    private val json: Json,
    private val reads: MT5GatewayReads,
    private val snapshots: MT5SnapshotParser,
    private val readCache: MT5ReadCache?,
) {
    fun isReady(): Boolean =
        runCatching {
            val resp = http.newCall(mt5RequestBuilder("$gatewayUrl/health/ready", apiKey).build()).execute()
            resp.use { response ->
                if (!response.isSuccessful) return@use false
                val payload = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(payload).jsonObject
                root["status"]?.jsonPrimitive?.contentOrNull == "ready"
            }
        }.getOrDefault(false)

    /**
     * Fetch the venue's open positions. Returns `null` when the read FAILED (gateway
     * unreachable / non-2xx after retries) — callers must treat that as "unknown",
     * never as "no positions". An outage that reads as an empty account makes the
     * pollers synthesize a close for every open position (#359).
     */
    fun getPositions(magic: Int? = null): List<MT5Position>? {
        val filterLocally = magic != null && readCache != null
        val url =
            when {
                filterLocally -> "$gatewayUrl/get_positions"
                magic != null -> "$gatewayUrl/get_positions?magic=$magic"
                else -> "$gatewayUrl/get_positions"
            }
        val raw = reads.get(url) ?: return null
        val positions = snapshots.parsePositions(raw)
        return if (filterLocally) {
            positions.filter { it.magic == magic }
        } else {
            positions
        }
    }

    /**
     * Fetch the venue's working (pending) orders. Returns `null` when the read FAILED
     * (gateway unreachable, non-2xx after retries, or a gateway too old to expose
     * `/orders`) — callers must treat that as "unknown", never as "all cancelled".
     */
    fun getPendingOrders(magic: Int? = null): List<MT5PendingOrder>? {
        val filterLocally = magic != null && readCache != null
        val url =
            when {
                filterLocally -> "$gatewayUrl/orders"
                magic != null -> "$gatewayUrl/orders?magic=$magic"
                else -> "$gatewayUrl/orders"
            }
        val raw = reads.get(url) ?: return null
        val orders = snapshots.parsePendingOrders(raw)
        return if (filterLocally) {
            orders.filter { it.magic == magic }
        } else {
            orders
        }
    }

    /**
     * Fetch the venue's symbol metadata (volume step / min, digits, point, stops level).
     *
     * Returns `null` if the gateway doesn't expose the symbol or the call fails — the
     * caller decides whether to fall back to a configured override or pass-through.
     */
    fun getSymbolInfo(brokerSymbol: String): MT5SymbolInfo? {
        val raw = reads.get("$gatewayUrl/symbol_info/$brokerSymbol") ?: return null
        val obj = json.parseToJsonElement(raw).jsonObject
        return MT5SymbolInfo(
            ask = obj["ask"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            bid = obj["bid"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            digits = obj["digits"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            point = obj["point"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            tradeStopsLevel = obj["trade_stops_level"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            tradeFreezeLevel = obj["trade_freeze_level"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            volumeMin = obj["volume_min"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            volumeStep = obj["volume_step"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            volumeMax =
                obj["volume_max"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toBigDecimalOrNull()
                    ?.takeIf { it.signum() > 0 },
            // Default 1 keeps callers safe if the gateway version doesn't return the field;
            // for known instruments the venue always populates it (XAUUSD=100, EURUSD=100000).
            contractSize =
                obj["trade_contract_size"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
                    ?: BigDecimal.ONE,
        )
    }

    /**
     * Fetch the account snapshot via `GET /account`. The [MT5AccountInfo.marginMode] field
     * tells the broker whether the venue is netting (`0`) or hedging (`2`) — the routing
     * decision for closing a position. Returns `null` if the call fails.
     */
    fun getAccount(): MT5AccountInfo? {
        val raw = reads.get("$gatewayUrl/account") ?: return null
        val obj = json.parseToJsonElement(raw).jsonObject
        return MT5AccountInfo(
            balance = obj["balance"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            equity = obj["equity"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            currency = obj["currency"]?.jsonPrimitive?.contentOrNull ?: "",
            leverage = obj["leverage"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            marginMode = obj["margin_mode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: MARGIN_MODE_UNKNOWN,
            marginFree = obj["margin_free"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull(),
            marginLevel = obj["margin_level"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull(),
            margin = obj["margin"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull(),
            profit = obj["profit"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull(),
            login = obj["login"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
            server = obj["server"]?.jsonPrimitive?.contentOrNull ?: "",
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
            tradeMode = obj["trade_mode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1,
        )
    }
}
