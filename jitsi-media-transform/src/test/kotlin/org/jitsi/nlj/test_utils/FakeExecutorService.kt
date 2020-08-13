package org.jitsi.nlj.test_utils

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

internal abstract class FakeExecutorService : ExecutorService {
    private var jobs = JobsTimeline()
    val clock: FakeClock = FakeClock()

    override fun execute(command: Runnable) {
        jobs.add(Job(command, clock.instant()))
    }

    override fun submit(task: Runnable): Future<*> {
        val job = Job(task, clock.instant())
        jobs.add(job)
        return EmptyCompletableFuture { job.cancelled = true }
    }

    fun runOne() {
        if (jobs.isNotEmpty()) {
            val job = jobs.removeAt(0)
            if (!job.cancelled) {
                job.run()
            } else {
                // Check for another job since this one had been cancelled
                runOne()
            }
        }
    }

    fun runAll() {
        while (jobs.isNotEmpty()) {
            runOne()
        }
    }
}

/**
 * A simple implementation of [CompletableFuture<Unit>] which allows passing
 * a handler to be invoked on cancellation.
 */
private class EmptyCompletableFuture(private val cancelHandler: () -> Unit) : CompletableFuture<Unit>() {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        cancelHandler()
        return true
    }
}
