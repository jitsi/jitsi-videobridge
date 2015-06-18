/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rtcp;

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;

/**
 * @author George Politis
 */
class REMBNotifier implements Transformer<RTCPCompoundPacket>
{
    private AbstractBridgeRTCPTerminationStrategy strategy;

    public REMBNotifier(AbstractBridgeRTCPTerminationStrategy strategy)
    {
        this.strategy = strategy;
    }

    @Override
    public RTCPCompoundPacket reverseTransform(RTCPCompoundPacket inPacket)
    {
        // Intercept REMBs and forward them to the VideoChannel logic
        for (RTCPPacket p : inPacket.packets)
        {
            if (p != null && p.type == RTCPFBPacket.PSFB)
            {
                RTCPFBPacket psfb = (RTCPFBPacket) p;
                if (psfb.fmt == RTCPREMBPacket.FMT)
                {
                    RTCPREMBPacket remb = (RTCPREMBPacket) psfb;
                    Conference conference = strategy.getConference();
                    if (conference != null)
                    {
                        Channel channel
                                = conference.findChannelByReceiveSSRC(remb.senderSSRC,
                                MediaType.VIDEO);
                        if (channel != null && channel instanceof VideoChannel)
                        {
                            ((VideoChannel) channel).receivedREMB(remb.getBitrate());
                        }
                    }
                }
            }
        }

        return inPacket;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        // nothing to be done here
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RTCPCompoundPacket transform(RTCPCompoundPacket inPacket)
    {
        return inPacket;
    }
}
