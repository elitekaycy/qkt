package com.qkt.execution

/** Every client order id inside this request: its own plus those of nested legs, entries and targets. */
fun OrderRequest.allIds(): Set<String> {
    val out = linkedSetOf<String>()

    fun walk(r: OrderRequest) {
        out.add(r.id)
        when (r) {
            is OrderRequest.StandaloneOCO -> {
                walk(r.leg1)
                walk(r.leg2)
            }
            is OrderRequest.OTO -> {
                walk(r.parent)
                r.children.forEach(::walk)
            }
            is OrderRequest.Bracket -> walk(r.entry)
            is OrderRequest.ScaleOut -> walk(r.basis)
            is OrderRequest.TimeExit -> walk(r.target)
            else -> Unit
        }
    }
    walk(this)
    return out
}
