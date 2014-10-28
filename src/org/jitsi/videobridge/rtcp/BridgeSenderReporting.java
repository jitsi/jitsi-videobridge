/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rtcp;

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.recording.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * @author George Politis
 */
public class BridgeSenderReporting
{

    public BridgeSenderReporting(BridgeRTCPTerminationStrategy strategy)
    {
        this.strategy = strategy;
    }

    public final BridgeRTCPTerminationStrategy strategy;

    private final Map<Integer, Map<Integer, SenderInformation>>
        lastSenderInformationMap
            = new HashMap<Integer, Map<Integer, SenderInformation>>();

    /**
     * Explode the SRs to make them compliant with features from the translator.
     *
     * @param outPacket
     * @return
     */
    public boolean explodeSenderReport(RTCPCompoundPacket outPacket)
    {
        if (outPacket.packets == null
                || outPacket.packets.length == 0
                || outPacket.packets[0].type != RTCPPacket.SR)
        {
            return false;
        }

        RTCPSRPacket senderReport = (RTCPSRPacket) outPacket.packets[0];

        Conference conf = strategy.getConference();
        if (senderReport == null || conf == null)
            return false;

        RTPTranslator rtpTranslator = strategy.getRTPTranslator();
        if (rtpTranslator == null
                || !(rtpTranslator instanceof RTPTranslatorImpl))
            return false;

        RTPTranslatorImpl rtpTranslatorImpl = (RTPTranslatorImpl)rtpTranslator;

        long ssrc = senderReport.ssrc & 0xFFFFFFFFL;
        if (ssrc < 1)
            return false;

        Integer senderSSRC = senderReport.ssrc;
        Map<Integer, SenderInformation> receiverSenderInformationMap
                = getReceiverSenderInformationMap(senderSSRC);

        Channel srcChannel = conf
                .findChannelByReceiveSSRC(ssrc, MediaType.VIDEO);

        if (srcChannel == null || !(srcChannel instanceof RtpChannel))
            return false;

        RtpChannel srcRtpChannel = (RtpChannel)srcChannel;

        // Send to every channel that receives this sender an SR.
        for (Content content : conf.getContents())
        {
            if (MediaType.VIDEO.equals(content.getMediaType()))
            {
                for (Channel destChannel : content.getChannels())
                {
                    if (!(destChannel instanceof RtpChannel)
                            || srcRtpChannel == destChannel)
                        continue;

                    RtpChannel destRtpChannel = (RtpChannel) destChannel;
                    MediaStream stream = destRtpChannel.getStream();
                    if (stream == null)
                        continue;

                    boolean destIsReceiving
                            = srcRtpChannel.isInLastN(destChannel);

                    if (destIsReceiving && srcRtpChannel instanceof VideoChannel)
                    {
                        VideoChannel srcVideoChannel
                                = (VideoChannel) srcRtpChannel;

                        if (!(destChannel instanceof VideoChannel))
                        {
                            destIsReceiving = false;
                        }
                        else
                        {
                            VideoChannel destVideoChannel
                                    = (VideoChannel) destChannel;

                            destIsReceiving
                                    = destVideoChannel.getSimulcastManager()
                                    .accept(ssrc,
                                            srcVideoChannel);
                        }
                    }

                    explodeSenderReport(destIsReceiving, outPacket,
                            senderReport,
                            rtpTranslatorImpl,
                            senderSSRC,
                            receiverSenderInformationMap,
                            stream);
                }

                if (content.isRecording())
                {
                    Recorder recorder = content.getRecorder();
                    MediaStream s;

                    if (recorder != null && (s = recorder.getMediaStream()) != null)
                    {
                        explodeSenderReport(true, outPacket,
                                senderReport,
                                rtpTranslatorImpl,
                                senderSSRC,
                                receiverSenderInformationMap,
                                s);
                    }
                }
            }
        }

        return true;
    }

    private void explodeSenderReport(boolean destIsReceiving,
            RTCPCompoundPacket outPacket,
            RTCPSRPacket senderReport,
            RTPTranslatorImpl rtpTranslatorImpl,
            Integer senderSSRC,
            Map<Integer, SenderInformation> receiverSenderInformationMap,
            MediaStream stream)
    {
        // "Clone" the SR.
        RTCPSRPacket sr = new RTCPSRPacket(
                senderSSRC, new RTCPReportBlock[0]);
        sr.ntptimestampmsw = senderReport.ntptimestampmsw;
        sr.ntptimestamplsw = senderReport.ntptimestamplsw;
        sr.rtptimestamp = senderReport.rtptimestamp;
        sr.octetcount = senderReport.octetcount;
        sr.packetcount = senderReport.packetcount;

        Integer receiverSSRC = (int) stream.getLocalSourceID();

        if (destIsReceiving)
        {
            // The sender is being received by this receiver:
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
            // The sender is NOT being received by this receiver:
            // We keep the packet count/octet count stable.
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

        // Send the SR to the receiver.
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

    private static class SenderInformation
    {
        long packetCount;
        long octetCount;
    }
}
