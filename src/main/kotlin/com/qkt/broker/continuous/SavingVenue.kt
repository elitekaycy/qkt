package com.qkt.broker.continuous

import com.qkt.broker.Broker
import com.qkt.broker.SubmitAck
import com.qkt.execution.OrderRequest

/**
 * This venue with [save] run before every order it is sent and every cancel, so what a lane intends is
 * durable before the venue can act on it: after a restart an order the venue does not know was never sent.
 */
internal fun ContractVenue.savingFirst(save: () -> Unit): ContractVenue {
    val inner = broker
    val saving =
        object : Broker by inner {
            override fun submit(request: OrderRequest): SubmitAck {
                save()
                return inner.submit(request)
            }

            override fun cancel(orderId: String) {
                save()
                inner.cancel(orderId)
            }
        }
    return ContractVenue(saving, onTick)
}
