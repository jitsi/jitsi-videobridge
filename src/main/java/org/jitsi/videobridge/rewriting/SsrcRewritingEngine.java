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
package org.jitsi.videobridge.rewriting;

import net.sf.fmj.media.rtp.util.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.function.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * Produces an RTP stream with a single SSRC with a single sequence number space
 * from all the SSRCs that it receives. It is assumed that only a single RTP
 * stream is received at any given time.
 *
 * @author George Politis
 */
public class SsrcRewritingEngine implements TransformEngine
{
    /**
     * The <tt>VideoChannel</tt> this <tt>SsrcRewritingEngine</tt> is associated
     * to.
     */
    private final VideoChannel videoChannel;

    /**
     * A <tt>Map</tt> that maps <tt>VideoChannel</tt>s to
     * <tt>SsrcRewriter</tt>s.
     */
    private Map<VideoChannel, SsrcRewriter> peerChannelEngines
            = new WeakHashMap<VideoChannel, SsrcRewriter>();

    /**
     * The <tt>Object</tt> which synchronizes the access to
     * {@link #peerChannelEngines}.
     */
    private Object peerChannelEnginesSyncRoot = new Object();

    // Start disabled.
    private boolean enabled = false;

    /**
     * The <tt>PacketTransformer</tt> that transforms <tt>RTPPacket</tt>s for
     * this <tt>TransformEngine</tt>.
     */
    private final SinglePacketTransformer rtpTransformer
            = new SinglePacketTransformer()
    {
        @Override
        public RawPacket transform(RawPacket pkt)
        {
            if (!enabled)
            {
                return pkt;
            }

            Conference conference = videoChannel.getContent().getConference();
            if (conference == null)
            {
                // what?
                return pkt;
            }

            RTPPacketParserEx parser = new RTPPacketParserEx();
            RTPPacket rtpPacket;
            try
            {
                rtpPacket = parser.parse(pkt);
            }
            catch (BadFormatException e)
            {
                return pkt;
            }

            Channel channel = conference
                    .findChannelByReceiveSSRC(rtpPacket.ssrc & 0xffffffffl,
                            MediaType.VIDEO);

            if (channel == null || !(channel instanceof VideoChannel))
            {
                // what?
                return pkt;
            }

            VideoChannel peerVideoChannel = (VideoChannel) channel;

            SsrcRewriter ssrcRewriter
                    = getOrCreatePeerChannelEngine(peerVideoChannel);

            rtpPacket = ssrcRewriter.transform(rtpPacket);

            RTPGenerator generator = new RTPGenerator();
            return generator.apply(rtpPacket);
        }

        @Override
        public RawPacket reverseTransform(RawPacket pkt)
        {
            // Pass through, nothing to do here.
            return pkt;
        }
    };

    /**
     * The <tt>PacketTransformer</tt> that transforms
     * <tt>RTCPCompoundPacket</tt>s for this <tt>TransformEngine</tt>.
     */
    private final SinglePacketTransformer rtcpTransformer
            = new SinglePacketTransformer()
    {
        @Override
        public RawPacket transform(RawPacket pkt)
        {
            if (!enabled)
            {
                return pkt;
            }

            return pkt;
        }

        @Override
        public RawPacket reverseTransform(RawPacket pkt)
        {
            if (!enabled)
            {
                return pkt;
            }

            return pkt;
        }
    };

    /**
     *
     * @param enabled
     */
    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    private SsrcRewriter getOrCreatePeerChannelEngine(VideoChannel peerVideoChannel)
    {
        synchronized (peerChannelEnginesSyncRoot)
        {
            if (!peerChannelEngines.containsKey(peerVideoChannel))
            {
                peerChannelEngines.put(peerVideoChannel,
                        new SsrcRewriter());
            }

            SsrcRewriter ssrcRewriter
                    = peerChannelEngines.get(peerVideoChannel);

            // todo this is the sole tie with simulcast. Ideally this should be
            // done outside this class.
            ssrcRewriter.setCurrentRewriteSsrc(
                peerVideoChannel.getSimulcastManager()
                    .getSimulcastLayers().first().getPrimarySSRC());

            return ssrcRewriter;
        }
    }

    /**
     *
     * @return
     */
    public boolean isEnabled()
    {
        return enabled;
    }

    /**
     * Ctor.
     *
     * @param videoChannel
     */
    public SsrcRewritingEngine(VideoChannel videoChannel)
    {
        this.videoChannel = videoChannel;
    }

    public PacketTransformer getRTPTransformer()
    {
        return rtpTransformer;
    }

    public PacketTransformer getRTCPTransformer()
    {
        return rtcpTransformer;
    }
}
