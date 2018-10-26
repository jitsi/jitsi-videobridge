package org.jitsi.rtp.util

import io.kotlintest.data.forall
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.tables.row

internal class RightToLeftByteTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    init {
        val data = 0x0F.toByte()
        "Getting bits" {
            should("work correctly using right-to-left indicies") {
                forall(
                    row(0, true),
                    row(1, true),
                    row(2, true),
                    row(3, true),
                    row(4, false),
                    row(5, false),
                    row(6, false),
                    row(7, false)
                ) { bitIndex, value ->
                    RightToLeftByteUtils.getBitAsBool(data, bitIndex) shouldBe value
                }
            }
        }
        "Setting bits" {
            should("work correctly using right-to-left indicies") {
                var changedData = data
                changedData = RightToLeftByteUtils.putBit(changedData, 0, false)
                changedData = RightToLeftByteUtils.putBit(changedData, 1, false)
                changedData = RightToLeftByteUtils.putBit(changedData, 2, false)
                changedData = RightToLeftByteUtils.putBit(changedData, 3, false)
                changedData = RightToLeftByteUtils.putBit(changedData, 4, true)
                changedData = RightToLeftByteUtils.putBit(changedData, 5, true)
                changedData = RightToLeftByteUtils.putBit(changedData, 6, true)
                changedData = RightToLeftByteUtils.putBit(changedData, 7, true)

                forall(
                    row(0, false),
                    row(1, false),
                    row(2, false),
                    row(3, false),
                    row(4, true),
                    row(5, true),
                    row(6, true),
                    row(7, true)
                ) { bitIndex, value ->
                    RightToLeftByteUtils.getBitAsBool(changedData, bitIndex) shouldBe value
                }
            }
        }
    }
}
