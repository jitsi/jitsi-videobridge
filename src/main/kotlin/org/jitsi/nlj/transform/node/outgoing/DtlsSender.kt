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
package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.dtls.DtlsStack
import org.jitsi.nlj.transform.node.TransformerNode

/**
 * A Node to handle the output of the sctp stack to bridge it back
 * into a pipeline.  This acts as the send half of the 'DatagramTransport'
 * (so it handles already encrypted DTLS packets).
 */
class DtlsSender(
    private val dtlsStack: DtlsStack
) : TransformerNode("DTLS sender") {
    init {
        dtlsStack.onOutgoingProtocolData =  ::next
    }

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        // Pass the packet into the stack.  When the stack is done processing it and packets are
        // ready to be sent, it will invoke the onOutgoingProtocolData handler above
        dtlsStack.sendDtlsAppData(packetInfo)

        return null
    }
}
