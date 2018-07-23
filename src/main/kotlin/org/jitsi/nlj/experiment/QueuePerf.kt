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
package org.jitsi.nlj.experiment

import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.system.measureNanoTime

data class Data(val bytes: ByteBuffer = ByteBuffer.allocate(1300))

typealias DataType = Data

fun main(args: Array<String>) {
    val queue = LinkedBlockingQueue<DataType>()
//    val queue = ArrayBlockingQueue<Int>(2_000_000)
    val itersPerWriter = 100000
    val numWriters = 10

    for (i in 0 until numWriters) {
        thread {
            for (j in 0 until itersPerWriter) {
                queue.add(Data())
            }
            println("Thread $i done")
        }
    }

    val data = mutableListOf<DataType>()
    var bytesRead: Long = 0
    var itemsRead: Long = 0
    val time = measureNanoTime {
        val expectedItems = itersPerWriter * numWriters
        while (itemsRead < expectedItems) {
            if (false) {
                queue.drainTo(data, 20)
                data.forEach { bytesRead += it.bytes.capacity() }
                itemsRead += data.size
                data.clear()
            } else {
                val datum = queue.take()
                bytesRead += datum.bytes.capacity()
                itemsRead++
            }
        }
    }

    println("Read $itemsRead items")
    println("Removed items at a rate of ${time / itemsRead} nanos/item")
    println("Read $bytesRead bytes in $time nanos (${((bytesRead * 8 / 1000000.0)) / (time / 1000000000.0)} mbps)")
}
