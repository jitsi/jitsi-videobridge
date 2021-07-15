package org.jitsi.videobridge.cc.allocation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.util.bps
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
                    allocation.layers.map { it.layer } shouldBe listOf(ld7_5, ld15, ld30, sd30, hd30)
                }
                context("With constraints") {
                    val allocation =
                        SingleSourceAllocation(endpoint, VideoConstraints(360), false, diagnosticContext, clock)

                    // We include all resolutions up to the preferred resolution, and only high-FPS (at least
                    // "preferred FPS") layers for higher resolutions.
                    allocation.preferredLayer shouldBe sd30
                    allocation.layers.map { it.layer } shouldBe listOf(ld7_5, ld15, ld30, sd30)
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
                allocation.layers.map { it.layer } shouldBe listOf(ld7_5, ld15, ld30)
            }
        }
    }
}
