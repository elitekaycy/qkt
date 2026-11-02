package com.qkt.broker.continuous

import com.qkt.positions.PositionProvider

/**
 * What a continuous stream's lane holds on the contracts it follows ([positions], by contract symbol),
 * labelled with the [stream] (`DERIBIT:BTC_USDC@front`) it holds them for: the lane's venue broker is
 * handed these positions, so a venue report of a contract can name the stream, and the strategy's booked
 * position on it, that the contract belongs to.
 */
class StreamContractPositions(
    val stream: String,
    private val positions: PositionProvider,
) : PositionProvider by positions
