package com.qkt.broker.mt5

import java.math.BigDecimal

data class MT5Tick(
    val symbol: String,
    val bid: BigDecimal,
    val ask: BigDecimal,
    val time: Long,
)

data class MT5AccountInfo(
    val balance: BigDecimal,
    val equity: BigDecimal,
    val currency: String,
    val leverage: Int,
)

data class MT5Position(
    val ticket: Long,
    val symbol: String,
    val type: Int,
    val volume: BigDecimal,
    val priceOpen: BigDecimal,
    val sl: BigDecimal,
    val tp: BigDecimal,
    val profit: BigDecimal,
    val magic: Int,
    val openTime: Long,
    val comment: String? = null,
)

data class MT5SymbolInfo(
    val ask: BigDecimal,
    val bid: BigDecimal,
    val digits: Int,
    val point: BigDecimal,
    val tradeStopsLevel: Int,
    val volumeMin: BigDecimal,
    val volumeStep: BigDecimal,
)

data class MT5OrderRequest(
    val symbol: String,
    val volume: BigDecimal,
    val type: String,
    val price: BigDecimal? = null,
    val sl: BigDecimal? = null,
    val tp: BigDecimal? = null,
    val deviation: Int = 20,
    val magic: Int,
    val comment: String,
    val expiration: Long? = null,
    val typeTime: String? = null,
)

data class MT5OrderResult(
    val retcode: Int,
    val order: Long,
    val deal: Long,
    val price: BigDecimal,
    val comment: String,
)

data class MT5OrderResponse(
    val result: MT5OrderResult,
    val errorMessage: String? = null,
)

const val MT5_TRADE_RETCODE_DONE: Int = 10009

fun isOrderSuccessful(retcode: Int): Boolean = retcode == MT5_TRADE_RETCODE_DONE
