package org.jitsi.nlj.test_utils

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A fake [ScheduledExecutorService] which gives control over when scheduled tasks are running without requiring a
 * separate thread
 */
internal abstract class FakeScheduledExecutorService : ScheduledExecutorService {
    private var jobs = JobsTimeline()
    val clock: FakeClock = FakeClock()

    override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> {
        val future: ScheduledFuture<Unit> = mock(stubOnly = true)
        val nextRunTime = clock.instant().plus(Duration.ofMillis(unit.toMillis(initialDelay)))
        val job = RecurringJob(command, nextRunTime, Duration.ofMillis(unit.toMillis(period)))
        jobs.add(job)

        whenever(future.cancel(any())).thenAnswer {
            job.cancelled = true
            true
        }

        return future
    }

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
//        println("scheduling job with a delay of $delay $unit from time ${clock.instant()}")
        val future: ScheduledFuture<Unit> = mock(stubOnly = true)
        val nextRunTime = clock.instant().plus(Duration.ofNanos(unit.toNanos(delay)))
        val job = Job(command, nextRunTime)
        jobs.add(job)

        whenever(future.cancel(any())).thenAnswer {
            job.cancelled = true
            true
        }

        return future
    }

    // Note that, when a job is cancelled, we don't remove it from the timeline, we just mark it
    // as cancelled.  We only want to reflect non-cancelled jobs in the count here, so we filter
    // them
    fun numPendingJobs(): Int = jobs.filter { !it.cancelled }.size

    /**
     * Run the next pending task and advance the clock to that time
     */
    fun runOne() {
        while (jobs.isNotEmpty() && jobs[0].cancelled) {
            jobs.removeAt(0)
        }
        if (jobs.isNotEmpty()) {
            val nextJob = jobs.removeAt(0)
            if (clock.instant() < nextJob.nextRunTime) {
                clock.setTime(nextJob.nextRunTime)
            }
            nextJob.run()
            if (nextJob is RecurringJob) {
                nextJob.updateNextRuntime()
                jobs.add(nextJob)
            }
        }
    }

    /**
     * Run pending tasks until [endTime], or until there are no pending tasks
     * in the queue, advancing the clock with each task.
     */
    fun runUntil(endTime: Instant) {
        while (jobs.isNotEmpty() && clock.instant() <= endTime) {
            runOne()
        }
    }

    /**
     * Runs all jobs that are due to run by the current time.  This may include a single job multiple times if the
     * time since the last run is longer than 2 times the job's period
     */
    fun run() {
        val now = clock.instant()
        if (jobs.isNotEmpty()) {
            val job = jobs.removeAt(0)
            if (!job.cancelled) {
                if (job.ready(now)) {
                    job.run()
                    if (job is RecurringJob) {
                        job.updateNextRuntime()
                        jobs.add(job)
                    }
                    run()
                } else {
                    // The job wasn't ready yet, re-add it to the front of the queue
                    jobs.add(0, job)
                }
            }
        }
    }
}

/**
 * Model a Job that is track in the [FakeScheduledExecutorService]
 */
internal open class Job(val command: Runnable, var nextRunTime: Instant) {
    var cancelled = false

    fun run() = command.run()

    fun ready(now: Instant): Boolean = nextRunTime <= now

    override fun toString(): String = with(StringBuffer()) {
        append("next run time: $nextRunTime")

        toString()
    }
}

internal class RecurringJob(command: Runnable, nextRunTime: Instant, val period: Duration) :
    Job(command, nextRunTime) {

    fun updateNextRuntime() {
        // TODO: this behavior is correct for scheduledAtFixedRate, but wrong for scheduleWithFixedDelay, so
        // when we want to support both we'll have to change the way this is calculated
        nextRunTime += period
    }
}

/**
 * An [ArrayList] which keeps a list of [Job]s sorted according to their next run tim
 */
internal class JobsTimeline : ArrayList<Job>() {
    override fun add(element: Job): Boolean {
        val result = super.add(element)
        sortBy(Job::nextRunTime)
        return result
    }
}
