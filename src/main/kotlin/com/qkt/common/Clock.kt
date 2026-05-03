package com.qkt.common

interface Clock {
    fun now(): Long
}

class SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

class FixedClock(var time: Long = 0L) : Clock {
    override fun now(): Long = time
}
