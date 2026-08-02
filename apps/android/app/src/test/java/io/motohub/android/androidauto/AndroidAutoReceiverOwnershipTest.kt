package io.motohub.android.androidauto

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Regression cover for the leak that made port 5288 unbindable for the life of the process: a
 * second instance of the same feature could claim without stopping the first, and any instance
 * could release a claim it did not hold.
 */
class AndroidAutoReceiverOwnershipTest {
    private val first = Any()
    private val second = Any()
    private val third = Any()
    private val stopped = mutableListOf<Any>()
    private val handovers = mutableListOf<String>()
    private lateinit var originalLog: (String) -> Unit

    @Before
    fun captureHandoverLog() {
        originalLog = AndroidAutoReceiverOwnership.log
        AndroidAutoReceiverOwnership.log = { handovers += it }
    }

    @After
    fun tearDown() {
        AndroidAutoReceiverOwnership.release(first)
        AndroidAutoReceiverOwnership.release(second)
        AndroidAutoReceiverOwnership.release(third)
        AndroidAutoReceiverOwnership.log = originalLog
    }

    @Test
    fun `a second instance of the same feature stops the first`() {
        AndroidAutoReceiverOwnership.claim(first, "phone-only") { stopped += first }
        AndroidAutoReceiverOwnership.claim(second, "phone-only") { stopped += second }

        assertEquals(listOf(first), stopped)
        assertEquals(
            listOf("phone-only is taking over the Android Auto receiver from phone-only."),
            handovers
        )
    }

    @Test
    fun `re-claiming with the same instance does not stop it`() {
        AndroidAutoReceiverOwnership.claim(first, "phone-only") { stopped += first }
        AndroidAutoReceiverOwnership.claim(first, "phone-only") { stopped += first }

        assertEquals(emptyList<Any>(), stopped)
    }

    @Test
    fun `releasing from a non-owner leaves the live claim intact`() {
        AndroidAutoReceiverOwnership.claim(first, "phone-only") { stopped += first }
        AndroidAutoReceiverOwnership.release(second)

        // The claim survived, so the next owner still hands over instead of hitting EADDRINUSE.
        AndroidAutoReceiverOwnership.claim(third, "real-session") { stopped += third }
        assertEquals(listOf(first), stopped)
    }

    @Test
    fun `the owner's own release ends the claim`() {
        AndroidAutoReceiverOwnership.claim(first, "phone-only") { stopped += first }
        AndroidAutoReceiverOwnership.release(first)

        AndroidAutoReceiverOwnership.claim(second, "real-session") { stopped += second }
        assertEquals(emptyList<Any>(), stopped)
    }
}
