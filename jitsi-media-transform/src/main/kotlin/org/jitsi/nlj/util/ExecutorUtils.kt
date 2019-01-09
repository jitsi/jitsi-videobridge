package org.jitsi.nlj.util

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class ExecutorShutdownTimeoutException : Exception("Timed out trying to shutdown executor service")
/**
 * Shutdown [executorService] normally via [ExecutorService.shutdown].  If, after [timeout] / 2, the
 * service has still not shutdown, try to stop it more forcefully via [ExecutorService.shutdownNow].  If,
 * after [timeout] / 2, the service has still not shutdown then throw [ExecutorShutdownTimeoutException].
 *
 */
@Throws(ExecutorShutdownTimeoutException::class)
fun ExecutorService.safeShutdown(timeout: Duration) {
    shutdown()
    awaitTermination(timeout.toMillis() / 2, TimeUnit.MILLISECONDS)
    if (!isShutdown) {
        shutdownNow()
        awaitTermination(timeout.toMillis() / 2, TimeUnit.MILLISECONDS)
        if (!isShutdown) {
            throw ExecutorShutdownTimeoutException()
        }
    }
}
