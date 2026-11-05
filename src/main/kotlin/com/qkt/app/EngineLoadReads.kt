package com.qkt.app

/** How loaded a session's engine thread is: what tells a busy engine from a stalled one before reads time out. */
interface EngineLoadReads {
    /**
     * Depth of the inbound engine queue (control events + buffered ticks). A growing
     * value flags a stalled or slow engine thread before it becomes dropped ticks.
     */
    fun inboundQueueDepth(): Int = 0

    /** The messages that held the engine thread a second or more, and the worst of them (#1609). */
    fun engineStalls(): EngineStalls = EngineStalls.NONE
}
