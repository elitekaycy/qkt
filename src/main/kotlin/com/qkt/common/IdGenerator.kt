package com.qkt.common

interface IdGenerator {
    fun next(): String
}

class SequentialIdGenerator(private val prefix: String = "ORD") : IdGenerator {
    private var counter = 0L
    override fun next(): String = "$prefix-${counter++}"
}
