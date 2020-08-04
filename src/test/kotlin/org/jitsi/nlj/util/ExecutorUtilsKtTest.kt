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

package org.jitsi.nlj.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import org.jitsi.utils.secs
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

internal class ExecutorUtilsKtTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val executor = Executors.newSingleThreadExecutor()

    init {
        context("shutting down an executor") {
            context("with a blocked task which can be interrupted") {
                val queue = LinkedBlockingQueue<Int>()
                executor.submit {
                    queue.take()
                }

                // Give it some time to make sure the executor has started the task
                Thread.sleep(1000)

                // This should not throw
                executor.safeShutdown(1.secs)
            }
            context("with an uninterruptable task") {
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
                    executor.safeShutdown(1.secs)
                }
            }
        }
    }
}
