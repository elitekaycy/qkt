package com.qkt.instrument

/**
 * Where an option chain quote came from, and so which stored chain series it belongs to: an
 * instrument's last trade (sparse, no book) or the venue's book (bid, ask and mark for every contract).
 */
enum class QuoteSource { TRADE, BOOK }
