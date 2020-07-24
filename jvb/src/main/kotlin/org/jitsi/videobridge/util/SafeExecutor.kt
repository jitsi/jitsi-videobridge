/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.videobridge.util

import org.jitsi.utils.logging2.LoggerImpl
import org.json.simple.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

/**
 * Tracks the number of uncaught exceptions from tasks
 */
abstract class TaskServiceWrapper<T : ExecutorService>(
    val name: String,
    protected val delegate: T
) {
    private val logger = LoggerImpl(name)
    private val numExceptions = LongAdder()

    protected fun wrapTask(task: Runnable): Runnable {
        return Runnable {
            try {
                task.run()
            } catch (t: Throwable) {
                logger.warn("Uncaught exception: $t")
                numExceptions.increment()
            }
        }
    }

    fun getStatsJson(): JSONObject = JSONObject().apply {
        put("executor_class", delegate::class.simpleName)
        put("num_exceptions", numExceptions.sum())

        val ex = delegate as? ThreadPoolExecutor ?: return@apply
        put("pool_size", ex.poolSize)
        put("active_task_count", ex.activeCount)
        put("completed_task_count", ex.completedTaskCount)
        put("core_pool_size", ex.corePoolSize)
        put("maximum_pool_size", ex.maximumPoolSize)
        put("largest_pool_size", ex.largestPoolSize)
        put("queue_class", ex.queue.javaClass.simpleName)
        put("pending_task_count", ex.queue.size)
    }
}

/**
 * An [ExecutorService] which catches and logs any uncaught exceptions from tasks
 */
class SafeExecutor(
    name: String,
    delegate: ExecutorService
) : ExecutorService by delegate, TaskServiceWrapper<ExecutorService>(name, delegate) {
    override fun submit(task: Runnable): Future<*> = delegate.submit(wrapTask(task))
}

/**
 * A [ScheduledExecutorService] which catches and logs any uncaught exceptions from tasks
 */
class SafeScheduledExecutor(
    name: String,
    delegate: ScheduledExecutorService
) : ScheduledExecutorService by delegate, TaskServiceWrapper<ScheduledExecutorService>(name, delegate) {

    override fun scheduleAtFixedRate(
        command: Runnable,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> = delegate.scheduleAtFixedRate(wrapTask(command), initialDelay, period, unit)

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        delegate.schedule(wrapTask(command), delay, unit)
}
