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
import org.jitsi.impl.neomedia.rtcp.termination.strategies.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * This class extends the <tt>BasicRTCPTerminationStrategy</tt> to make it work
 * with features found exclusively in the video bridge like, for example, lastN
 * and simulcast.
 *
 * @author George Politis
 */
public class BasicBridgeRTCPTerminationStrategy
    extends AbstractBridgeRTCPTerminationStrategy
{
    private static final Logger logger
            = Logger.getLogger(BasicBridgeRTCPTerminationStrategy.class);

    /**
     * Constructor.
     */
    public BasicBridgeRTCPTerminationStrategy()
    {
        setTransformerChain(new Transformer[]{
                new REMBNotifier(this),
                new ReceiverFeedbackFilter(),
                new SenderFeedbackExploder(this)
        });
    }

    /**
     * Sends RRs using data from FMJ and and REMBs using data from our remote
     * bitrate estimator.
     *
     * @return <tt>null</tt>
     */
    public RTCPPacket[] makeReports()
    {
        RTPTranslator rtpTranslator = this.getRTPTranslator();

        if (!(rtpTranslator instanceof RTPTranslatorImpl))
            return null;

        Conference conference = this.getConference();
        if (conference == null)
            return null;

        long time = System.currentTimeMillis();
        RTPTranslatorImpl rtpTranslatorImpl = (RTPTranslatorImpl) rtpTranslator;
        // Use the SSRC of the bridge (that is announced through signaling) so
        // that the endpoints won't drop the packet.
        int localSSRC = (int) rtpTranslatorImpl.getLocalSSRC(null);
        RTCPTransmitter rtcpTransmitter
                = this.getRTCPReportBuilder().getRTCPTransmitter();

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
     * @param ssrc
     * @return
     */
    private RTCPSDES createRTCPSDES(int ssrc)
    {
        RTCPTransmitter rtcpTransmitter
                = this.getRTCPReportBuilder().getRTCPTransmitter();

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
                = this.getRTCPReportBuilder().getRTCPTransmitter();

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

        // Destination
        RemoteBitrateEstimator remoteBitrateEstimator
                = ((VideoMediaStream) videoChannel.getStream())
                .getRemoteBitrateEstimator();
        Collection<Integer> ssrcs = remoteBitrateEstimator.getSsrcs();

        // TODO(gp) intersect with SSRCs from signaled simulcast layers
        // NOTE(gp) The Google Congestion Control algorithm (sender side)
        // doesn't seem to care about the SSRCs in the dest field.
        long[] dest = new long[ssrcs.size()];
        int i = 0;

        for (Integer ssrc : ssrcs)
            dest[i++] = ssrc & 0xFFFFFFFFL;

        // Exp & mantissa
        long bitrate = remoteBitrateEstimator.getLatestEstimate();

        if (bitrate == -1)
            return null;

        if (logger.isDebugEnabled())
            logger.debug("Estimated bitrate: " + bitrate);

        // Create and return the packet.
        return
                new RTCPREMBPacket(
                        localSSRC & 0xFFFFFFFFL,
                    /* mediaSSRC */ 0L,
                        bitrate,
                        dest);
    }

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

        return packets.toArray(new RTCPPacket[packets.size()]);
    }
}
