/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
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
