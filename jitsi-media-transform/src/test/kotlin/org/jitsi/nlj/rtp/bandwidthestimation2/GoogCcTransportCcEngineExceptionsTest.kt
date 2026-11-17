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
package org.jitsi.nlj.rtp.bandwidthestimation2

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.utils.concurrent.FakeScheduledExecutorService
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.secs
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Tests how [GoogCcTransportCcEngine] handles exceptions from the code it calls: its listeners and the function that
 * sends a probe.
 *
 * A real executor stores an exception from a task in the task's Future, and stops running a periodic task after the
 * task throws. The fake executor runs tasks on the test thread, and an exception from a task propagates out of
 * [FakeScheduledExecutorService.runUntil]. The tests use this to detect an exception that escapes a task.
 */
class GoogCcTransportCcEngineExceptionsTest : ShouldSpec() {
    private class Failure : RuntimeException("test")

    /** A clock that returns the time of [executor], and throws while [failing] is set. */
    private class FailingClock(private val executor: FakeScheduledExecutorService) : Clock() {
        var failing = false
        var calls = 0

        override fun instant(): Instant {
            calls++
            if (failing) {
                throw Failure()
            }
            return executor.clock.instant()
        }

        override fun getZone(): ZoneId = executor.clock.zone

        override fun withZone(zone: ZoneId?): Clock = throw UnsupportedOperationException()
    }

    /** An engine with its own executor, clock and logger. The engine sends probes by calling [sendProbing]. */
    private class Fixture(sendProbing: (DataSize, Any?) -> Int) {
        val executor = FakeScheduledExecutorService()
        val clock = FailingClock(executor)
        val logger = mockk<Logger>(relaxed = true).also {
            every { it.createChildLogger(any()) } returns it
            every { it.createChildLogger(any(), any()) } returns it
        }
        val engine = GoogCcTransportCcEngine(DiagnosticContext(), logger, executor, sendProbing, clock)

        /** Runs the tasks that are due in the next second. */
        fun runTasks() = executor.runUntil(executor.clock.instant() + 1.secs)

        /** Tells the engine about a media packet. The engine starts probing after its first media packet. */
        fun sendPacket(sequenceNumber: Long) {
            val packet = VideoRtpPacket(ByteArray(1200), 0, 1000).apply { version = 2 }
            engine.mediaPacketTagged(PacketInfo(packet), sequenceNumber)
        }

        fun numErrorsLogged(n: Int) = verify(exactly = n) { logger.error(any<Any>(), any<Throwable>()) }
    }

    init {
        context("The periodic task") {
            should("log an exception and keep running") {
                with(Fixture { _, _ -> 0 }) {
                    engine.start()

                    // The task reads the clock, so it throws while the clock is failing.
                    clock.failing = true
                    val callsBefore = clock.calls
                    shouldNotThrowAny { runTasks() }
                    // The task ran and failed many times. The log message is rate limited.
                    clock.calls shouldBeGreaterThan callsBefore + 10
                    numErrorsLogged(1)

                    // The fake executor, like a real executor, stops running a periodic task after an exception
                    // escapes it. The task still runs, so no exception escaped.
                    clock.failing = false
                    val callsAfterFailing = clock.calls
                    runTasks()
                    clock.calls shouldBeGreaterThan callsAfterFailing
                    engine.stop()
                }
            }
        }
        context("A listener that throws") {
            should("not prevent the rest of the update from being applied") {
                var laterListenerCalls = 0
                var probes = 0
                val sendProbing = { size: DataSize, _: Any? ->
                    probes++
                    size.bytes.toInt()
                }
                with(Fixture(sendProbing)) {
                    engine.addBandwidthListener(object : TransportCcEngine.BandwidthListener {
                        override fun bandwidthEstimationChanged(newValue: Bandwidth) = throw Failure()
                    })
                    engine.addBandwidthListener(object : TransportCcEngine.BandwidthListener {
                        override fun bandwidthEstimationChanged(newValue: Bandwidth) {
                            laterListenerCalls++
                        }
                    })

                    // The engine reports the first target rate to its listeners when it starts. The exception
                    // from the listener still propagates out of start().
                    shouldThrow<Failure> { engine.start() }

                    // The second listener was called.
                    laterListenerCalls shouldBeGreaterThan 0
                    // The periodic task does not run for an engine that failed to start.
                    val callsBefore = clock.calls
                    runTasks()
                    clock.calls shouldBe callsBefore
                    // The probe clusters from the update were created, so probes are sent.
                    sendPacket(0)
                    runTasks()
                    probes shouldBeGreaterThan 0
                    engine.stop()
                }
            }
        }
        context("Each task") {
            should("have its own rate limit for log messages") {
                var failing = true
                val sendProbing = { size: DataSize, _: Any? ->
                    if (failing) {
                        throw Failure()
                    }
                    size.bytes.toInt()
                }
                with(Fixture(sendProbing)) {
                    engine.start()

                    // The periodic task fails many times and uses up its rate limit.
                    clock.failing = true
                    runTasks()
                    numErrorsLogged(1)
                    clock.failing = false

                    // A failure of the probe task right after that is still logged.
                    sendPacket(0)
                    runTasks()
                    numErrorsLogged(2)

                    failing = false
                    engine.stop()
                }
            }
        }
        context("The probe task") {
            should("log an exception and resume probing later") {
                var calls = 0
                var failing = true
                val sendProbing = { size: DataSize, _: Any? ->
                    calls++
                    if (failing) {
                        throw Failure()
                    }
                    size.bytes.toInt()
                }
                with(Fixture(sendProbing)) {
                    engine.start()

                    sendPacket(0)
                    shouldNotThrowAny { runTasks() }
                    // The task ran and called the failing function.
                    calls shouldBeGreaterThan 0
                    numErrorsLogged(1)

                    failing = false
                    val callsWhileFailing = calls
                    sendPacket(1)
                    runTasks()
                    // More than one call: each probe that is sent schedules the next probe.
                    calls shouldBeGreaterThan callsWhileFailing + 1
                    engine.stop()
                }
            }
            should("resume probing after a probe that sends nothing") {
                var calls = 0
                var sending = false
                val sendProbing = { size: DataSize, _: Any? ->
                    calls++
                    if (sending) size.bytes.toInt() else 0
                }
                with(Fixture(sendProbing)) {
                    engine.start()

                    sendPacket(0)
                    shouldNotThrowAny { runTasks() }
                    calls shouldBeGreaterThan 0
                    numErrorsLogged(0)

                    sending = true
                    val callsWhileNotSending = calls
                    sendPacket(1)
                    runTasks()
                    calls shouldBeGreaterThan callsWhileNotSending + 1
                    numErrorsLogged(0)
                    engine.stop()
                }
            }
        }
    }
}
