package com.qkt.execution

/** The client order id whose fill opens the position this time exit closes. */
val OrderRequest.TimeExit.entryFillId: String
    get() = (target as? OrderRequest.Bracket)?.entry?.id ?: target.id
