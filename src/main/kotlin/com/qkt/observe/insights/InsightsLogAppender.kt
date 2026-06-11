package com.qkt.observe.insights

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Ships engine log lines to the insights collector as `log` envelopes through the
 * shared [InsightsSink] — same bounded queue, same drop-oldest, so a log flood can
 * never block trading or grow memory.
 *
 * INFO and above only; the strategy attribution comes from the `strategy` MDC key
 * the daemon already sets per session (the same key the SiftingAppender files by).
 * Lines from this package itself are skipped so a sink WARN can't echo forever.
 */
class InsightsLogAppender(
    private val sink: InsightsSink,
) : AppenderBase<ILoggingEvent>() {
    private val counter = AtomicLong(0)

    override fun append(event: ILoggingEvent) {
        if (!event.level.isGreaterOrEqual(Level.INFO)) return
        if (event.loggerName.startsWith("com.qkt.observe.insights")) return
        val strategyId = event.mdcPropertyMap["strategy"]
        sink.offer(
            InsightsEnvelope(
                id = "log-${event.timeStamp}-${counter.incrementAndGet()}",
                seq = 0,
                ts = event.timeStamp,
                strategyId = strategyId?.takeIf { it.isNotBlank() },
                type = "log",
                payload =
                    mapOf(
                        "level" to event.level.toString(),
                        "logger" to event.loggerName,
                        "message" to event.formattedMessage,
                    ),
            ),
        )
    }

    companion object {
        const val NAME = "INSIGHTS"

        /** Attaches an appender for [sink] to the root logback logger. Idempotent. */
        fun attach(sink: InsightsSink) {
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            if (root.getAppender(NAME) != null) return
            val appender = InsightsLogAppender(sink)
            appender.name = NAME
            appender.context = root.loggerContext as LoggerContext
            appender.start()
            root.addAppender(appender)
        }
    }
}
