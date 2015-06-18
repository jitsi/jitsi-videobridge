/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.videobridge.rtcp;

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;

import javax.media.rtp.*;

/**
* @author George Politis
*/
class RTCPPacketPayload
    implements Payload
{
    private final RTCPCompoundPacket packet;

    public RTCPPacketPayload(RTCPCompoundPacket p)
    {
        this.packet = p;
    }

    @Override
    public void writeTo(OutputDataStream stream)
    {
        if (packet != null)
        {
            int len = packet.calcLength();
            packet.assemble(len, false);
            byte[] buf = packet.data;

            stream.write(buf, 0, len);
        }
    }
}
