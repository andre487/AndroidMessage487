package life.andre.message487

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], manifest = Config.NONE)
class NotificationImageQueueTest {
    @Test fun `hung capture expires without blocking text callbacks and late images are discarded`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(2)
        val count = AtomicInteger()
        val late = imageFixture("late")
        NotificationImageQueue(timeoutMs = 200, capacity = 1).use { queue ->
            try {
                queue.submit("slow", {
                    started.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    listOf(late)
                }, { images -> assertTrue(images.isEmpty()); count.incrementAndGet(); delivered.countDown() }, { throw it })
                assertTrue(started.await(1, TimeUnit.SECONDS))
                queue.submit("waiting", { error("Expired queued task must not execute") },
                    { images -> assertTrue(images.isEmpty()); count.incrementAndGet(); delivered.countDown() }, { throw it })
                assertTrue(delivered.await(2, TimeUnit.SECONDS))
                assertEquals(2, count.get())
                release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (late.file.exists() && System.nanoTime() < deadline) Thread.sleep(10)
                assertFalse(late.file.exists())
                assertEquals(2, count.get())
            } finally { release.countDown() }
        }
    }
    @Test fun `coalescing and capacity fallback keep text and only latest pending image capture runs`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(4)
        val oldRuns = AtomicInteger()
        val latestRuns = AtomicInteger()
        val overflowRuns = AtomicInteger()
        val imageCallbacks = AtomicInteger()
        val photo = imageFixture("latest")
        NotificationImageQueue(timeoutMs = 3000, capacity = 1).use { queue ->
            try {
                queue.submit("active", { started.countDown(); release.await(2, TimeUnit.SECONDS); emptyList() },
                    { delivered.countDown() }, { throw it })
                assertTrue(started.await(1, TimeUnit.SECONDS))
                queue.submit("same", { oldRuns.incrementAndGet(); emptyList() }, { delivered.countDown() }, { throw it })
                queue.submit("same", { latestRuns.incrementAndGet(); listOf(photo) }, {
                    if (it.singleOrNull()?.file?.exists() == true) imageCallbacks.incrementAndGet()
                    delivered.countDown()
                }, { throw it })
                queue.submit("overflow", { overflowRuns.incrementAndGet(); emptyList() }, { delivered.countDown() }, { throw it })
                release.countDown()
                assertTrue(delivered.await(2, TimeUnit.SECONDS))
                assertEquals(0, oldRuns.get())
                assertEquals(1, latestRuns.get())
                assertEquals(0, overflowRuns.get())
                assertEquals(1, imageCallbacks.get())
            } finally { release.countDown() }
        }
    }
}
