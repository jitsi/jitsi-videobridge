/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.nlj.util

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.PacketInfo
import org.jitsi.rtp.UnparsedPacket
import java.util.concurrent.Executors

class PacketInfoQueueTest : ShouldSpec() {
    private val returnedBuffers = mutableListOf<ByteArray>()
    private val originalReturnBuffer = BufferPool.returnBuffer
    private val executor = Executors.newSingleThreadExecutor()

    override suspend fun beforeSpec(spec: io.kotest.core.spec.Spec) {
        BufferPool.returnBuffer = { synchronized(returnedBuffers) { returnedBuffers.add(it) } }
    }

    override suspend fun afterSpec(spec: io.kotest.core.spec.Spec) {
        BufferPool.returnBuffer = originalReturnBuffer
        executor.shutdownNow()
    }

    private fun packet() = PacketInfo(UnparsedPacket(ByteArray(100), 0, 100))

    init {
        context("Adding to a closed queue") {
            val queue = PacketInfoQueue("test", executor, { true })
            queue.close()

            val packet = packet()
            should("reject the packet and return its buffer to the pool") {
                queue.tryAdd(packet) shouldBe false
                returnedBuffers shouldBe listOf(packet.packet.buffer)
            }
        }
    }
}
