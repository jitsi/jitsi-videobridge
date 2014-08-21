/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rtcp;

import java.util.*;

import net.sf.fmj.media.rtp.*;

import org.jitsi.impl.neomedia.rtcp.termination.strategies.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;

/**
 * This class extends the <tt>BasicRTCPTerminationStrategy</tt> to make it work
 * with features found exclusively in the video bridge like, for example, lastN
 * and simulcast.
 *
 * Created by gp on 17/07/14.
 */
public class BasicBridgeRTCPTerminationStrategy
    extends BasicRTCPTerminationStrategy
    implements BridgeRTCPTerminationStrategy
{
    private Conference conference;

    @Override
    public void setConference(Conference conference)
    {
        this.conference = conference;
    }

    @Override
    public RTCPCompoundPacket transformRTCPPacket(
            RTCPCompoundPacket inPacket)
    {
        // 1. Removes receiver report blocks from RRs and SRs and kills REMBs.
        // 2. Updates the receiver feedback cache.

        RTCPCompoundPacket outPacket = super.transformRTCPPacket(inPacket);

        // 3. Explodes sender reports.

        if (outPacket.packets != null
            && outPacket.packets.length != 0
            && outPacket.packets[0].type == RTCPPacket.SR)
        {
            if (explodeSenderReport(outPacket))
            {
                return null;
            }
            else
            {
                return outPacket;
            }
        }
        else
        {
            return outPacket;
        }
    }

    private boolean explodeSenderReport(RTCPCompoundPacket outPacket)
    {
        if (outPacket.packets == null
                || outPacket.packets.length == 0
                || outPacket.packets[0].type != RTCPPacket.SR)
        {
            return false;
        }

        RTCPSRPacket senderReport = (RTCPSRPacket) outPacket.packets[0];

        // We explode SRs to make them LastN compliant.

        Conference conf = this.conference;
        if (senderReport == null || conf == null)
            return false;

        RTPTranslator rtpTranslator = this.translator;
        if (rtpTranslator == null
                || !(rtpTranslator instanceof RTPTranslatorImpl))
            return false;

        RTPTranslatorImpl rtpTranslatorImpl = (RTPTranslatorImpl)rtpTranslator;

        long ssrc = senderReport.ssrc & 0xFFFFFFFFL;
        if (ssrc < 1)
            return false;

        Integer senderSSRC = Integer.valueOf(senderReport.ssrc);
        Map<Integer, SenderInformation> receiverSenderInformationMap
                = getReceiverSenderInformationMap(senderSSRC);

        Channel srcChannel = conf
                .findChannelByReceiveSSRC(ssrc, MediaType.VIDEO);

        if (!(srcChannel instanceof RtpChannel))
            return false;

        RtpChannel srcRtpChannel = (RtpChannel)srcChannel;

        for (Content content : conference.getContents())
        {
            if (MediaType.VIDEO.equals(content.getMediaType()))
            {
                for (Channel destChannel : content.getChannels())
                {
                    if (!(destChannel instanceof RtpChannel)
                            || srcChannel == destChannel)
                        continue;

                    RtpChannel destRtpChannel = (RtpChannel) destChannel;
                    MediaStream stream = destRtpChannel.getStream();
                    if (stream == null)
                        continue;

                    RTCPSRPacket sr = new RTCPSRPacket(
                            senderSSRC, new RTCPReportBlock[0]);
                    sr.ntptimestampmsw = senderReport.ntptimestampmsw;
                    sr.ntptimestamplsw = senderReport.ntptimestamplsw;
                    sr.rtptimestamp = senderReport.rtptimestamp;
                    sr.octetcount = senderReport.octetcount;
                    sr.packetcount = senderReport.packetcount;

                    Integer receiverSSRC = Integer.valueOf(
                            (int) stream.getLocalSourceID());

                    boolean destIsReceiving
                            = srcRtpChannel.isInLastN(destChannel);

                    if (destIsReceiving && srcChannel instanceof VideoChannel)
                    {
                        VideoChannel srcVideoChannel
                                = (VideoChannel) srcChannel;

                        if (!(destChannel instanceof VideoChannel))
                        {
                            destIsReceiving = false;
                        }
                        else
                        {
                            VideoChannel dstVideoChannel
                                    = (VideoChannel) destChannel;

                            destIsReceiving = dstVideoChannel.getSimulcastManager()
                                    .acceptSimulcastLayer(ssrc,
                                            srcVideoChannel);
                        }
                    }

                    if (destIsReceiving)
                    {
                        // The sender is in the LastN set for this receiver:
                        // Cache the sender information.
                        SenderInformation si = new SenderInformation();
                        si.octetCount = senderReport.octetcount;
                        si.packetCount = senderReport.packetcount;

                        synchronized (receiverSenderInformationMap)
                        {
                            receiverSenderInformationMap.put(receiverSSRC, si);
                        }
                    }
                    else
                    {
                        // The sender is NOT being received by te this receiver:
                        // We keep the packet counqt/octet count stable.
                        SenderInformation si;
                        synchronized (receiverSenderInformationMap)
                        {
                            if (receiverSenderInformationMap
                                    .containsKey(receiverSSRC))
                            {
                                si = receiverSenderInformationMap
                                        .get(receiverSSRC);
                            }
                            else
                            {
                                si = null;
                            }
                        }

                        if (si != null)
                        {
                            sr.packetcount = si.packetCount;
                            sr.octetcount = si.octetCount;
                        }
                        else
                        {
                            sr.packetcount = 0L;
                            sr.octetcount = 0L;
                        }
                    }

                    RTCPPacket[] packets
                            = new RTCPPacket[outPacket.packets.length];

                    packets[0] = sr;

                    System.arraycopy(
                            outPacket.packets, 1,
                            packets, 1, outPacket.packets.length - 1);

                    RTCPCompoundPacket compoundPacket
                            = new RTCPCompoundPacket(packets);

                    Payload payload = new RTCPPacketPayload(compoundPacket);
                    rtpTranslatorImpl.writeControlPayload(payload, stream);
                }
            }
        }

        return true;
    }

    class SenderInformation
    {
        long packetCount;
        long octetCount;
    }

    Map<Integer, Map<Integer, SenderInformation>> lastSenderInformationMap
            = new HashMap<Integer, Map<Integer, SenderInformation>>();

    private Map<Integer, SenderInformation> getReceiverSenderInformationMap(
            Integer senderSSRC)
    {
        Map<Integer, SenderInformation> receiverSenderInformationMap;
        synchronized (lastSenderInformationMap)
        {
            if (lastSenderInformationMap.containsKey(senderSSRC))
            {
                receiverSenderInformationMap
                        = lastSenderInformationMap.get(senderSSRC);
            }
            else
            {
                receiverSenderInformationMap
                        = new HashMap<Integer, SenderInformation>();

                lastSenderInformationMap.put(senderSSRC,
                        receiverSenderInformationMap);
            }
        }

        return receiverSenderInformationMap;
    }
}
