package com.qkt.dsl.compile

import com.qkt.marketdata.depth.BookDepthSymbol

/** Which of a rule's streams may run it. */
internal object RuleTriggers {
    /**
     * The aliases a rule reading [referenced] runs on: each as it is, but a depth field's hidden stream
     * (`perp/bid_depth`) is replaced by the stream it reads the book of (`perp`). One book snapshot ticks the
     * bid, ask and imbalance streams one after another, so a rule one of them ran would read one snapshot's bid
     * beside the last one's ask; on its contract's closes it reads all three of the same, newest, snapshot.
     */
    fun runsOn(
        referenced: Set<String>,
        streams: Map<String, HubKey>,
    ): Set<String> =
        referenced.mapTo(LinkedHashSet()) { alias ->
            val parent = alias.substringBefore('/')
            val depth = streams[alias]?.broker == BookDepthSymbol.BROKER && '/' in alias && parent in streams
            if (depth) parent else alias
        }
}
