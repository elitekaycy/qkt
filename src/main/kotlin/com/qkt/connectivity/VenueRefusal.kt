package com.qkt.connectivity

/**
 * A venue's refusal of an order before anything was sent: the order cannot exist at the venue. Anything
 * else thrown while placing may come after the order left, and is never reported as a refusal.
 */
class VenueRefusal(
    message: String,
) : IllegalArgumentException(message)
