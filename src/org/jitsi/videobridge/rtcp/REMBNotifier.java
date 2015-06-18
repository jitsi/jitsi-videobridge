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
