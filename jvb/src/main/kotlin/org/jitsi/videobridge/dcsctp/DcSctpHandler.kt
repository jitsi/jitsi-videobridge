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
package org.jitsi.videobridge.dcsctp

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.videobridge.sctp.SctpConfig
import org.jitsi.videobridge.util.TaskPools
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * A node which can be placed in the pipeline to cache SCTP packets until
 * the DcSctpTransport is ready to handle them.
 */
class DcSctpHandler : ConsumerNode("SCTP handler") {
    private val sctpTransportLock = Any()
    private var sctpTransport: DcSctpTransport? = null
    private val numCachedSctpPackets = AtomicLong(0)
    private val cachedSctpPackets = LinkedBlockingQueue<PacketInfo>(100)

    override fun consume(packetInfo: PacketInfo) {
        synchronized(sctpTransportLock) {
            if (SctpConfig.config.enabled) {
                sctpTransport?.handleIncomingSctp(packetInfo) ?: run {
                    numCachedSctpPackets.incrementAndGet()
                    cachedSctpPackets.add(packetInfo)
                }
            }
        }
    }

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addNumber("num_cached_packets", numCachedSctpPackets.get())
    }

    fun setSctpTransport(sctpTransport: DcSctpTransport) {
        // Submit this to the pool since we wait on the lock and process any
        // cached packets here as well
        TaskPools.IO_POOL.execute {
            // We grab the lock here so that we can set the SCTP transport and
            // process any previously-cached packets as an atomic operation.
            // It also prevents another thread from coming in via
            // #doProcessPackets and processing packets at the same time in
            // another thread, which would be a problem.
            synchronized(sctpTransportLock) {
                this.sctpTransport = sctpTransport
                cachedSctpPackets.forEach { sctpTransport.handleIncomingSctp(it) }
                cachedSctpPackets.clear()
            }
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
