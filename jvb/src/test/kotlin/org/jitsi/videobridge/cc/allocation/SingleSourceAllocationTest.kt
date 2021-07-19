package org.jitsi.videobridge.cc.allocation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.kbps
import org.jitsi.test.time.FakeClock
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.videobridge.util.VideoType

/**
 * Test the logic for selecting the layers to be considered for an endpoint and the "preferred" layer.
 */
class SingleSourceAllocationTest : ShouldSpec() {
    private val clock = FakeClock()
    private val diagnosticContext = DiagnosticContext()

    private val ld7_5 = createLayer(tid = 0, eid = 0, height = 180, frameRate = 7.5, bitrate = bitrateLd * 0.33)
    private val ld15 = createLayer(tid = 1, eid = 0, height = 180, frameRate = 15.0, bitrate = bitrateLd * 0.66)
    private val ld30 = createLayer(tid = 2, eid = 0, height = 180, frameRate = 30.0, bitrate = bitrateLd)

    private val sd7_5 = createLayer(tid = 0, eid = 1, height = 360, frameRate = 7.5, bitrate = bitrateSd * 0.33)
    private val sd15 = createLayer(tid = 1, eid = 1, height = 360, frameRate = 15.0, bitrate = bitrateSd * 0.66)
    private val sd30 = createLayer(tid = 2, eid = 1, height = 360, frameRate = 30.0, bitrate = bitrateSd)

    private val hd7_5 = createLayer(tid = 0, eid = 2, height = 720, frameRate = 7.5, bitrate = bitrateHd * 0.33)
    private val hd15 = createLayer(tid = 1, eid = 2, height = 720, frameRate = 15.0, bitrate = bitrateHd * 0.66)
    private val hd30 = createLayer(tid = 2, eid = 2, height = 720, frameRate = 30.0, bitrate = bitrateHd)

