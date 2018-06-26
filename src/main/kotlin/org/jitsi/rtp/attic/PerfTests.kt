/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.rtp.attic

import org.jitsi.rtp.BitBufferRtpHeader
import java.nio.ByteBuffer
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

val packetData = ByteBuffer.wrap(byteArrayOf(
    // Header
    0xB3.toByte(),  0xE0.toByte(),  0x10,           0x80.toByte(),
    0x00,           0x01,           0x81.toByte(),  0xCD.toByte(),
    0x00,           0x12,           0xD6.toByte(),  0x87.toByte(),
    0x00,           0x00,           0x00,           0x01,
    0x00,           0x00,           0x00,           0x02,
    0x00,           0x00,           0x00,           0x03,
    // Extensions (one byte header)
    0xBE.toByte(),  0xDE.toByte(),  0x00,           0x03,
    0x10.toByte(),  0x42,           0x21,           0x42,
    0x42,           0x00,           0x00,           0x32,
    0x42,           0x42,           0x42,           0x42,
    // Payload
    0x00,           0x00,           0x00,           0x00
))

fun runAndTime(block: () -> Unit, iterations: Int = 1_000_000): Double {
    val times = mutableListOf<Long>()
    repeat (iterations) {
        times.add(measureNanoTime { block() })
    }
    return times.average() / iterations
}

fun repeatRunAndTime(testCases: List<TestCase>, iterations: Int = 1) {
    repeat(iterations) {
        val startTestCaseIndex = it % testCases.size
        for (i in 0 until testCases.size) {
            val currentTestCaseIndex = (startTestCaseIndex + i) % testCases.size
            val currentTestCase = testCases.get(currentTestCaseIndex)
            println("Running test case ${currentTestCase.context} (index $currentTestCaseIndex)")
            val runTime = runAndTime(currentTestCase.block)
            println("${currentTestCase.context} took an average of $runTime nanosecs")
        }
    }
}

fun creationTest() {
    val initializedFromArrayAvg =
        runAndTime(block = { BitBufferRtpHeader(packetData.duplicate()) })
    println("Initialized from array took an average of $initializedFromArrayAvg nanosecs")

    val readFromArrayAvg = runAndTime(block = { BBRH(packetData.duplicate()) })
    println("Read from array took an average of $readFromArrayAvg nanosecs")
}

data class TestCase(val context: String, val block: () -> Unit)

fun cloneTest2() {
    val initializedFromArray = BitBufferRtpHeader(packetData.duplicate())
    val readFromArray = BBRH(packetData.duplicate())
    val dumb = BitBufferRtpHeader(packetData.duplicate())

    val testCases = listOf(
        TestCase("initializedFromArray clone") {
            initializedFromArray.clone()
        },
        TestCase("readFromArray clone") {
            readFromArray.clone()
        },
        TestCase("dumb clone") {
            dumb.clone()
        }
    )
    repeatRunAndTime(testCases, 3)
}

fun cloneTest() {
    val initializedFromArray = BitBufferRtpHeader(packetData.duplicate())
    val readFromArray = BBRH(packetData.duplicate())
    val dumb = BitBufferRtpHeader(packetData.duplicate())

    initializedFromArray.version = 3
    readFromArray.version = 3
    dumb.version = 3

    initializedFromArray.hasExtension = false
    readFromArray.hasExtension = false
    dumb.hasExtension = false

    initializedFromArray.timestamp = 424242
    readFromArray.timestamp = 424242
    dumb.timestamp = 424242

    val initializedAverages = mutableListOf<Double>()
    val readFromAverages = mutableListOf<Double>()
    repeat(1) {
        var initializedFromArrayReadAvg: Double = 0.0
        var readFromArrayReadAvg: Double = 0.0
        if (it % 2 == 0) {
            initializedFromArrayReadAvg = runAndTime(block = {
                initializedFromArray.clone()
            })
            readFromArrayReadAvg = runAndTime(block = {
                readFromArray.clone()
            })

            println("$it) Initialized from array read took an average of $initializedFromArrayReadAvg nanosecs")
            println("$it) Read from array read took an average of $readFromArrayReadAvg nanosecs")
        } else {
            readFromArrayReadAvg = runAndTime(block = {
                readFromArray.clone()
            })
            initializedFromArrayReadAvg = runAndTime(block = {
                initializedFromArray.clone()
            })

            println("$it) Read from array read took an average of $readFromArrayReadAvg nanosecs")
            println("$it) Initialized from array read took an average of $initializedFromArrayReadAvg nanosecs")
        }
        initializedAverages.add(initializedFromArrayReadAvg)
        readFromAverages.add(readFromArrayReadAvg)
    }
    println("Overall average for intialized: ${initializedAverages.average()}")
    println("Overall average for read: ${readFromAverages.average()}")

}

fun arrayCopyTest() {
    val byteArray = ByteArray(953)
    val byteBuf = ByteBuffer.allocate(953)

    val arrayTime = measureTimeMillis {
        repeat (1_000_000) {
            val newByteArray = ByteArray(953)
            System.arraycopy(byteArray, 0, newByteArray, 0, 953)
        }
    }

    val bufTime = measureTimeMillis {
        repeat (1_000_000) {
            val newBuf = byteBuf.clone()
        }
    }

    println("array time: $arrayTime ms")
    println("buf time: $bufTime ms")
}

fun readTest() {
    val initializedFromArray = BitBufferRtpHeader(packetData.duplicate())
    val readFromArray = BBRH(packetData.duplicate())

    val initializedAverages = mutableListOf<Double>()
    val readFromAverages = mutableListOf<Double>()
    repeat(10) {
        var initializedFromArrayReadAvg: Double = 0.0
        var readFromArrayReadAvg: Double = 0.0
        if (it % 2 == 0) {
            initializedFromArrayReadAvg = runAndTime(block = {
                initializedFromArray.toString()
            })
            readFromArrayReadAvg = runAndTime(block = {
                readFromArray.toString()
            })

            println("$it) Initialized from array read took an average of $initializedFromArrayReadAvg nanosecs")
            println("$it) Read from array read took an average of $readFromArrayReadAvg nanosecs")
        } else {
            readFromArrayReadAvg = runAndTime(block = {
                readFromArray.toString()
            })
            initializedFromArrayReadAvg = runAndTime(block = {
                initializedFromArray.toString()
            })

            println("$it) Read from array read took an average of $readFromArrayReadAvg nanosecs")
            println("$it) Initialized from array read took an average of $initializedFromArrayReadAvg nanosecs")
        }
        initializedAverages.add(initializedFromArrayReadAvg)
        readFromAverages.add(readFromArrayReadAvg)
    }
    println("Overall average for intialized: ${initializedAverages.average()}")
    println("Overall average for read: ${readFromAverages.average()}")
}

fun main(args: Array<String>) {
    //creationTest()
    //readTest()
    //cloneTest()
    //cloneTest2()
    arrayCopyTest()




}
