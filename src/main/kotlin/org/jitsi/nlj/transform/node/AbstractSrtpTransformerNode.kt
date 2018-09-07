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
package org.jitsi.nlj.transform.node

import org.jitsi.impl.neomedia.transform.SinglePacketTransformer
import org.jitsi.nlj.PacketInfo
import org.jitsi.rtp.Packet

abstract class AbstractSrtpTransformerNode(name: String) : Node(name) {
    /**
     * The [SinglePacketTransformer] instance to use for srtp transform/reverseTransforms.
     * Note that this is private on purpose: subclasses should use the transformer given to
     * them in [doTransform] (which will never be null, whereas this may be null).
     */
    private var transformer: SinglePacketTransformer? = null

    fun setTransformer(t: SinglePacketTransformer?) {
        transformer = t
    }
    /**
     * We'll cache all packets that come through before [transformer]
     * gets set so that we don't lose any packets at the beginning
     * (likely a keyframe)
     */
    private var cachedPackets = mutableListOf<PacketInfo>()

    /**
     * The function which subclasses should implement to do the actual srtp/srtcp encryption/decryption
     */
    abstract fun doTransform(pkts: List<PacketInfo>, transformer: SinglePacketTransformer): List<PacketInfo>

    override fun doProcessPackets(p: List<PacketInfo>) {
        transformer?.let {
            val outPackets = mutableListOf<PacketInfo>()
            outPackets.addAll(doTransform(cachedPackets, it))
            cachedPackets.clear()
            outPackets.addAll(doTransform(p, it))
            next(outPackets)
        } ?: run {
            cachedPackets.addAll(p)
        }
    }

}
