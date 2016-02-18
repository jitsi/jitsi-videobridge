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
package org.jitsi.videobridge.simulcast;

import net.sf.fmj.media.rtp.*;
import net.sf.fmj.media.rtp.util.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtcp.termination.strategies.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.util.*;
import org.jitsi.util.function.*;
import org.jitsi.videobridge.*;

/**
 * The <tt>SimulcastEngine</tt> of a <tt>VideoChannel</tt> makes sure to only
 * forward one simulcast stream at any given point in time to the owner
 * endpoint, viewed as a receiver.
 *
 * This class also takes care of "gatewaying" the RTCP SRs that sending
 * endpoints are sending. In this context "gatewaying" means updating the octet
 * and packet count information in the SRs. Such a change is necessary because
 * of the pausing/resuming of the simulcast streams that this class performs.
 *
 * @author George Politis
 */
public class SimulcastEngine
    implements TransformEngine
{
    /**
     * The <tt>Logger</tt> used by the <tt>SimulcastEngine</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(SimulcastEngine.class);

    /**
     * The owner of this <tt>SimulcastEngine</tt>.
     */
    private final VideoChannel videoChannel;

    /**
     * If the owning endpoint (viewed as a sender) has signaled simulcast, this
     * object receives it.
     */
    private final SimulcastReceiver simulcastReceiver
        = new SimulcastReceiver(this);

    /**
     * For each <tt>SimulcastReceiver</tt> we have a <tt>SimulcastSender</tt>.
     * This object manages those <tt>SimulcastSender</tt>s.
     */
    private final SimulcastSenderManager simulcastSenderManager
        = new SimulcastSenderManager(this);

    /**
     * The RTP stats map that holds RTP statistics about all the simulcast
     * streams that this <tt>SimulcastEngine</tt> is sending. It allows to
     * modify the packets sent count and packets octet count in the RTCP SRs,
     * taking into account the pausing of the simulcast streams. The stats are
     * updated in the RTP transform direction and they are used by the
     * <tt>SenderReportGateway</tt> that is defined bellow.
     */
    private final RTPStatsMap rtpStatsMap = new RTPStatsMap();

    /**
     * The <tt>SenderReportGateway</tt> responsible for gatewaying sender
     * reports i.e. modifying their octet count and packet count to reflect the
     * pausing/resuming of the simulcast streams due to simulcast.
     *
     * RTCP termination, which needs to be activated for simulcast, nullifies
     * the effects of the SenderReportGateway because it generates SRs from
     * scratch.
     *
     * The original idea behind having the SenderReportGateway inside the
     * SimulcastEngine was so that they can be (dis-)activated independently.
     * This is not currently possible.
     */
    private final SenderReportGateway srGateway = new SenderReportGateway();

    /**
     * The RTP <tt>PacketTransformer</tt> of this <tt>SimulcastEngine</tt>.
     */
    private final PacketTransformer rtpTransformer = new MyRTPTransformer();


    /**
     * The RTCP <tt>PacketTransformer</tt> of this <tt>SimulcastEngine</tt>.
     */
    private final PacketTransformer rtcpTransformer = new MyRTCPTransformer();

    /**
     * Ctor.
     *
     * @param videoChannel The <tt>VideoChannel</tt> associated to this
     * <tt>SimulcastEngine</tt>.
     */
    public SimulcastEngine(VideoChannel videoChannel)
    {
        this.videoChannel = videoChannel;
    }

    /**
     *
     * Gets the <tt>SimulcastReceiver</tt> of this <tt>SimulcastReceiver</tt>.
     *
     * @return
     */
    public SimulcastReceiver getSimulcastReceiver()
    {
        return simulcastReceiver;
    }

    /**
     * Gets the <tt>SimulcastSenderManager</tt> of this
     * <tt>SimulcastEngine</tt>.
     *
     * @return the <tt>SimulcastSenderManager</tt> of this
     * <tt>SimulcastEngine</tt>.
     */
    public SimulcastSenderManager getSimulcastSenderManager()
    {
        return simulcastSenderManager;
    }

    /**
     * Gets the <tt>VideoChannel</tt> that owns this <tt>SimulcastEngine</tt>.
     *
     * @return the <tt>VideoChannel</tt> that owns this
     * <tt>SimulcastEngine</tt>.
     */
    public VideoChannel getVideoChannel()
    {
        return videoChannel;
    }

    /**
     * {@inheritDoc}
     */
    public PacketTransformer getRTPTransformer()
    {
        return rtpTransformer;
    }

    /**
     * {@inheritDoc}
     */
    public PacketTransformer getRTCPTransformer()
    {
        return rtcpTransformer;
    }

    /**
     * Updates octet count and packet count in sender reports.
     */
    private class SenderReportGateway
    {
        /**
         * Updates octet count and packet count in sender reports found in the
         * <tt>RTCPCompoundPacket</tt>.
         *
         * @param pkt
         * @return {@code true} if the specified {@code pkt} was modified;
         * otherwise, {@code false}
         */
        public boolean gateway(RTCPCompoundPacket pkt)
        {
            RTCPPacket[] pkts;
            boolean modified = false;

            if (pkt != null
                    && (pkts = pkt.packets) != null
                    && pkts.length != 0)
            {
                for (RTCPPacket p : pkts)
                {
                    switch (p.type)
                    {
                    case RTCPPacket.SR:
                        RTCPSRPacket sr = (RTCPSRPacket) p;
                        int ssrc = sr.ssrc;
                        RTPStatsEntry rtpStats = rtpStatsMap.get(ssrc);

                        if (rtpStats != null)
                        {
                            // Mark the packet as modified and update the octet
                            // and packet count using the information gathered
                            // by rtpStatsMap.
                            sr.octetcount = rtpStats.getBytesSent();
                            sr.packetcount = rtpStats.getPacketsSent();
                            modified = true;
                        }
                        break;
                    }
                }
            }
            return modified;
        }
    }


    /**
     * The RTP <tt>PacketTransformer</tt> of this <tt>SimulcastEngine</tt>.
     */
    class MyRTPTransformer extends SinglePacketTransformer
    {
        /**
         * Ctor.
         */
        public MyRTPTransformer()
        {
            super(RTPPacketPredicate.INSTANCE);
        }

        @Override
        public RawPacket transform(RawPacket pkt)
        {
            // Drops or accepts RTP packets depending on which simulcast
            // stream is currently being sent. This is managed by the
            // <tt>SwitchingSimulcastSender</tt>.

            boolean accept = simulcastSenderManager.accept(pkt);
            if (accept)
            {
                // Update the RTP stats map with the stuff that we accept to
                // send and return the packet as is.
                rtpStatsMap.apply(pkt);
                return pkt;
            }
            else
            {
                return null;
            }
        }

        @Override
        public RawPacket reverseTransform(RawPacket p)
        {
            // Pass the received <tt>RawPacket</tt> down to the
            // <tt>SimulcastReceiver</tt> and let it do its thing (updates the
            // <tt>SimulcastStream</tt>s that we receive).
            simulcastReceiver.accepted(p);

            return p;
        }
    }

    /**
     * The RTCP <tt>PacketTransformer</tt> of this <tt>SimulcastEngine</tt>.
     */
    class MyRTCPTransformer extends SinglePacketTransformer
    {
        /**
         * Ctor.
         */
        public MyRTCPTransformer()
        {
            super(RTCPPacketPredicate.INSTANCE);
        }

        /**
         * The RTCP packet parser that parses RTCP packets from
         * <tt>RawPacket</tt>s.
         */
        private final RTCPPacketParserEx parser = new RTCPPacketParserEx();

        /**
         * The RTCP generator that generates <tt>RTCPCompoundPacket</tt>s from
         * <tt>RawPacket</tt>s.
         */
        private final RTCPGenerator generator = new RTCPGenerator();

        @Override
        public RawPacket transform(RawPacket pkt)
        {
            // Update octets and packets sent in SRs.
            RTCPCompoundPacket inPacket;
            try
            {
                inPacket = (RTCPCompoundPacket) parser.parse(
                    pkt.getBuffer(),
                    pkt.getOffset(),
                    pkt.getLength());
            }
            catch (BadFormatException e)
            {
                logger.warn("Failed to terminate an RTCP packet. " +
                    "Dropping packet.");
                return null;
            }

            if (srGateway.gateway(inPacket))
            {
                return generator.apply(inPacket);
            }
            else
            {
                // If the RTCP packet hasn't been modified don't generate
                // anything, just send whatever we got as input.
                return pkt;
            }
        }

        @Override
        public RawPacket reverseTransform(RawPacket pkt)
        {
            // Don't touch incoming RTCP traffic.
            return pkt;
        }
    }
}
