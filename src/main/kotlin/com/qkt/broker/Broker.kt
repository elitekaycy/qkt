package com.qkt.broker

import com.qkt.execution.Order
import com.qkt.execution.Trade

interface Broker {
    fun execute(order: Order): Trade?
}
