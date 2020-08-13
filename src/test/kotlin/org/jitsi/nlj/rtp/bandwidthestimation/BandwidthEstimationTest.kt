package org.jitsi.nlj.rtp.bandwidthestimation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.doubles.shouldBeBetween
import io.mockk.spyk
import org.jitsi.nlj.test_utils.FakeScheduledExecutorService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.NEVER
import org.jitsi.nlj.util.atRate
import org.jitsi.nlj.util.bits
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.mbps
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.utils.logging2.LoggerImpl

/** A simulated packet, for bandwidth estimation testing. */
data class SimulatedPacket(
    val sendTime: Instant,
    val packetSize: DataSize,
    val ssrc: Long
)

abstract class FixedRateSender(
    private val executor: ScheduledExecutorService,
    val clock: Clock,
    var receiver: (SimulatedPacket) -> Unit
) {
    private var nextPacket: ScheduledFuture<*>? = null
    private var lastSendTime: Instant = NEVER

    var running = false

    var rate: Bandwidth by Delegates.observable(0.bps) {
        _, _, _ ->
        nextPacket?.cancel(false)
        schedulePacket(false)
    }

    abstract fun nextPacketSize(): DataSize

    fun schedulePacket(justSent: Boolean) {
        if (!running || rate <= 0.bps || nextPacketSize().bits == 0L) {
            nextPacket = null
        } else {
            val packetDelayTime = when (lastSendTime) {
                NEVER -> Duration.ZERO
                else -> {
                    var delayTime = (nextPacketSize() atRate rate)
                    if (!justSent) {
                        delayTime -= Duration.between(lastSendTime, clock.instant())
                    }
                    delayTime
                }
            }

            nextPacket = executor.schedule(::doSendPacket, packetDelayTime.toNanos(), TimeUnit.NANOSECONDS)
        }
    }

    private fun doSendPacket() {
        val now = clock.instant()
        val sendNext = sendPacket(now)

        if (sendNext) {
            lastSendTime = now
            schedulePacket(true)
        }
    }

    abstract fun sendPacket(now: Instant): Boolean

    fun start() {
        running = true
        doSendPacket()
    }

    fun stop() {
        running = false
        nextPacket?.cancel(false)
    }
}

class PacketGenerator(
    executor: ScheduledExecutorService,
    clock: Clock,
    receiver: (SimulatedPacket) -> Unit,
    private var packetSize: DataSize = 1250.bytes,
    private val ssrc: Long = 0xcafebabe
) : FixedRateSender(executor, clock, receiver) {
    override fun nextPacketSize() = packetSize

    override fun sendPacket(now: Instant): Boolean {
        val packet = SimulatedPacket(now, packetSize, ssrc)
        receiver(packet)
        return true
    }
}

class PacketBottleneck(
    executor: ScheduledExecutorService,
    clock: Clock,
    private val ctx: DiagnosticContext,
    receiver: (SimulatedPacket) -> Unit
) : FixedRateSender(executor, clock, receiver) {
    private val timeSeriesLogger: TimeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(this.javaClass)

    private val queue = ArrayDeque<SimulatedPacket>()

    fun enqueue(packet: SimulatedPacket) {
        if (!running) {
            return
        }
        assert(packet.sendTime <= clock.instant())
        val queueWasEmpty = queue.isEmpty()
        queue.addFirst(packet)
        if (queueWasEmpty) {
            schedulePacket(false)
        }
    }

    override fun sendPacket(now: Instant): Boolean {
        if (queue.isEmpty()) {
            return false
        }

        val packet = queue.removeLast()
        receiver(packet)

        if (timeSeriesLogger.isTraceEnabled) {
            val delay = Duration.between(packet.sendTime, now)
            timeSeriesLogger.trace(ctx.makeTimeSeriesPoint("queue", now)
                .addField("depth", queue.size)
                .addField("delay", delay.toNanos() / 1e6)
            )
        }

        return true
    }

    override fun nextPacketSize(): DataSize = queue.peek()?.packetSize ?: 0.bits
}

class PacketDelayer(
    private val executor: ScheduledExecutorService,
    private val delay: Duration,
    val receiver: (SimulatedPacket) -> Unit
) {
    fun enqueue(packet: SimulatedPacket) {
        executor.schedule({ receiver(packet) }, delay.toNanos(), TimeUnit.NANOSECONDS)
    }
}

class PacketReceiver(
    private val clock: Clock,
    private val estimator: BandwidthEstimator,
    private val ctx: DiagnosticContext,
    val rateReceiver: (Bandwidth) -> Unit
) {
    private val timeSeriesLogger: TimeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(this.javaClass)
    private var seq = 0

    fun receivePacket(packet: SimulatedPacket) {
        val now = clock.instant()
        assert(packet.sendTime <= now)
        /* All delay is send -> receive in this simulation, so one-way delay is rtt. */
        estimator.onRttUpdate(now, Duration.between(packet.sendTime, now))
        estimator.processPacketArrival(now, packet.sendTime, now, seq, packet.packetSize)
        seq++
        val bw = estimator.getCurrentBw(now)
        if (timeSeriesLogger.isTraceEnabled) {
            timeSeriesLogger.trace(ctx.makeTimeSeriesPoint("bw", now).addField("bw", bw.bps))
        }
        rateReceiver(bw)
    }
}

class BandwidthEstimationTest : ShouldSpec() {
    private val scheduler: FakeScheduledExecutorService = spyk()
    private val clock: Clock = scheduler.clock

    private val ctx = DiagnosticContext(clock)
    init {
        /* Emulate the fields that jitsi-videobridge puts in its DiagnosticContexts. */
        ctx["conf_name"] = "BandwidthEstimationTest"
        ctx["conf_creation_time_ms"] = clock.instant().toEpochMilli()
        ctx["endpoint_id"] = "00000000"
    }

    private val logger = LoggerImpl(BandwidthEstimationTest::class.qualifiedName)
    private val estimator: BandwidthEstimator = GoogleCcEstimator(ctx, logger)

    private val rtt: Duration = Duration.ofMillis(200)
    private val bottleneckRate = 4.mbps

    private val generator: PacketGenerator = PacketGenerator(scheduler, clock, { bottleneck.enqueue(it) })
    private val bottleneck: PacketBottleneck = PacketBottleneck(scheduler, clock, ctx) { delayer.enqueue(it) }
    private val delayer: PacketDelayer = PacketDelayer(scheduler, rtt) { receiver.receivePacket(it) }
    private val receiver: PacketReceiver = PacketReceiver(clock, estimator, ctx) { generator.rate = it }

    init {
        context("Running bandwidth estimation test") {
            should("work correctly") {
                bottleneck.rate = bottleneckRate
                generator.rate = estimator.getCurrentBw(clock.instant())

                bottleneck.start()
                generator.start()

                scheduler.runUntil(clock.instant().plus(120, ChronoUnit.SECONDS))

                generator.stop()
                bottleneck.stop()

                val finalBw = estimator.getCurrentBw(clock.instant())
                finalBw.bps.shouldBeBetween((bottleneckRate / 1.2).bps, (bottleneckRate * 1.2).bps, 0.0)
            }
        }
    }
}
