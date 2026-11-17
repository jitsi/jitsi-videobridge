/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.videobridge.dcsctp

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.jitsi.dcsctp4j.SendStatus
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.time.FakeClock
import java.time.Duration
import java.util.function.Supplier

class SctpSendFailureLoggerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    /** Records the lines logged at INFO level and above. */
    private class RecordingLogger : LoggerImpl("SctpSendFailureLoggerTest") {
        val lines = mutableListOf<String>()

        override fun error(msg: Any?) {
            lines.add("$msg")
        }
        override fun error(msg: Supplier<String>) {
            lines.add(msg.get())
        }
        override fun warn(msg: Any?) {
            lines.add("$msg")
        }
        override fun warn(msg: Supplier<String>) {
            lines.add(msg.get())
        }
        override fun info(msg: Any?) {
            lines.add("$msg")
        }
        override fun info(msg: Supplier<String>) {
            lines.add(msg.get())
        }
    }

    private val logger = RecordingLogger()
    private val clock = FakeClock()
    private val interval = Duration.ofSeconds(10)
    private val sendFailureLogger = SctpSendFailureLogger(logger, clock, interval)
    private val start = clock.instant()

    /** The time [ms] milliseconds after [start]. */
    private fun at(ms: Long) = start.plusMillis(ms)

    /** Fails [n] sends with [status], 60 ms apart. */
    private fun failSends(n: Int, status: SendStatus? = SendStatus.kErrorResourceExhaustion) = repeat(n) {
        sendFailureLogger.sendFailed(status)
        clock.elapse(Duration.ofMillis(60))
    }

    init {
        context("Many failures within one interval") {
            failSends(100)

            should("log one line") {
                logger.lines shouldHaveSize 1
                logger.lines[0] shouldContain "kErrorResourceExhaustion"
                logger.lines[0] shouldContain "Further errors with the same status are logged at most once every 10 s"
            }
        }
        context("Failures over more than one interval") {
            // 200 failures 60 ms apart span 12 seconds: the first is logged, then one more line once 10 s passed.
            failSends(200)

            should("log one line for each interval, with the number of failures not logged") {
                logger.lines shouldHaveSize 2
                // The failures between the first line (t=0) and the second line (t=10.02 s).
                logger.lines[1] shouldContain "between ${at(60)} and ${at(9960)}: {kErrorResourceExhaustion=166}"
            }
        }
        context("A failure exactly one interval after the last line") {
            failSends(1)
            clock.elapse(interval.minusMillis(60))
            failSends(1)

            should("be logged") {
                logger.lines shouldHaveSize 2
            }
        }
        context("A different status during a burst") {
            failSends(10)
            failSends(1, SendStatus.kErrorShuttingDown)
            failSends(1, null)
            failSends(10)

            should("be logged right away, with the failures not logged before it") {
                logger.lines shouldHaveSize 3
                logger.lines[1] shouldContain "kErrorShuttingDown"
                logger.lines[1] shouldContain "{kErrorResourceExhaustion=9}"
                logger.lines[2] shouldContain "no transport"
            }
            should("be counted by status after it was logged once") {
                failSends(1, SendStatus.kErrorShuttingDown)
                sendFailureLogger.flush()
                logger.lines shouldHaveSize 4
                logger.lines[3] shouldContain "{kErrorResourceExhaustion=10, kErrorShuttingDown=1}"
            }
        }
        context("Flush") {
            should("log nothing when there are no failures") {
                sendFailureLogger.flush()
                logger.lines.shouldBeEmpty()
            }
            should("log nothing when every failure was logged") {
                failSends(1)
                sendFailureLogger.flush()
                logger.lines shouldHaveSize 1
            }
            should("log the failures not logged yet") {
                failSends(50)
                sendFailureLogger.flush()
                logger.lines shouldHaveSize 2
                logger.lines[1] shouldContain "between ${at(60)} and ${at(2940)}: {kErrorResourceExhaustion=49}"
                // A second flush has nothing new to report.
                sendFailureLogger.flush()
                logger.lines shouldHaveSize 2
            }
        }
        context("A failure after a quiet interval") {
            failSends(1)
            clock.elapse(Duration.ofSeconds(30))
            failSends(1)

            should("be logged") {
                logger.lines shouldHaveSize 2
            }
        }
    }
}
