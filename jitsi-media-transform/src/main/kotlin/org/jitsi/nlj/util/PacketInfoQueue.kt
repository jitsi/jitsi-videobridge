/*
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.nlj.PacketInfo
import org.jitsi.utils.queue.PacketQueue
import java.util.concurrent.ExecutorService

/**
 * A [PacketQueue] of [PacketInfo]s, returning buffers when released. Unlike the base class, this queue takes
 * ownership of every packet passed to [tryAdd]/[add], even when the queue is closed: a rejected packet's buffer is
 * returned to the pool, so callers must not use it afterwards.
 */
class PacketInfoQueue(
    id: String,
    executor: ExecutorService,
    handler: (PacketInfo) -> Boolean,
    capacity: Int = 1024
) : PacketQueue<PacketInfo>(capacity, null, id, handler, executor) {
    override fun releasePacket(pkt: PacketInfo) {
        BufferPool.returnBuffer(pkt.packet.buffer)
    }

    /**
     * @return whether the packet was accepted. If it was not (the queue is closed), its buffer has been returned to
     * the pool.
     */
    override fun tryAdd(pkt: PacketInfo): Boolean = super.tryAdd(pkt).also { accepted ->
        if (!accepted) {
            releasePacket(pkt)
        }
    }
}
