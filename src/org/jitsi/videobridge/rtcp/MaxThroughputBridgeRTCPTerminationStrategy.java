/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rtcp;

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtcp.termination.strategies.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * Created by gp on 18/08/14.
 */
public class MaxThroughputBridgeRTCPTerminationStrategy
    implements BridgeRTCPTerminationStrategy, RTCPPacketTransformer, RTCPReportBuilder
{
    private static final Logger logger = Logger
            .getLogger(MaxThroughputBridgeRTCPTerminationStrategy.class);

    private Conference conference;
    private RTPTranslator translator;
    private RTCPTransmitter rtcpTransmitter;

    @Override
    public void setConference(Conference conference)
    {
        this.conference = conference;
    }

    @Override
    public RTCPPacketTransformer getRTCPPacketTransformer()
    {
        return this;
    }

    @Override
    public RTCPReportBuilder getRTCPReportBuilder()
    {
        return this;
    }

    @Override
    public void setRTPTranslator(RTPTranslator translator) {
        // Nothing to do here.
        this.translator = translator;
    }

    @Override
    public void reset()
    {

    }

    @Override
    public void setRTCPTransmitter(RTCPTransmitter rtcpTransmitter)
    {
        this.rtcpTransmitter = rtcpTransmitter;
    }

    @Override
    public RTCPCompoundPacket transformRTCPPacket(RTCPCompoundPacket inPacket)
    {
        if (inPacket == null
                || inPacket.packets == null || inPacket.packets.length == 0)
        {
            return inPacket;
        }

        Vector<RTCPPacket> outPackets = new Vector<RTCPPacket>();

        for (RTCPPacket p : inPacket.packets)
        {
            switch (p.type)
            {
                case RTCPPacketType.RR:
                    // Mute RRs from the peers. We send our own.
                    break;
                case RTCPPacketType.SR:
                    // Remove feedback information from the SR and forward.
                    RTCPSRPacket sr = (RTCPSRPacket) p;
                    outPackets.add(sr);
                    sr.reports = new RTCPReportBlock[0];
                    break;
                case RTCPPacketType.PSFB:
                    RTCPFBPacket psfb = (RTCPFBPacket) p;
                    switch (psfb.fmt)
                    {
                        case RTCPPSFBFormat.REMB:
                            // Mute REMBs.
                            break;
                        default:
                            // Pass through everything else, like PLIs and NACKs
                            outPackets.add(psfb);
                            break;
                    }
                    break;
                default:
                    // Pass through everything else, like PLIs and NACKs
                    outPackets.add(p);
                    break;
            }
        }

        RTCPPacket[] outarr = new RTCPPacket[outPackets.size()];
        outPackets.copyInto(outarr);

        RTCPCompoundPacket outPacket = new RTCPCompoundPacket(outarr);

        return outPacket;
    }

    @Override
    public RTCPPacket[] makeReports()
    {
        if (this.rtcpTransmitter == null)
            throw new IllegalStateException("rtcpTransmitter is not set");

        RTPTranslator t = this.translator;
        if (t == null || !(t instanceof RTPTranslatorImpl))
            return new RTCPPacket[0];

        RTPTranslatorImpl rtpTranslatorImpl = (RTPTranslatorImpl)t;

        // Use the SSRC of the bridge (that is announced through signaling) so
        // that the endpoints won't drop the packet.
        int localSSRC = (int) rtpTranslatorImpl.getLocalSSRC(null);
        long time = System.currentTimeMillis();

        for (Endpoint endpoint : this.conference.getEndpoints())
        {
            for (Channel channel : endpoint.getChannels(MediaType.VIDEO))
            {
                VideoChannel videoChannel = (VideoChannel)channel;
                SortedSet<SimulcastLayer> layers = videoChannel
                        .getSimulcastManager().getSimulcastLayers();

                RTCPPacket[] packets = new RTCPPacket[2];
                // Adds RTCP RR.
                RTCPReportBlock receiverReport = null;

                for (SimulcastLayer layer : layers)
                {
                    int ssrc = Integer.valueOf((int) layer.getPrimarySSRC());
                    SSRCInfo info = rtcpTransmitter.cache.cache.get(ssrc);

                    if (info != null)
                    {
                        long lastseq = info.maxseq + info.cycles;
                        int jitter = (int) info.jitter;
                        long lsr = (int) ((info.lastSRntptimestamp & 0x0000ffffffff0000L) >> 16);
                        long dlsr = (int) ((time - info.lastSRreceiptTime) * 65.536000000000001D);
                        int packetslost = (int) (((lastseq - info.baseseq) + 1L) - info.received);

                        if (packetslost < 0)
                            packetslost = 0;
                        double frac = (double) (packetslost - info.prevlost)
                                / (double) (lastseq - info.prevmaxseq);
                        if (frac < 0.0D)
                            frac = 0.0D;

                        int fractionlost = (int) (frac * 256D);
                        receiverReport = new RTCPReportBlock(
                                ssrc,
                                fractionlost,
                                packetslost,
                                lastseq,
                                jitter,
                                lsr,
                                dlsr
                        );

                        info.prevmaxseq = (int) lastseq;
                        info.prevlost = packetslost;

                        if (logger.isInfoEnabled())
                        {
                            logger.info("FMJ reports " + packetslost
                                    + " lost packets (" + fractionlost + ") for sync source " + (ssrc & 0xffffffffl) + " (" + ssrc + ")");
                        }

                        // stop the loop and report only for the base stream.
                        // TODO(gp) reporting for higher quality streams seems
                        // to be a little trickier. We need to at least make
                        // sure we are actually receiving a high quality stream
                        // in order to report some feedback.
                        break;
                    }
                    else
                    {
                        // Don't send RTCP feedback information for this
                        // substream. TODO(gp) Any endpoints receiving this stream must
                        // switch to a lower quality stream.
                        if (logger.isInfoEnabled())
                        {
                            logger.info("FMJ has no information for " + (ssrc & 0xffffffffl) + " (" + ssrc + ")");
                        }
                    }
                }

                RTCPReportBlock[] reportBlocksArray = new RTCPReportBlock[] { receiverReport };

                packets[0] = new RTCPRRPacket(localSSRC, reportBlocksArray);

                // Add REMB.
                long mediaSSRC = 0l;
                int exp = MaxThroughputRTCPTerminationStrategy.MAX_EXP;
                int mantissa = MaxThroughputRTCPTerminationStrategy.MAX_MANTISSA;

                long[] dest = new long[reportBlocksArray.length];
                for (int i = 0; i < dest.length; i++)
                {
                    dest[i] = reportBlocksArray[i].getSSRC();
                }

                packets[1] = new RTCPREMBPacket(localSSRC,
                        mediaSSRC,
                        exp,
                        mantissa,
                        dest);

                // TODO(gp) for RTCP compound packets MUST contain an SDES packet.

                RTCPCompoundPacket compoundPacket = new RTCPCompoundPacket(packets);
                Payload payload = new RTCPPacketPayload(compoundPacket);
                rtpTranslatorImpl.writeControlPayload(payload, videoChannel.getStream());
            }
        }

        return null;
    }
}
