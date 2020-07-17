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

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import io.mockk.every
import io.mockk.mockk
import java.lang.RuntimeException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class SafeExecutorTest : ShouldSpec() {
    private val executor: ExecutorService = mockk<ExecutorService>().apply {
        every { submit(any()) } answers {
            firstArg<Runnable>().run()
            CompletableFuture<Nothing>()
        }
    }

    init {
        "A SafeExecutor" {
            val se = SafeExecutor("test", executor)
            should("execute the tasks submitted") {
                val latch = CountDownLatch(3)
                repeat(3) {
                    se.submit(Runnable { latch.countDown() })
                }
                latch.await(1, TimeUnit.SECONDS) shouldBe true
                se.getStatsJson()["num_exceptions"] shouldBe 0
            }
            should("count uncaught exceptions correctly") {
                se.submit { Unit }
                se.submit { throw NullPointerException() }
                se.submit { throw RuntimeException() }
                se.submit { Unit }
                se.getStatsJson()["num_exceptions"] shouldBe 2
            }
        }
    }
}
