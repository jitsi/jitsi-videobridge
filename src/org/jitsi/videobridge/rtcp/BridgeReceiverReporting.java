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
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

/**
 *
 * @author George Politis
 * @author Lyubomir Marinov
 */
public class BridgeReceiverReporting
{
    /**
     *
     */
    private static final Logger logger
            = Logger.getLogger(BridgeReceiverReporting.class);

    /**
     *
     */
    private final BridgeRTCPTerminationStrategy strategy;

    /**
     *
     * @param strategy
     */
    public BridgeReceiverReporting(BridgeRTCPTerminationStrategy strategy)
    {
        this.strategy = strategy;
    }

    /**
     *
     * @param ssrc
     * @return
     */
    private RTCPSDES createRTCPSDES(int ssrc)
    {
        RTCPTransmitter rtcpTransmitter
                = strategy.getRTCPReportBuilder().getRTCPTransmitter();

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

    /**
     *
     * @param videoChannel
     * @param time
     * @return
     */
    private RTCPReportBlock[] createRTCPReportBlocksForChannel(
            VideoChannel videoChannel,
            long time)
    {
        RTCPTransmitter rtcpTransmitter
                = strategy.getRTCPReportBuilder().getRTCPTransmitter();

        int[] ssrcs = videoChannel.getReceiveSSRCs();
        List<RTCPReportBlock> receiverReports
                = new ArrayList<RTCPReportBlock>(ssrcs.length);

        for (int ssrc : ssrcs)
        {
            // TODO(gp) we need a mutex here for accessing the RTCP transmitter
            // cache.
            SSRCInfo info = rtcpTransmitter.cache.cache.get(ssrc);

            if (info != null)
            {
                RTCPReportBlock receiverReport
                        = info.makeReceiverReport(time);

                receiverReports.add(receiverReport);
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

    /**
     *
     * @param videoChannel
     * @param localSSRC
     * @return
     */
    private RTCPREMBPacket createRTCPREMBPacketForChannel(
            VideoChannel videoChannel,
            int localSSRC)
    {
        if (videoChannel == null)
            throw new IllegalArgumentException("videoChannel");

        // Media SSRC (always 0)
        final long mediaSSRC = 0l;

        // Destination
        RemoteBitrateEstimator remoteBitrateEstimator
                = ((VideoMediaStream) videoChannel.getStream())
                .getRemoteBitrateEstimator();

        Collection<Integer> tmp = remoteBitrateEstimator.getSsrcs();
        List<Integer> ssrcs = new ArrayList<Integer>(tmp);

        // TODO(gp) intersect with SSRCs from signaled simulcast layers
        // NOTE(gp) The Google Congestion Control algorithm (sender side)
        // doesn't seem to care about the SSRCs in the dest field.
        long[] dest = new long[ssrcs.size()];
        for (int i = 0; i < ssrcs.size(); i++)
            dest[i] = ssrcs.get(i) & 0xffffffffl;

        // Exp & mantissa
        long bitrate = remoteBitrateEstimator.getLatestEstimate();
        if (bitrate == -1)
        {
            return null;
        }

        if (logger.isDebugEnabled())
            logger.debug("Estimated bitrate: " + bitrate);

        // Create and return the packet.
        return
                new RTCPREMBPacket(
                        localSSRC & 0xFFFFFFFFL,
                        mediaSSRC,
                        bitrate,
                        dest);
    }

    /**
     * Sends RRs using data from FMJ and and REMBs using data from our remote
     * bitrate estimator.
     *
     * @return null
     */
    public RTCPPacket[] makeReports()
    {
        RTPTranslator rtpTranslator = this.strategy.getRTPTranslator();

        if (!(rtpTranslator instanceof RTPTranslatorImpl))
            return null;

        Conference conference = this.strategy.getConference();

        long time = System.currentTimeMillis();

        RTPTranslatorImpl rtpTranslatorImpl = (RTPTranslatorImpl) rtpTranslator;

        // Use the SSRC of the bridge (that is announced through signaling) so
        // that the endpoints won't drop the packet.
        int localSSRC = (int) rtpTranslatorImpl.getLocalSSRC(null);

        RTCPTransmitter rtcpTransmitter
                = strategy.getRTCPReportBuilder().getRTCPTransmitter();

        for (Endpoint endpoint : conference.getEndpoints())
        {
            for (RtpChannel channel : endpoint.getChannels(MediaType.VIDEO))
            {
                // Make the RTCP reports.
                RTCPPacket[] packets
                        = makeReportsForChannel(
                        (VideoChannel) channel,
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

                    /*
                     * NOTE(gp, lyubomir): RTCPTransmitter cannot transmit
                     * specific reports to specific destinations so we've
                     * implemented the transmission ourselves. We're updating
                     * the (global) transmission statistics maintained by
                     * RTCPTranmitter by calling its onRTCPCompoundPacketSent
                     * method.
                     */
                    rtcpTransmitter.onRTCPCompoundPacketSent(compoundPacket);
                }
            }
        }

        return null;
    }

    /**
     *
     * @param videoChannel
     * @param time
     * @param localSSRC
     * @return
     */
    private RTCPPacket[] makeReportsForChannel(
            VideoChannel videoChannel,
            long time,
            int localSSRC)
    {
        List<RTCPPacket> packets = new ArrayList<RTCPPacket>(3);

        // RTCP RR
        RTCPReportBlock[] receiverReports
                = createRTCPReportBlocksForChannel(videoChannel, time);
        RTCPPacket rr = new RTCPRRPacket(localSSRC, receiverReports);

        packets.add(rr);

        // RTCP REMB
        RTCPREMBPacket remb
                = createRTCPREMBPacketForChannel(videoChannel, localSSRC);

        if (remb != null)
        {
            packets.add(remb);

            if (logger.isDebugEnabled())
                logger.debug(remb);
        }

        // RTCP SDES
        List<RTCPSDES> sdesChunks
                = new ArrayList<RTCPSDES>(1 + receiverReports.length);
        RTCPSDES sdesChunk = createRTCPSDES(localSSRC);

        if (sdesChunk != null)
            sdesChunks.add(sdesChunk);

        long[] dest = new long[receiverReports.length];

        for (int i = 0; i < dest.length; i++)
            dest[i] = receiverReports[i].getSSRC();

        for (long ssrc : dest)
        {
            sdesChunk = createRTCPSDES((int) ssrc);
            if (sdesChunk != null)
                sdesChunks.add(sdesChunk);
        }

        // TODO(gp) why does this happen : sdesChunks.size() == 0
        if (sdesChunks.size() != 0)
        {
            RTCPSDESPacket sdes
                = new RTCPSDESPacket(
                sdesChunks.toArray(new RTCPSDES[sdesChunks.size()]));

            packets.add(sdes);
        }

        RTCPPacket[] pkts = packets.toArray(new RTCPPacket[packets.size()]);
        return pkts;
    }
}