    init {
        context("Camera") {
            context("When all layers are active") {
                val endpoint = Endpoint(
                    "id",
                    MediaSourceDesc(
                        arrayOf(
                            RtpEncodingDesc(1L, arrayOf(ld7_5, ld15, ld30)),
                            RtpEncodingDesc(1L, arrayOf(sd7_5, sd15, sd30)),
                            RtpEncodingDesc(1L, arrayOf(hd7_5, hd15, hd30))
                        )
                    ),
                    videoType = VideoType.CAMERA
                )

                context("Without constraints") {
                    val allocation =
                        SingleSourceAllocation(endpoint, VideoConstraints(720), false, diagnosticContext, clock)

                    // We include all resolutions up to the preferred resolution, and only high-FPS (at least
                    // "preferred FPS") layers for higher resolutions.
                    allocation.preferredLayer shouldBe sd30
                    allocation.oversendLayer shouldBe null
                    allocation.layers.map { it.layer } shouldBe listOf(ld7_5, ld15, ld30, sd30, hd30)
                }
                context("With constraints") {
                    val allocation =
                        SingleSourceAllocation(endpoint, VideoConstraints(360), false, diagnosticContext, clock)

                    // We include all resolutions up to the preferred resolution, and only high-FPS (at least
                    // "preferred FPS") layers for higher resolutions.
                    allocation.preferredLayer shouldBe sd30
                    allocation.oversendLayer shouldBe null
                    allocation.layers.map { it.layer } shouldBe listOf(ld7_5, ld15, ld30, sd30)
                }
                context("With constraints unmet by any layer") {
                    // Single high-res stream with 3 temporal layers.
                    val endpoint = Endpoint(
                        "id",
                        MediaSourceDesc(
                            // No simulcast.
                            arrayOf(RtpEncodingDesc(1L, arrayOf(hd7_5, hd15, hd30)))
                        )
                    )

                    context("Non-zero constraints") {
                        val allocation =
                            SingleSourceAllocation(endpoint, VideoConstraints(360), false, diagnosticContext, clock)

                        // The receiver set 360p constraints, but we only have a 720p stream.
                        allocation.preferredLayer shouldBe hd30
                        allocation.oversendLayer shouldBe null
                        allocation.layers.map { it.layer } shouldBe listOf(hd7_5, hd15, hd30)
                    }
                    context("Zero constraints") {
                        val allocation =
                            SingleSourceAllocation(endpoint, VideoConstraints(0), false, diagnosticContext, clock)

                        // The receiver set a maxHeight=0 constraint.
                        allocation.preferredLayer shouldBe null
                        allocation.oversendLayer shouldBe null
                        allocation.layers.map { it.layer } shouldBe emptyList()
                    }
                }
            }
            context("When some layers are inactive") {
                // Override layers with bitrate=0. Simulate only up to 360p/15 being active.
                val sd30 = createLayer(tid = 2, eid = 1, height = 360, frameRate = 30.0, bitrate = 0.bps)
                val hd7_5 = createLayer(tid = 0, eid = 2, height = 720, frameRate = 7.5, bitrate = 0.bps)
                val hd15 = createLayer(tid = 1, eid = 2, height = 720, frameRate = 15.0, bitrate = 0.bps)
                val hd30 = createLayer(tid = 2, eid = 2, height = 720, frameRate = 30.0, bitrate = 0.bps)
                val endpoint = Endpoint(
                    "id",
                    MediaSourceDesc(
                        arrayOf(
                            RtpEncodingDesc(1L, arrayOf(ld7_5, ld15, ld30)),
                            RtpEncodingDesc(1L, arrayOf(sd7_5, sd15, sd30)),
                            RtpEncodingDesc(1L, arrayOf(hd7_5, hd15, hd30))
                        )
                    ),
                    videoType = VideoType.CAMERA
                )

                val allocation =
                    SingleSourceAllocation(endpoint, VideoConstraints(720), false, diagnosticContext, clock)

                // We include all resolutions up to the preferred resolution, and only high-FPS (at least
                // "preferred FPS") layers for higher resolutions.
                allocation.preferredLayer shouldBe ld30
                allocation.oversendLayer shouldBe null
                allocation.layers.map { it.layer } shouldBe listOf(ld7_5, ld15, ld30)
            }
        }
        context("Screensharing") {
            context("When all layers are active") {
                val endpoint = Endpoint(
                    "id",
                    MediaSourceDesc(
                        arrayOf(
                            RtpEncodingDesc(1L, arrayOf(ld7_5, ld15, ld30)),
                            RtpEncodingDesc(1L, arrayOf(sd7_5, sd15, sd30)),
                            RtpEncodingDesc(1L, arrayOf(hd7_5, hd15, hd30))
                        )
                    ),
                    videoType = VideoType.DESKTOP
                )

                context("With no constraints") {
                    val allocation =
                        SingleSourceAllocation(endpoint, VideoConstraints(720), true, diagnosticContext, clock)

                    // For screensharing the "preferred" layer should be the highest -- always prioritized over other
                    // endpoints.
                    allocation.preferredLayer shouldBe hd30
                    allocation.oversendLayer shouldBe hd7_5
                    allocation.layers.map { it.layer } shouldBe
                        listOf(ld7_5, ld15, ld30, sd7_5, sd15, sd30, hd7_5, hd15, hd30)
                }
                context("With 360p constraints") {
                    val allocation =
                        SingleSourceAllocation(endpoint, VideoConstraints(360), true, diagnosticContext, clock)

                    allocation.preferredLayer shouldBe sd30
                    allocation.oversendLayer shouldBe sd7_5
                    allocation.layers.map { it.layer } shouldBe listOf(ld7_5, ld15, ld30, sd7_5, sd15, sd30)
                }
            }
            context("The high layers are inactive (send-side bwe restrictions)") {
                // Override layers with bitrate=0. Simulate only up to 360p/30 being active.
                val hd7_5 = createLayer(tid = 0, eid = 2, height = 720, frameRate = 7.5, bitrate = 0.bps)
                val hd15 = createLayer(tid = 1, eid = 2, height = 720, frameRate = 15.0, bitrate = 0.bps)
                val hd30 = createLayer(tid = 2, eid = 2, height = 720, frameRate = 30.0, bitrate = 0.bps)
                val endpoint = Endpoint(
                    "id",
                    MediaSourceDesc(
                        arrayOf(
                            RtpEncodingDesc(1L, arrayOf(ld7_5, ld15, ld30)),
                            RtpEncodingDesc(1L, arrayOf(sd7_5, sd15, sd30)),
                            RtpEncodingDesc(1L, arrayOf(hd7_5, hd15, hd30))
                        )
                    ),
                    videoType = VideoType.DESKTOP
                )

                val allocation =
                    SingleSourceAllocation(endpoint, VideoConstraints(720), true, diagnosticContext, clock)

                // For screensharing the "preferred" layer should be the highest -- always prioritized over other
                // endpoints.
                allocation.preferredLayer shouldBe sd30
                allocation.oversendLayer shouldBe sd7_5
                allocation.layers.map { it.layer } shouldBe listOf(ld7_5, ld15, ld30, sd7_5, sd15, sd30)
            }
            context("The low layers are inactive (simulcast signaled but not used)") {
                // Override layers with bitrate=0. Simulate simulcast being signaled but effectively disabled.
                val ld7_5 = createLayer(tid = 0, eid = 2, height = 720, frameRate = 7.5, bitrate = 0.bps)
                val ld15 = createLayer(tid = 1, eid = 2, height = 720, frameRate = 15.0, bitrate = 0.bps)
                val ld30 = createLayer(tid = 2, eid = 2, height = 720, frameRate = 30.0, bitrate = 0.bps)
                val sd7_5 = createLayer(tid = 0, eid = 1, height = 360, frameRate = 7.5, bitrate = 0.bps)
                val sd15 = createLayer(tid = 1, eid = 1, height = 360, frameRate = 15.0, bitrate = 0.bps)
                val sd30 = createLayer(tid = 2, eid = 1, height = 360, frameRate = 30.0, bitrate = 0.bps)
                val endpoint = Endpoint(
                    "id",
                    MediaSourceDesc(
                        arrayOf(
                            RtpEncodingDesc(1L, arrayOf(ld7_5, ld15, ld30)),
                            RtpEncodingDesc(1L, arrayOf(sd7_5, sd15, sd30)),
                            RtpEncodingDesc(1L, arrayOf(hd7_5, hd15, hd30))
                        )
                    ),
                    videoType = VideoType.DESKTOP
                )

                context("With no constraints") {
                    val allocation =
                        SingleSourceAllocation(endpoint, VideoConstraints(720), true, diagnosticContext, clock)

                    // For screensharing the "preferred" layer should be the highest -- always prioritized over other
                    // endpoints.
                    allocation.preferredLayer shouldBe hd30
                    allocation.oversendLayer shouldBe hd7_5
                    allocation.layers.map { it.layer } shouldBe listOf(hd7_5, hd15, hd30)
                }
                context("With 180p constraints") {
                    val allocation =
                        SingleSourceAllocation(endpoint, VideoConstraints(180), true, diagnosticContext, clock)

                    // For screensharing the "preferred" layer should be the highest -- always prioritized over other
                    // endpoints. Since no layers satisfy the resolution constraints, we consider layers from the
                    // lowest available resolution (which is high).
                    allocation.preferredLayer shouldBe hd30
                    allocation.oversendLayer shouldBe hd7_5
                    allocation.layers.map { it.layer } shouldBe listOf(hd7_5, hd15, hd30)
                }
            }
            context("VP9") {
                val l1 = createLayer(tid = 0, eid = 0, sid = 0, height = 720, frameRate = -1.0, bitrate = 150.kbps)
                val l2 = createLayer(tid = 0, eid = 0, sid = 1, height = 720, frameRate = -1.0, bitrate = 370.kbps)
                val l3 = createLayer(tid = 0, eid = 0, sid = 2, height = 720, frameRate = -1.0, bitrate = 750.kbps)

                val endpoint = Endpoint(
                    "id",
                    MediaSourceDesc(
                        arrayOf(
                            RtpEncodingDesc(1L, arrayOf(l1)),
                            RtpEncodingDesc(1L, arrayOf(l2)),
                            RtpEncodingDesc(1L, arrayOf(l3))
                        )
                    ),
                    videoType = VideoType.DESKTOP
                )

                context("With no constraints") {
                    val allocation =
                        SingleSourceAllocation(endpoint, VideoConstraints(720), true, diagnosticContext, clock)

                    allocation.preferredLayer shouldBe l3
                    allocation.oversendLayer shouldBe l1
                    allocation.layers.map { it.layer } shouldBe listOf(l1, l2, l3)
                }
                context("With 180p constraints") {
                    val allocation =
                        SingleSourceAllocation(endpoint, VideoConstraints(180), true, diagnosticContext, clock)

                    // For screensharing the "preferred" layer should be the highest -- always prioritized over other
                    // endpoints. Since no layers satisfy the resolution constraints, we consider layers from the
                    // lowest available resolution (which is high).
                    allocation.preferredLayer shouldBe l1
                    allocation.oversendLayer shouldBe l1
                    allocation.layers.map { it.layer } shouldBe listOf(l1)
                }
            }
        }
    }
}
