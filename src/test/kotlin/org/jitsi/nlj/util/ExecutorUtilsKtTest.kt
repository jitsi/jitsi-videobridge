package org.jitsi.nlj.util

import io.kotlintest.shouldThrow
import io.kotlintest.specs.ShouldSpec
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.LinkedBlockingQueue

internal class ExecutorUtilsKtTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private val executor = Executors.newSingleThreadExecutor()

    init {
        "shutting down an executor" {
            "with a blocked task which can be interrupted" {
                val queue = LinkedBlockingQueue<Int>()
                executor.submit {
                    queue.take()
                }

                // Give it some time to make sure the executor has started the task
                Thread.sleep(1000)

                // This should not throw
                executor.safeShutdown(Duration.ofSeconds(1))

            }
            "with an uninterruptable task" {
                val queue = LinkedBlockingQueue<Int>()
                executor.submit {
                    while (true) {
                        try {
                            queue.take()
                        } catch (e: Exception) {}
                    }
                }

                // Give it some time to make sure the executor has started the task
                Thread.sleep(1000)

                shouldThrow<ExecutorShutdownTimeoutException> {
                    executor.safeShutdown(Duration.ofSeconds(1))
                }
            }
        }
    }
}