package com.qkt.app

import com.qkt.execution.Trade
import java.time.Duration

interface LiveSessionHandle {
    val running: Boolean
    val droppedTicks: Long

    fun stop()

    fun awaitTermination(timeout: Duration): Boolean

    fun recentTrades(): List<Trade>
}
