package com.qkt.broker.bybit.spot

import com.qkt.broker.bybit.FakeBybitClient
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitSpotBrokerReconcilerIntegrationTest {
    private fun newBus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun emptyOk() = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    @Test
    fun `broker init triggers an immediate reconcile (3 REST calls)`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk()
        client.responses["/v5/execution/list"] = emptyOk()
        client.responses["/v5/account/wallet-balance"] = emptyOk()

        BybitSpotBroker(client, newBus(), FixedClock(0L))

        val paths = client.posts.map { it.path }
        assertThat(paths).contains("/v5/order/realtime", "/v5/execution/list", "/v5/account/wallet-balance")
    }

    @Test
    fun `periodic ticks invoke reconcile`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk()
        client.responses["/v5/execution/list"] = emptyOk()
        client.responses["/v5/account/wallet-balance"] = emptyOk()

        val scheduler = TickScheduler()
        BybitSpotBroker(
            client,
            newBus(),
            FixedClock(0L),
            pollExecutor = scheduler.asExecutor(),
        )

        val initialPosts = client.posts.size
        scheduler.fireTick()
        scheduler.fireTick()

        assertThat(client.posts.size - initialPosts).isEqualTo(6)
    }

    @Test
    fun `close stops the periodic reconciler`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk()
        client.responses["/v5/execution/list"] = emptyOk()
        client.responses["/v5/account/wallet-balance"] = emptyOk()

        val scheduler = TickScheduler()
        val broker =
            BybitSpotBroker(
                client,
                newBus(),
                FixedClock(0L),
                pollExecutor = scheduler.asExecutor(),
            )

        broker.close()

        assertThat(scheduler.cancelled).isTrue
    }

    private class TickScheduler {
        var cancelled: Boolean = false
        private var task: Runnable? = null

        fun fireTick() {
            task?.run() ?: error("no task scheduled")
        }

        fun asExecutor(): ScheduledExecutorService =
            object : ScheduledExecutorService {
                override fun scheduleAtFixedRate(
                    command: Runnable,
                    initialDelay: Long,
                    period: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> {
                    task = command
                    return CapturingFuture { cancelled = true }
                }

                override fun shutdown() {}

                override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

                override fun isShutdown(): Boolean = false

                override fun isTerminated(): Boolean = false

                override fun awaitTermination(
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean = true

                override fun <T> submit(task: Callable<T>): Future<T> = error("not used")

                override fun <T> submit(
                    task: Runnable,
                    result: T,
                ): Future<T> = error("not used")

                override fun submit(task: Runnable): Future<*> = error("not used")

                override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> =
                    mutableListOf()

                override fun <T> invokeAll(
                    tasks: MutableCollection<out Callable<T>>,
                    timeout: Long,
                    unit: TimeUnit,
                ): MutableList<Future<T>> = mutableListOf()

                override fun <T> invokeAny(tasks: MutableCollection<out Callable<T>>): T = error("not used")

                override fun <T> invokeAny(
                    tasks: MutableCollection<out Callable<T>>,
                    timeout: Long,
                    unit: TimeUnit,
                ): T = error("not used")

                override fun execute(command: Runnable) {}

                override fun schedule(
                    command: Runnable,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = error("not used")

                override fun <V> schedule(
                    callable: Callable<V>,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<V> = error("not used")

                override fun scheduleWithFixedDelay(
                    command: Runnable,
                    initialDelay: Long,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = error("not used")
            }
    }

    private class CapturingFuture(
        private val onCancel: () -> Unit,
    ) : ScheduledFuture<Any?> {
        override fun compareTo(other: Delayed?): Int = 0

        override fun getDelay(unit: TimeUnit): Long = 0L

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            onCancel()
            return true
        }

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = false

        override fun get(): Any? = null

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Any? = null
    }
}
