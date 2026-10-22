@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.timhofmann27.opentakez.engine.device

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpGateTest {
    private val gate = YtDlpGate()

    @Test
    fun `shared sections run together`() = runTest {
        val release = CompletableDeferred<Unit>()
        var inside = 0
        val jobs = List(3) { launch { gate.shared { inside++; release.await() } } }
        runCurrent()

        assertEquals(3, inside)

        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(jobs.all { it.isCompleted })
    }

    @Test
    fun `exclusive waits for shared sections to finish`() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { gate.shared { release.await() } }
        runCurrent()
        var ran = false
        launch { gate.exclusive { ran = true } }
        runCurrent()

        assertFalse(ran)

        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(ran)
    }

    @Test
    fun `shared waits for an exclusive section`() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { gate.exclusive { release.await() } }
        runCurrent()
        var ran = false
        launch { gate.shared { ran = true } }
        runCurrent()

        assertFalse(ran)

        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(ran)
    }

    @Test
    fun `exclusive sections never overlap`() = runTest {
        val release = CompletableDeferred<Unit>()
        var running = 0
        var maxRunning = 0
        List(3) {
            launch {
                gate.exclusive {
                    maxRunning = maxOf(maxRunning, ++running)
                    release.await()
                    running--
                }
            }
        }
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, maxRunning)
    }

    @Test
    fun `a cancelled shared section frees the gate`() = runTest {
        val started = CompletableDeferred<Unit>()
        val job = launch { gate.shared { started.complete(Unit); CompletableDeferred<Unit>().await() } }
        runCurrent()
        job.cancelAndJoin()

        assertEquals("ok", async { gate.exclusive { "ok" } }.await())
    }

    @Test
    fun `a cancelled waiter does not leave the gate locked`() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { gate.exclusive { release.await() } }
        runCurrent()
        val waiter = launch { gate.shared { } }
        runCurrent()
        waiter.cancelAndJoin()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals("ok", async { gate.exclusive { "ok" } }.await())
    }
}
