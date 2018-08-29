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
package org.jitsi.nlj

import org.jitsi.nlj.transform_og.SinglePacketTransformer
import org.jitsi.rtp.Packet

abstract class RtpReceiver :
    PacketHandler, EventHandler {
    protected var running = true
//    protected abstract val moduleChain: ModuleChain
//    abstract fun getStatsString(): String
    abstract fun enqueuePacket(p: Packet)
    abstract fun setSrtpTransformer(srtpTransformer: SinglePacketTransformer)
    abstract fun setSrtcpTransformer(srtcpTransformer: SinglePacketTransformer)
    fun stop() {
        running = false
    }
}
