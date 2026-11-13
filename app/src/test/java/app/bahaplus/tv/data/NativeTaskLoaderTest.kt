package app.bahaplus.tv.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeTaskLoaderTest {
    @Test fun cancellationRetiresResultsAlreadyQueuedForTheMainThread() {
        val deliveries = ConcurrentLinkedQueue<() -> Unit>()
        val queued = CountDownLatch(1)
        var called = false
        val loader = NativeTaskLoader { deliveries.add(it); queued.countDown() }
        try {
            loader.load(OfficialHttpSession(), { 1 }) { called = true }
            assertTrue(queued.await(3, TimeUnit.SECONDS))
            loader.cancel()
            deliveries.forEach { it() }
            assertFalse(called)
        } finally { loader.destroy() }
    }

    @Test fun replacedOperationCannotOverwriteItsSuccessor() {
        val deliveries = ConcurrentLinkedQueue<() -> Unit>()
        val queued = CountDownLatch(2)
        var value = 0
        val loader = NativeTaskLoader { deliveries.add(it); queued.countDown() }
        try {
            val completed = CountDownLatch(1)
            loader.load(OfficialHttpSession(), { 1 }) { value = it.getOrThrow() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (deliveries.isEmpty() && System.nanoTime() < deadline) Thread.yield()
            assertFalse(deliveries.isEmpty())
            loader.load(OfficialHttpSession(), { 2 }) { value = it.getOrThrow(); completed.countDown() }
            assertTrue(queued.await(3, TimeUnit.SECONDS))
            deliveries.forEach { it() }
            assertTrue(completed.await(1, TimeUnit.SECONDS))
            assertEquals(2, value)
        } finally { loader.destroy() }
    }
}
