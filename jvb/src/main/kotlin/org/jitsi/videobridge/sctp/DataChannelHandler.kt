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
package org.jitsi.videobridge.sctp

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.videobridge.datachannel.DataChannelStack
import org.jitsi.videobridge.datachannel.protocol.DataChannelPacket
import org.jitsi.videobridge.util.TaskPools
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * A node which can be placed in the pipeline to cache Data channel packets
 * until the DataChannelStack is ready to handle them.
 */
class DataChannelHandler : ConsumerNode("Data channel handler") {
    private val dataChannelStackLock = Any()
    private var dataChannelStack: DataChannelStack? = null
    private val cachedDataChannelPackets = LinkedBlockingQueue<PacketInfo>()

    public override fun consume(packetInfo: PacketInfo) {
        synchronized(dataChannelStackLock) {
            when (val packet = packetInfo.packet) {
                is DataChannelPacket -> {
                    dataChannelStack?.onIncomingDataChannelPacket(
                        ByteBuffer.wrap(packet.buffer),
                        packet.sid,
                        packet.ppid
                    ) ?: run {
                        cachedDataChannelPackets.add(packetInfo)
                    }
                }
                else -> Unit
            }
        }
    }

    fun setDataChannelStack(dataChannelStack: DataChannelStack) {
        // Submit this to the pool since we wait on the lock and process any
        // cached packets here as well

        // Submit this to the pool since we wait on the lock and process any
        // cached packets here as well
        TaskPools.IO_POOL.execute {
            // We grab the lock here so that we can set the SCTP manager and
            // process any previously-cached packets as an atomic operation.
            // It also prevents another thread from coming in via
            // #doProcessPackets and processing packets at the same time in
            // another thread, which would be a problem.
            synchronized(dataChannelStackLock) {
                this.dataChannelStack = dataChannelStack
                cachedDataChannelPackets.forEach {
                    val dcp = it.packet as DataChannelPacket
                    dataChannelStack.onIncomingDataChannelPacket(
                        ByteBuffer.wrap(dcp.buffer),
                        dcp.sid,
                        dcp.ppid
                    )
                }
            }
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
