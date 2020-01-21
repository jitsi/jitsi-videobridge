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

import java.util.concurrent.ExecutorService
import org.jitsi.nlj.PacketInfo
import org.jitsi.utils.queue.PacketQueue

/**
 * A [PacketInfo] queue. We do not want to use the copy functionality, which is why the related
 * methods always throw.
 */
class PacketInfoQueue(
    id: String,
    executor: ExecutorService,
    handler: (PacketInfo) -> Boolean,
    capacity: Int = 1024
) : PacketQueue<PacketInfo>(capacity, false, null, id, handler, executor) {
    override fun getBuffer(packetInfo: PacketInfo): ByteArray =
        throw NotImplementedError("copy=true is not supported")

    override fun createPacket(p0: ByteArray?, p1: Int, p2: Int, p3: Any?): PacketInfo =
        throw NotImplementedError("copy=true is not supported")

    override fun getContext(p0: PacketInfo?): Any? =
        throw NotImplementedError("copy=true is not supported")

    override fun getLength(p0: PacketInfo): Int =
        throw NotImplementedError("copy=true is not supported")

    override fun getOffset(p0: PacketInfo?): Int =
        throw NotImplementedError("copy=true is not supported")
}
