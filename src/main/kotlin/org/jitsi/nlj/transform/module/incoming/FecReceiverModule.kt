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
package org.jitsi.nlj.transform.module.incoming

import org.jitsi.nlj.transform.module.Module
import org.jitsi.rtp.Packet

class FecReceiverModule : Module("FEC Receiver") {
//    val handlers = mutableListOf<(Int) -> Unit>()
    override fun doProcessPackets(p: List<Packet>) {
    /*
        p.forEachAs<RtpPacket> {
            if (Random().nextInt(100) > 90) {
                if (debug) {
                    println("FEC receiver recovered packet")
                }
                handlers.forEach { it.invoke(1000)}
            }
        }
        */
        next(p)
    }
}
