package com.qkt.broker

/** How a paper broker describes itself: connector and venue `paper`, label `PAPER`, one local account. */
object PaperVenue : VenueDescribed {
    private val identity =
        VenueIdentity(connector = "paper", adapter = "paper", label = "PAPER", accountId = "paper:local")

    override fun venueIdentity(): VenueIdentity = identity
}
