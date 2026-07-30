package io.motohub.android.ipc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule these tests pin cost a rider a ride: a second live T-Box connector holds a second
 * exclusive `WifiNetworkSpecifier` request, the two fight over the association, and every
 * reconnect dies after a few seconds until the bike is power-cycled. Reproducing it needs a
 * motorcycle, a twenty-five minute session and a process death, so the invariant is verified
 * here instead.
 */
class SingleLiveInstanceTest {

    private class Tracked(val id: Int)

    @Test
    fun `replacing releases the previous instance exactly once`() = runBlocking {
        val released = mutableListOf<Int>()
        val holder = SingleLiveInstance<Tracked>(release = { released += it.id })

        val first = holder.replace { Tracked(1) }
        assertTrue("Nothing was held before, so nothing may be released", released.isEmpty())

        val second = holder.replace { Tracked(2) }
        assertEquals(listOf(1), released)
        assertSame(second, holder.peek())

        holder.replace { Tracked(3) }
        assertEquals(listOf(1, 2), released)
        assertEquals(1, first.id)
    }

    @Test
    fun `clear releases the held instance and holds nothing afterwards`() = runBlocking {
        val released = mutableListOf<Int>()
        val holder = SingleLiveInstance<Tracked>(release = { released += it.id })

        holder.replace { Tracked(1) }
        holder.clear()

        assertEquals(listOf(1), released)
        assertNull(holder.peek())

        // A second clear must not release anything again - a double release would tear down a
        // connector somebody else has since adopted.
        holder.clear()
        assertEquals(listOf(1), released)
    }

    @Test
    fun `a failing release still adopts the replacement`() = runBlocking {
        val holder = SingleLiveInstance<Tracked>(release = { error("teardown blew up for ${it.id}") })

        holder.replace { Tracked(1) }
        val second = holder.replace { Tracked(2) }

        // The whole point is that a connector that cannot be torn down cleanly does not block the
        // next connect attempt - the rider would be left unable to reconnect at all.
        assertSame(second, holder.peek())
    }

    @Test
    fun `concurrent replacements never leave two instances live`() = runBlocking {
        val liveCount = AtomicInteger(0)
        val peakLive = AtomicInteger(0)
        val releaseGate = CompletableDeferred<Unit>()
        val holder = SingleLiveInstance<Tracked>(
            // Suspends inside the release, which is what a real cancel() does (it stops the
            // transport and the network connector). If replace() did not hold its lock across
            // the release, a concurrent caller would slip a second instance in right here.
            release = {
                releaseGate.await()
                liveCount.decrementAndGet()
            }
        )

        coroutineScope {
            val creators = (1..4).map { id ->
                async {
                    holder.replace {
                        val live = liveCount.incrementAndGet()
                        peakLive.updateAndGet { peak -> maxOf(peak, live) }
                        Tracked(id)
                    }
                }
            }
            releaseGate.complete(Unit)
            creators.awaitAll()
        }

        assertEquals("Only one instance may be live at a time", 1, peakLive.get())
        assertEquals(1, liveCount.get())
    }
}
