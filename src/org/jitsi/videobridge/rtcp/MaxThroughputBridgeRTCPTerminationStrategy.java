/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rtcp;

import java.util.*;

import net.sf.fmj.media.rtp.*;

import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtcp.termination.strategies.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

/**
 *
 * @author George Politis
 * @author Lyubomir Marinov
 */
public class MaxThroughputBridgeRTCPTerminationStrategy
    extends AbstractRTCPReportBuilder
    implements BridgeRTCPTerminationStrategy,
               RTCPPacketTransformer
{
    private static final Logger logger
        = Logger.getLogger(MaxThroughputBridgeRTCPTerminationStrategy.class);

    private Conference conference;
    private RTPTranslator rtpTranslator;

    private RTCPSDES createRTCPSDES(RTCPTransmitter rtcpTransmitter, int ssrc)
    {
        SSRCInfo ssrcInfo = rtcpTransmitter.cache.cache.get(ssrc);
        RTCPSDES rtcpSDES = null;

        if (ssrcInfo != null)
        {
            String cname = ssrcInfo.getCNAME();

            if (cname != null)
            {
                rtcpSDES = new RTCPSDES();

                rtcpSDES.ssrc = ssrc;
                rtcpSDES.items
                    = new RTCPSDESItem[]
                            {
                                new RTCPSDESItem(RTCPSDESItem.CNAME, cname)
                            };
            }
        }
        return rtcpSDES;
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

    private RTCPReportBlock[] makeReceiverReports(
            VideoChannel videoChannel,
            RTCPTransmitter rtcpTransmitter,
            long time)
    {
        SortedSet<SimulcastLayer> layers
            = videoChannel.getSimulcastManager().getSimulcastLayers();
        List<RTCPReportBlock> receiverReports
            = new ArrayList<RTCPReportBlock>(layers.size());

        for (SimulcastLayer layer : layers)
        {
            int ssrc = (int) layer.getPrimarySSRC();
            SSRCInfo info = rtcpTransmitter.cache.cache.get(ssrc);

            if (info != null)
            {
                int baseseq = info.baseseq;
                int cycles = info.cycles;
                double jitter = info.jitter;
                long lastSRntptimestamp = info.lastSRntptimestamp;
                long lastSRreceiptTime = info.lastSRreceiptTime;
                int maxseq = info.maxseq;
                int received = info.received;

                long lastseq = maxseq + cycles;
                long lsr
                    = (int) ((lastSRntptimestamp & 0x0000ffffffff0000L) >> 16);
                long dlsr
                    = (int) ((time - lastSRreceiptTime) * 65.536000000000001D);
                int packetslost = (int) (lastseq - baseseq + 1L - received);

                if (packetslost < 0)
                    packetslost = 0;

                double frac
                    = (packetslost - info.prevlost)
                        / (double) (lastseq - info.prevmaxseq);

                if (frac < 0.0D)
                    frac = 0.0D;

                int fractionlost = (int) (frac * 256D);

                receiverReports.add(
                        new RTCPReportBlock(
                                ssrc,
                                fractionlost,
                                packetslost,
                                lastseq,
                                (int) jitter,
                                lsr,
                                dlsr));

                info.prevmaxseq = (int) lastseq;
                info.prevlost = packetslost;

                if (logger.isInfoEnabled())
                {
                    logger.info(
                            "FMJ reports " + packetslost + " lost packets ("
                                + fractionlost + ") for SSRC "
                                + (ssrc & 0xffffffffl) + " (" + ssrc + ")");
                }
            }
            else
            {
                // Don't send RTCP feedback information for this sub-stream.
                // TODO(gp) Any endpoints receiving this stream must switch to
                // a lower quality stream.
                if (logger.isInfoEnabled())
                {
                    logger.info(
                            "FMJ has no information for SSRC "
                                + (ssrc & 0xffffffffl) + " (" + ssrc + ")");
                }
            }
        }

        return
            receiverReports.toArray(
                    new RTCPReportBlock[receiverReports.size()]);
    }

    @Override
    public RTCPPacket[] makeReports(RTCPTransmitter rtcpTransmitter)
    {
        RTPTranslator rtpTranslator = this.rtpTranslator;

        if (!(rtpTranslator instanceof RTPTranslatorImpl))
            return null;

        long time = System.currentTimeMillis();

        RTPTranslatorImpl rtpTranslatorImpl = (RTPTranslatorImpl) rtpTranslator;

        // Use the SSRC of the bridge (that is announced through signaling) so
        // that the endpoints won't drop the packet.
        int localSSRC = (int) rtpTranslatorImpl.getLocalSSRC(null);

        for (Endpoint endpoint : this.conference.getEndpoints())
        {
            for (RtpChannel channel : endpoint.getChannels(MediaType.VIDEO))
            {
                // Make the RTCP reports.
                RTCPPacket[] packets
                    = makeReports(
                            (VideoChannel) channel,
                            rtcpTransmitter,
                            time,
                            localSSRC);

                // Transmit the RTCP reports.
                if ((packets != null) && (packets.length != 0))
                {
                    RTCPCompoundPacket compoundPacket
                        = new RTCPCompoundPacket(packets);
                    Payload payload = new RTCPPacketPayload(compoundPacket);

                    rtpTranslatorImpl.writeControlPayload(
                            payload,
                            channel.getStream());
                }
            }
        }

        /*
         * TODO Lyubomir: RTCPTransmitter cannot transmit specific reports to
         * specific destinations so we've implemented the transmission
         * ourselves. However, we're not updating the (global) transmission
         * statistics maintained by RTCPTranmitter.
         */
        return null;
    }

    private RTCPPacket[] makeReports(
            VideoChannel videoChannel,
            RTCPTransmitter rtcpTransmitter,
            long time,
            int localSSRC)
    {
        // RTCP RR
        RTCPReportBlock[] receiverReports
            = makeReceiverReports(videoChannel, rtcpTransmitter, time);
        RTCPPacket rr = new RTCPRRPacket(localSSRC, receiverReports);

        // RTCP REMB
        long mediaSSRC = 0l;
        int exp = MaxThroughputRTCPTerminationStrategy.MAX_EXP;
        int mantissa = MaxThroughputRTCPTerminationStrategy.MAX_MANTISSA;
        long[] dest = new long[receiverReports.length];

        for (int i = 0; i < dest.length; i++)
            dest[i] = receiverReports[i].getSSRC();

        RTCPPacket remb
            = new RTCPREMBPacket(
                    localSSRC,
                    mediaSSRC,
                    exp,
                    mantissa,
                    dest);

        // RTCP SDES
        List<RTCPSDES> sdesChunks
            = new ArrayList<RTCPSDES>(1 + receiverReports.length);
        RTCPSDES sdesChunk = createRTCPSDES(rtcpTransmitter, localSSRC);

        if (sdesChunk != null)
            sdesChunks.add(sdesChunk);
        for (long ssrc : dest)
        {
            sdesChunk = createRTCPSDES(rtcpTransmitter, (int) ssrc);
            if (sdesChunk != null)
                sdesChunks.add(sdesChunk);
        }

        RTCPSDESPacket sdes
            = new RTCPSDESPacket(
                    sdesChunks.toArray(new RTCPSDES[sdesChunks.size()]));

        return new RTCPPacket[] { rr, remb, sdes };
    }

    @Override
    public void setConference(Conference conference)
    {
        this.conference = conference;
    }

    @Override
    public void setRTPTranslator(RTPTranslator translator)
    {
        this.rtpTranslator = translator;
    }

    @Override
    public RTCPCompoundPacket transformRTCPPacket(RTCPCompoundPacket inPacket)
    {
        if (inPacket == null)
            return inPacket;

        RTCPPacket[] inPackets = inPacket.packets;

        if ((inPackets == null) || (inPackets.length == 0))
            return inPacket;

        List<RTCPPacket> outPackets
            = new ArrayList<RTCPPacket>(inPackets.length);

        for (RTCPPacket p : inPackets)
        {
            switch (p.type)
            {
            case RTCPPacket.RR:
                // Mute RRs from the peers. We send our own.
                break;

            case RTCPPacket.SR:
                // Remove feedback information from the SR and forward.
                RTCPSRPacket sr = (RTCPSRPacket) p;

                sr.reports = new RTCPReportBlock[0];
                outPackets.add(sr);
                break;

            case RTCPFBPacket.PSFB:
                RTCPFBPacket psfb = (RTCPFBPacket) p;

                switch (psfb.fmt)
                {
                case RTCPREMBPacket.FMT:
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

        RTCPCompoundPacket outPacket;

        if (outPackets.isEmpty())
        {
            outPacket = null;
        }
        else
        {
            outPacket
                = new RTCPCompoundPacket(
                        outPackets.toArray(new RTCPPacket[outPackets.size()]));
        }
        return outPacket;
    }
}
