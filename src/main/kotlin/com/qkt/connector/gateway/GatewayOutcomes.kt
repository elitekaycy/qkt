package com.qkt.connector.gateway

/** What a gateway answered to a submit: the order it placed, or its refusal (`venue_rejected`, `kill_switch`). */
sealed interface GatewaySubmit {
    /** The gateway holds [order]: new, or the existing one for a resubmitted `client_order_id`. */
    data class Placed(
        val order: WireOrder,
    ) : GatewaySubmit

    /** The venue or the kill switch refused the order, with the gateway's [code] and [message]. */
    data class Refused(
        val code: String,
        val message: String,
    ) : GatewaySubmit
}

/** The gateway answered [status] with error [code]: a request it will not serve as sent. */
class GatewayException(
    val status: Int,
    val code: String,
    message: String,
) : RuntimeException("$code: $message")

/** The gateway could not be reached, or could not reach its venue, within the configured attempts. */
class GatewayUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
