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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

abstract class SafeExecutorService(protected val name: String) {
    private val logger = LoggerImpl(name)
    private val numExceptions = LongAdder()

    abstract val innerExecutor: ExecutorService

    protected fun wrapTask(task: Runnable): Runnable {
        return Runnable {
            try {
                task.run()
            } catch (t: Throwable) {
                logger.warn("Uncaught exception ($name): $t")
                numExceptions.increment()
            }
        }
    }

    fun getStatsJson(): JSONObject = JSONObject().apply {
        put("executor_class", innerExecutor::class.simpleName)
        put("num_exceptions", numExceptions.sum())

        val ex = innerExecutor as? ThreadPoolExecutor ?: return@apply
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

class SafeExecutor(
    name: String,
    override val innerExecutor: ExecutorService
) : SafeExecutorService(name) {
    fun submit(task: Runnable) {
        innerExecutor.submit(wrapTask(task))
    }
}

class SafeScheduledExecutor(
    name: String,
    override val innerExecutor: ScheduledExecutorService
) : SafeExecutorService(name) {
    /**
     * See [ScheduledExecutorService.scheduleAtFixedRate]
     */
    fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
        innerExecutor.scheduleAtFixedRate(wrapTask(command), initialDelay, period, unit)

    fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        innerExecutor.schedule(wrapTask(command), delay, unit)
}
