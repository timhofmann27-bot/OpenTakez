package io.github.timhofmann27.opentakez.engine.device

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps yt-dlp updates away from everything else: replacing the yt-dlp files while a process
 * reads them breaks that process. Downloads and resolves share the gate (many at once);
 * an update is exclusive.
 */
class YtDlpGate {
    private val exclusiveLock = Mutex()
    private val readerBookkeeping = Mutex()
    private var readers = 0

    suspend fun <T> shared(block: suspend () -> T): T {
        readerBookkeeping.withLock {
            // The first reader takes the exclusive lock for the whole group.
            if (readers == 0) exclusiveLock.lock()
            readers++
        }
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                readerBookkeeping.withLock {
                    readers--
                    if (readers == 0) exclusiveLock.unlock()
                }
            }
        }
    }

    suspend fun <T> exclusive(block: suspend () -> T): T = exclusiveLock.withLock { block() }
}
