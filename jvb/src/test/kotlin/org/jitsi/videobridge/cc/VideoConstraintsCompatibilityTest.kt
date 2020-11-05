package org.jitsi.videobridge.cc

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.maps.shouldContainExactly
import org.jitsi.videobridge.VideoConstraints

class VideoConstraintsCompatibilityTest : ShouldSpec() {
    init {
        val vcc = VideoConstraintsCompatibility()
        context("Stage view behavior") {
            vcc.setMaxFrameHeight(720)
            vcc.setSelectedEndpoints(setOf("A"))
            vcc.computeVideoConstraints().shouldContainExactly(
                mapOf(
                    "A" to VideoConstraints(720, 360, 30.0)
                )
            )
        }
        context("Tile view behavior") {
            vcc.setMaxFrameHeight(180)
            vcc.setSelectedEndpoints(setOf("A", "B", "C", "D"))
            vcc.computeVideoConstraints().shouldContainExactly(
                mapOf(
                    "A" to VideoConstraints(180, -1, -1.0),
                    "B" to VideoConstraints(180, -1, -1.0),
                    "C" to VideoConstraints(180, -1, -1.0),
                    "D" to VideoConstraints(180, -1, -1.0)
                )
            )
        }
    }
}
