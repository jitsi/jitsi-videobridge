package org.jitsi.rtp.rtcp.rtcpfb

import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex

internal class FirTest : ShouldSpec() {
    init {
        "Serializing an FIR FCI" {
            "created from values" {
                val fir = Fir(1L, 2)

                val buf = fir.getBuffer()
                println(buf.toHex())

            }
        }

    }
}
