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
package org.jitsi.nlj.transform.module

import org.jitsi.nlj.transform.PacketHandler
import org.jitsi.nlj.transform.StatsProducer
import org.jitsi.nlj.util.PacketPredicate
import org.jitsi.rtp.Packet
import org.jitsi.service.neomedia.RTPExtension
import org.jitsi.service.neomedia.format.MediaFormat
import kotlin.reflect.KClass

class DemuxerModule : Module("Demuxer") {
    private var transformPaths: MutableMap<PacketPredicate, PacketHandler> = mutableMapOf()

    fun addPacketPath(pp: PacketPath) {
        transformPaths[pp.predicate] = pp.path
    }

    override fun attach(nextModule: PacketHandler) {//= throw Exception()
    }

    override fun doProcessPackets(p: List<Packet>) {
        // Is this scheme always better? Or only when the list of
        // packets is above a certain size?
        transformPaths.forEach { predicate, chain ->
            val pathPackets = p.filter(predicate)
            next(chain, pathPackets)
        }
    }
    override fun onRtpExtensionAdded(extensionId: Byte, rtpExtension: RTPExtension) {
        transformPaths.forEach { (_, handler) ->
            if (handler is ModuleChain) {
                handler.modules.forEach { it.onRtpExtensionAdded(extensionId, rtpExtension) }
            }
        }
    }
    override fun onRtpExtensionRemoved(extensionId: Byte) {
        transformPaths.forEach { (_, handler) ->
            if (handler is ModuleChain) {
                handler.modules.forEach { it.onRtpExtensionRemoved(extensionId) }
            }
        }
    }

    override fun onRtpPayloadTypeAdded(payloadType: Byte, format: MediaFormat) {
        transformPaths.forEach { (_, handler) ->
            if (handler is ModuleChain) {
                handler.modules.forEach { it.onRtpPayloadTypeAdded(payloadType, format) }
            }
        }
    }

    override fun onRtpPayloadTypeRemoved(payloadType: Byte) {
        transformPaths.forEach { (_, handler) ->
            if (handler is ModuleChain) {
                handler.modules.forEach { it.onRtpPayloadTypeRemoved(payloadType) }
            }
        }
    }

    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            append(super.getStats(indent))
            transformPaths.values.forEach {
                if (it is StatsProducer) {
                    append(it.getStats(indent + 2))
                }
            }
            toString()
        }
    }
}
