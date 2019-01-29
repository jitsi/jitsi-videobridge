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

import org.ice4j.util.PacketQueue
import org.jitsi.nlj.PacketInfo
import java.util.concurrent.ExecutorService

class PacketInfoQueue(
    id: String,
    executor: ExecutorService,
    handler: (PacketInfo) -> Boolean
) : PacketQueue<PacketInfo>(100, false, false, id, handler, executor) {
    override fun getBuffer(packetInfo: PacketInfo): ByteArray {
        TODO()
//        return packetInfo.packet.getBuffer().array()
    }

    override fun createPacket(p0: ByteArray?, p1: Int, p2: Int, p3: Any?): PacketInfo {
        TODO()
//        return PacketInfo(UnparsedPacket(ByteBuffer.allocate(0)))
    }

    override fun getContext(p0: PacketInfo?): Any? {
        TODO()
//        return null
    }

    override fun getLength(p0: PacketInfo): Int {
        TODO()
//        return p0.packet.getBuffer().limit()
    }

    override fun getOffset(p0: PacketInfo?): Int {
        TODO()
//        return 0
    }
}