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
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

open class SafeExecutorService(protected val name: String) {
    private val logger = LoggerImpl(name)
    private val numExceptions = LongAdder()

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
}

class SafeExecutor(name: String, private val executor: ExecutorService) : SafeExecutorService(name) {
    fun submit(task: Runnable) {
        executor.submit(wrapTask(task))
    }
}

class SafeScheduledExecutor(name: String, private val executor: ScheduledExecutorService) : SafeExecutorService(name) {
    /**
     * See [ScheduledExecutorService.scheduleAtFixedRate]
     */
    fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
        executor.scheduleAtFixedRate(wrapTask(command), initialDelay, period, unit)

    fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        executor.schedule(wrapTask(command), delay, unit)
}
