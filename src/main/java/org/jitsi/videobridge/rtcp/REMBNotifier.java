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
import net.sf.fmj.media.rtp.util.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.videobridge.*;

import java.lang.ref.*;

/**
 * Intercepts REMBs and passes them down to the VideoChannel logic.
 *
 * @author George Politis
 */
public class REMBNotifier
    implements TransformEngine
{
    /**
     *
     */
    private final WeakReference<VideoChannel> weakVideoChannel;

    /**
     *
     */
    private final RTCPPacketParserEx parserEx = new RTCPPacketParserEx();

    /**
     * Ctor.
     *
     * @param videoChannel
     */
    public REMBNotifier(VideoChannel videoChannel)
    {
        this.weakVideoChannel = new WeakReference<VideoChannel>(videoChannel);
    }

    public PacketTransformer getRTPTransformer()
    {
        return null;
    }

    public PacketTransformer getRTCPTransformer()
    {
        return new SinglePacketTransformer()
        {
            @Override
            public RawPacket transform(RawPacket pkt)
            {
                return pkt;
            }

            @Override
            public RawPacket reverseTransform(RawPacket pkt)
            {
                if (pkt == null)
                {
                    return pkt;
                }

                RTCPCompoundPacket inPacket;
                try
                {
                    inPacket = (RTCPCompoundPacket) parserEx.parse(
                        pkt.getBuffer(), pkt.getOffset(), pkt.getLength());
                }
                catch (BadFormatException ex)
                {
                   return pkt;
                }

                if (inPacket == null)
                {
                    return pkt;
                }

                // Intercept REMBs and forward them to the VideoChannel logic
                for (RTCPPacket p : inPacket.packets)
                {
                    if (p != null && p.type == RTCPFBPacket.PSFB)
                    {
                        RTCPFBPacket psfb = (RTCPFBPacket) p;
                        if (psfb.fmt == RTCPREMBPacket.FMT)
                        {
                            RTCPREMBPacket remb = (RTCPREMBPacket) psfb;

                            WeakReference<VideoChannel> wc = weakVideoChannel;
                            VideoChannel videoChannel = wc == null
                                ? null
                                : wc.get();

                            if (videoChannel != null)
                            {
                                videoChannel.receivedREMB(remb.getBitrate());
                            }
                        }
                    }
                }

                return pkt;
            }
        };
    }
}
