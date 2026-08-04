package com.rhys.obd2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The watchdog pattern that bounds a classic Bluetooth connect.
 *
 * `BluetoothSocket.connect()` cannot be interrupted and does not honour coroutine
 * cancellation — the only thing that makes it return is closing the socket from another
 * thread. That is unusual enough to be worth pinning down away from the Bluetooth stack,
 * where it can't be tested: the socket here is a stand-in that blocks exactly the way the
 * real one does, so the shape of the fix is what's under test.
 *
 * Getting this wrong is what left the app sitting on "Opening OBDII" forever with no
 * timeout and no way back.
 */
class ConnectTimeoutTest {

    /** Blocks until something closes it, exactly like an RFCOMM socket to a dead device. */
    private class FakeSocket(private val succeedAfterMs: Long?) {
        val closed = AtomicBoolean(false)
        private val lock = Object()

        fun connect() {
            val deadline = succeedAfterMs
            synchronized(lock) {
                if (deadline == null) {
                    while (!closed.get()) lock.wait(50)
                    throw IOException("socket closed")
                }
                val until = System.currentTimeMillis() + deadline
                while (!closed.get() && System.currentTimeMillis() < until) lock.wait(10)
                if (closed.get()) throw IOException("socket closed")
            }
        }

        fun close() {
            closed.set(true)
            synchronized(lock) { lock.notifyAll() }
        }
    }

    private class SocketTimeout(afterMs: Long) : IOException("timed out after $afterMs")

    private suspend fun connectWithin(sock: FakeSocket, timeoutMs: Long) {
        coroutineScope {
            val watchdog = launch(Dispatchers.IO) {
                delay(timeoutMs)
                runCatching { sock.close() }
            }
            try {
                runInterruptible(Dispatchers.IO) { sock.connect() }
            } catch (e: IOException) {
                if (!watchdog.isActive) throw SocketTimeout(timeoutMs) else throw e
            } finally {
                watchdog.cancel()
            }
        }
    }

    @Test
    fun `a connect that never completes is abandoned rather than hanging`() = runBlocking {
        val sock = FakeSocket(succeedAfterMs = null)
        val started = System.currentTimeMillis()

        val error = runCatching { connectWithin(sock, timeoutMs = 300) }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - started

        assertTrue("should have reported a timeout, got $error", error is SocketTimeout)
        assertTrue("should give up near the deadline, took ${elapsed}ms", elapsed < 3_000)
        assertTrue("the socket must be closed to break the blocking call", sock.closed.get())
    }

    @Test
    fun `a connect that completes in time is left alone`() = runBlocking {
        val sock = FakeSocket(succeedAfterMs = 50)
        connectWithin(sock, timeoutMs = 2_000)
        // The watchdog must not fire afterwards and close a socket that is now in use.
        delay(200)
        assertTrue("a successful socket must stay open", !sock.closed.get())
    }

    @Test
    fun `each attempt gets its own budget rather than sharing one`() = runBlocking {
        // Three dead attempts must cost roughly the sum of their own timeouts and then
        // stop — not block indefinitely on the first, which is what used to happen.
        val timeouts = listOf(150L, 120L, 100L)
        var attempts = 0
        val started = System.currentTimeMillis()

        for (t in timeouts) {
            val sock = FakeSocket(succeedAfterMs = null)
            runCatching { connectWithin(sock, t) }
            attempts++
        }

        val elapsed = System.currentTimeMillis() - started
        assertEquals("every fallback must be tried", 3, attempts)
        assertTrue("should be bounded by the sum of the budgets, took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `the failed socket is closed so it cannot poison the next attempt`() = runBlocking {
        // A half-open RFCOMM socket left behind makes the following connect fail too on
        // most Android stacks, which would defeat the fallbacks entirely.
        val first = FakeSocket(succeedAfterMs = null)
        runCatching { connectWithin(first, 200) }
        assertTrue("a failed socket must not be left half-open", first.closed.get())
    }
}
