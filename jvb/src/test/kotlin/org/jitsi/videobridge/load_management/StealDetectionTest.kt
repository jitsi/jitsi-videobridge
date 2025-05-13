package org.jitsi.videobridge.load_management

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class StealDetectionTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.SingleInstance

    val file = tempfile().apply {
        writeText(
            """
            cpu0 1370589 7125 506757 214097961 48978 0 43412 63777 0 0
            cpu1 1366281 6967 490213 214122004 49125 0 31097 72697 0 0
            cpu2 1368810 6801 490900 214122156 49174 0 26168 73365 0 0
            cpu3 1381486 6142 517097 214032354 51546 0 21246 71640 0 0
            cpu 100 200 300 400 500 600 700 800 900
            """.trimIndent()
        )
    }

    private val stealDetection = StealDetection(file)

    init {
        context("first read") {
            stealDetection.update().getLoad() shouldBe 0.0
        }
        context("calculate steal percentage correctly") {
            file.writeText("cpu 200 300 400 500 600 700 800 900 1000")
            val result = stealDetection.update()

            result.getLoad() shouldBe (100 / 900.0).plusOrMinus(0.000001)
        }
        context("invalid or empty file format") {
            file.writeText("cpu abcd efgh ijkl mnop qrst uvwx yz")
            stealDetection.update().getLoad() shouldBe 0.0

            file.writeText("abcd efgh ijkl mnop qrst uvwx yz")
            stealDetection.update().getLoad() shouldBe 0.0

            file.writeText("")
            stealDetection.update().getLoad() shouldBe 0.0
        }
        context("missing steal field") {
            file.writeText("cpu 300 400 500 600 700 800 900")
            stealDetection.update().getLoad() shouldBe 0.0
        }
        context("zero total delta") {
            file.writeText("cpu 1000 2000 3000 4000 5000 6000 7000 8000 9000 10000")
            stealDetection.update()
            stealDetection.update().getLoad() shouldBe 0.0
        }
    }
}
