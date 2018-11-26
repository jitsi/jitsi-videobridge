package org.jitsi.nlj.util

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

internal class RtpUtilsKtTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    init {
        "rolledOverTo" {
            should("return true when a rollover has taken place") {
                65535 rolledOverTo 1 shouldBe true
                65000 rolledOverTo 200 shouldBe true
            }
            should("return false when a rollover has not occurred") {
                0 rolledOverTo 65535 shouldBe false
            }
        }
    }
}