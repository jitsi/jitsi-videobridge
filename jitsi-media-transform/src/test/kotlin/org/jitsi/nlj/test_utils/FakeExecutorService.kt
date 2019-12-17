package org.jitsi.nlj.test_utils

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
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
        val future: CompletableFuture<Unit> = mock(stubOnly = true)
        val job = Job(task, clock.instant())
        whenever(future.cancel(any())).thenAnswer {
            job.cancelled = true
            true
        }
        jobs.add(job)
        return future
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
