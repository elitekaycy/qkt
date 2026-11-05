package com.qkt.connectivity.transport

/** Where each connector's [TransportJournal]s live under the state root; golden capture reads them back. */
object TransportJournalDirs {
    /** One subdirectory per MT5 profile. */
    const val MT5: String = "mt5-transport-journal"

    /** One subdirectory per gateway account label: its REST exchanges and stream events. */
    const val GATEWAY: String = "gateway-transport-journal"
}
