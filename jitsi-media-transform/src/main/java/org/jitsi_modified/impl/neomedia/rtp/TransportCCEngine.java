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
package org.jitsi_modified.impl.neomedia.rtp;

import kotlin.Unit;
import org.jetbrains.annotations.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateObserver;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.rtp.rtcp.rtcpfb.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateEstimatorAbsSendTime;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Implements transport-cc functionality as a {@link TransformEngine}. The
 * intention is to have the same instance shared between all media streams of
 * a transport channel, so we expect it will be accessed by multiple threads.
 * See https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01
 *
 * @author Boris Grozev
 * @author Julian Chukwu
 * @author George Politis
 *
 * NOTE(brian): this class needs a run through: it doesn't do as much as it used to, be was ported mostly as-is
 * for now to reduce the amount of work to get the bridge working again.  Once the dust has settled we should
 * revisit this and clean it up.
 */
public class TransportCCEngine
    extends RTCPPacketListenerAdapter
    implements RemoteBitrateObserver,
               CallStatsObserver
{
    /**
     * The maximum number of received packets and their timestamps to save.
     */
    private static final int MAX_INCOMING_PACKETS_HISTORY = 200;

    /**
     * The maximum number of received packets and their timestamps to save.
     *
     * XXX this is an uninformed value.
     */
    private static final int MAX_OUTGOING_PACKETS_HISTORY = 1000;

    /**
     * The {@link Logger} used by the {@link TransportCCEngine} class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(TransportCCEngine.class);

    /**
     * The {@link TimeSeriesLogger} to be used by this instance to print time
     * series.
     */
    private static final TimeSeriesLogger timeSeriesLogger
        = TimeSeriesLogger.getTimeSeriesLogger(TransportCCEngine.class);

    /**
     * The engine which handles incoming RTP packets for this instance. It
     * reads transport-wide sequence numbers and registers arrival times.
     */
    private final IngressEngine ingressEngine = new IngressEngine();

    /**
     * The engine which handles outgoing RTP packets for this instance. It
     * adds transport-wide sequence numbers to outgoing RTP packets.
     */
    private final EgressEngine egressEngine = new EgressEngine();

    /**
     * The ID of the transport-cc RTP header extension, or -1 if one is not
     * configured.
     */
    private int extensionId = -1;

    /**
     * The next sequence number to use for outgoing data packets.
     */
    private AtomicInteger outgoingSeq = new AtomicInteger(1);

    /**
     * The running index of sent RTCP transport-cc feedback packets.
     */
    private AtomicInteger outgoingFbPacketCount = new AtomicInteger();

    /**
     * The list of {@link MediaStream} that are using this
     * {@link TransportCCEngine}.
     */
    private final List<MediaStream> mediaStreams = new LinkedList<>();

    /**
     * Some {@link VideoMediaStream} that utilizes this instance. We use it to
     * get the sender/media SSRC of the outgoing RTCP TCC packets.
     */
    private VideoMediaStream anyVideoMediaStream;

    /**
     * Incoming transport-wide sequence numbers mapped to the timestamp of their
     * reception (in milliseconds since the epoch).
     */
    private RTCPTCCPacket.PacketMap incomingPackets;

    /**
     * Used to synchronize access to {@link #incomingPackets}.
     */
    private final Object incomingPacketsSyncRoot = new Object();

    /**
     * Used to synchronize access to {@link #sentPacketDetails}.
     */
    private final Object sentPacketsSyncRoot = new Object();

    /**
     * The {@link DiagnosticContext} to be used by this instance when printing
     * diagnostic information.
     */
    private final DiagnosticContext diagnosticContext;

    /**
     * The time (in milliseconds since the epoch) at which the first received
     * packet in {@link #incomingPackets} was received (or -1 if the map is
     * empty).
     * Kept here for quicker access, because the map is ordered by sequence
     * number.
     */
    private long firstIncomingTs = -1;

    /**
     * The reference time of the remote clock. This is used to rebase the
     * arrival times in the TCC packets to a meaningful time base (that of the
     * sender). This is technically not necessary and it's done for convenience.
     */
    private long remoteReferenceTimeMs = -1;

    /**
     * Local time to map to the reference time of the remote clock. This is used
     * to rebase the arrival times in the TCC packets to a meaningful time base
     * (that of the sender). This is technically not necessary and it's done for
     * convinience.
     */
    private long localReferenceTimeMs = -1;

    /**
     * Holds a key value pair of the packet sequence number and an object made
     * up of the packet send time and the packet size.
     */
    private Map<Integer, PacketDetail> sentPacketDetails
        = new LRUCache<>(MAX_OUTGOING_PACKETS_HISTORY);

    /**
     * Used for estimating the bitrate from RTCP TCC feedback packets
     */
    private final RemoteBitrateEstimatorAbsSendTime bitrateEstimatorAbsSendTime;

    /**
     * Ctor.
     *
     * @param diagnosticContext the {@link DiagnosticContext} of this instance.
     */
    public TransportCCEngine(@NotNull DiagnosticContext diagnosticContext)
    {
        this.diagnosticContext = diagnosticContext;
        bitrateEstimatorAbsSendTime
            = new RemoteBitrateEstimatorAbsSendTime(this, diagnosticContext);
    }

    /**
     * Notifies this instance that a data packet with a specific transport-wide
     * sequence number was received on this transport channel.
     *
     * @param seq the transport-wide sequence number of the packet.
     * @param marked whether the RTP packet had the "marked" bit set.
     */
    private void packetReceived(int seq, int pt, boolean marked)
    {
        long now = System.currentTimeMillis();
        synchronized (incomingPacketsSyncRoot)
        {
            if (incomingPackets == null)
            {
                incomingPackets = new RTCPTCCPacket.PacketMap();
            }
            if (incomingPackets.size() >= MAX_INCOMING_PACKETS_HISTORY)
            {
                Iterator<Map.Entry<Integer, Long>> iter
                    = incomingPackets.entrySet().iterator();
                if (iter.hasNext())
                {
                    iter.next();
                    iter.remove();
                }

                // This shouldn't happen, because we will send feedback often.
                logger.info("Reached max size, removing an entry.");
            }

            if (incomingPackets.isEmpty())
            {
                firstIncomingTs = now;
            }
            incomingPackets.put(seq, now);
        }

//        if (timeSeriesLogger.isTraceEnabled())
//        {
//            timeSeriesLogger.trace(diagnosticContext
//                    .makeTimeSeriesPoint("ingress_tcc_pkt", now)
//                    .addField("seq", seq)
//                    .addField("pt", pt));
//        }

//        maybeSendRtcp(marked, now);
    }

    /**
     * Gets the source SSRC to use for the outgoing RTCP TCC packets.
     *
     * @return the source SSRC to use for the outgoing RTCP TCC packets.
     */
//    private long getSourceSSRC()
//    {
//        MediaStream stream = anyVideoMediaStream;
//        if (stream == null)
//        {
//            return -1;
//        }
//
//        MediaStreamTrackReceiver receiver
//            = stream.getMediaStreamTrackReceiver();
//        if (receiver == null)
//        {
//            return -1;
//        }
//
//        MediaStreamTrackDesc[] tracks = receiver.getMediaStreamTracks();
//        if (tracks == null || tracks.length == 0)
//        {
//            return -1;
//        }
//
//        RTPEncodingDesc[] encodings = tracks[0].getRTPEncodings();
//        if (encodings == null || encodings.length == 0)
//        {
//            return -1;
//        }
//
//        return encodings[0].getPrimarySSRC();
//    }

    /**
     * Examines the list of received packets for which we have not yet sent
     * feedback and determines whether we should send feedback at this point.
     * If so, sends the feedback.
     * @param marked whether the last received RTP packet had the "marked" bit
     * set.
     * @param now the current time.
     * DEPRECATED this class is no longer responsible for sending tcc rtcp packets
     */
//    @Deprecated
//    private void maybeSendRtcp(boolean marked, long now)
//    {
//        RTCPTCCPacket.PacketMap packets = null;
//        long delta;
//
//        synchronized (incomingPacketsSyncRoot)
//        {
//            if (incomingPackets == null || incomingPackets.isEmpty())
//            {
//                // No packets with unsent feedback.
//                return;
//            }
//
//            delta = firstIncomingTs == -1 ? 0 : (now - firstIncomingTs);
//
//            // The number of packets represented in incomingPackets (including
//            // the missing ones), i.e. the number of entries that the RTCP TCC
//            // packet would include.
//            int packetCount
//                = 1 + RTPUtils.subtractNumber(
//                    incomingPackets.lastKey(),
//                    incomingPackets.firstKey());
//
//            // This condition controls when we send feedback:
//            // 1. If 100ms have passed,
//            // 2. If we see the end of a frame, and 20ms have passed, or
//            // 3. If we have at least 100 packets.
//            // 4. We are approaching the maximum number of packets we can
//            // report on in one RTCP packet.
//            // The exact values and logic here are to be improved.
//            if (delta > 100
//                || (delta > 20 && marked)
//                || incomingPackets.size() > 100
//                || packetCount >= RTCPTCCPacket.MAX_PACKET_COUNT - 20)
//            {
//                packets = incomingPackets;
//                incomingPackets = null;
//                firstIncomingTs = -1;
//            }
//        }
//
//        if (packets != null)
//        {
//            MediaStream stream = getMediaStream();
//            if (stream == null)
//            {
//                logger.warn("No media stream, can't send RTCP.");
//                return;
//            }
//
//            try
//            {
//                long senderSSRC
//                    = anyVideoMediaStream.getStreamRTPManager().getLocalSSRC();
//                if (senderSSRC == -1)
//                {
//                    logger.warn("No sender SSRC, can't send RTCP.");
//                    return;
//                }
//
//
//                long sourceSSRC = getSourceSSRC();
//                if (sourceSSRC == -1)
//                {
//                    logger.warn("No source SSRC, can't send RTCP.");
//                    return;
//                }
//                RTCPTCCPacket rtcpPacket
//                    = new RTCPTCCPacket(
//                        senderSSRC, sourceSSRC,
//                        packets,
//                        (byte) (outgoingFbPacketCount.getAndIncrement() & 0xff),
//                        diagnosticContext);
//
//                // Inject the TCC packet *after* this engine. We don't want
//                // RTCP termination -which runs before this engine in the
//                // egress- to drop the packet we just sent.
//                stream.injectPacket(
//                        rtcpPacket.toRawPacket(), false /* rtcp */, egressEngine);
//            }
//            catch (IllegalArgumentException iae)
//            {
//                // This comes from the RTCPTCCPacket constructor when the
//                // list of packets contains a delta which cannot be expressed
//                // in a single packet (more than 8192 milliseconds), or the
//                // number of packets to report (including the ones lost) is
//                // too big for one RTCP TCC packet. In this case we would have
//                // to split the feedback in two or more RTCP TCC packets.
//                // We currently don't do this, because it only happens if the
//                // receiver stops sending packets for over 8s or there is a
//                // significant gap in the received sequence numbers. In this
//                // case we will fail to send one feedback message.
//                logger.warn(
//                        "Not sending transport-cc feedback, delta or packet" +
//                            "count too big.");
//            }
//            catch (IOException | TransmissionFailedException e)
//            {
//                logger.error("Failed to send transport feedback RTCP: ", e);
//            }
//        }
//    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRttUpdate(long avgRttMs, long maxRttMs)
    {
        bitrateEstimatorAbsSendTime.onRttUpdate(avgRttMs, maxRttMs);
    }

    /**
     * Sets the ID of the transport-cc RTP extension. Set to -1 to effectively
     * disable.
     * @param id the ID to set.
     */
    public void setExtensionID(int id)
    {
        extensionId = id;
    }

    /**
     * Called when a receive channel group has a new bitrate estimate for the
     * incoming streams.
     *
     * @param ssrcs
     * @param bitrate
     */
    @Override
    public void onReceiveBitrateChanged(Collection<Long> ssrcs, long bitrate)
    {
        VideoMediaStream videoStream;
        for (MediaStream stream : mediaStreams)
        {
            if (stream instanceof VideoMediaStream)
            {
                videoStream = (VideoMediaStream) stream;
                videoStream.getOrCreateBandwidthEstimator()
                    .updateReceiverEstimate(bitrate);
                break;
            }
        }
    }

    public void tccReceived(RtcpFbTccPacket tccPacket)
    {
        tccPacket.getFci().forEach((tccSeqNum, recvTimestamp) ->
        {
            if (recvTimestamp == -1)
            {
                return Unit.INSTANCE;
            }
            if (remoteReferenceTimeMs == -1)
            {
                remoteReferenceTimeMs = tccPacket.getFci().getReferenceTimeMs();
                localReferenceTimeMs = System.currentTimeMillis();
            }

            PacketDetail packetDetail;
            synchronized (sentPacketsSyncRoot)
            {
                packetDetail = sentPacketDetails.remove(tccSeqNum);
            }

            if (packetDetail == null)
            {
                return Unit.INSTANCE;
            }
            long delta = recvTimestamp - tccPacket.getFci().getReferenceTimeMs();
//            logger.info("Got tcc for packet " + tccSeqNum + ", the reference time is " + tccPacket.getFci().getReferenceTimeMs() +
//                    " and it was received by the far side at " + recvTimestamp + ", meaning it has a delta of " +
//                            delta + ".  it was originally sent at " +
//                            packetDetail.packetSendTimeMs + " meaning in that clock it arrived at " +
//                            (packetDetail.packetSendTimeMs + delta));
            long arrivalTimeMs = packetDetail.packetSendTimeMs + delta;

//            logger.info("Notifying bitrate estimator of incoming packet info: " +
//                    "arrival time: " + arrivalTimeMs + ", sendTime24Bits: " + sendTime24bits +
//                    " packet length: " + packetDetail.packetLength + ", ssrc: " +
//                    tccPacket.getMediaSourceSsrc());
            bitrateEstimatorAbsSendTime.incomingPacketInfo(
                    arrivalTimeMs,
//                    sendTime24bits,
                    packetDetail.packetSendTimeMs,
                    packetDetail.packetLength,
                    tccPacket.getMediaSourceSsrc()
            );
//            logger.info("Latest bitrate estimate for " + tccPacket.getHeader().getSenderSsrc() +
//                    ": " + bitrateEstimatorAbsSendTime.getLatestEstimate() + "bps");
            return Unit.INSTANCE;
        });
    }

    /**
     * Handles an incoming RTCP transport-cc feedback packet.
     *
     * @param tccPacket the received TCC packet.
     */
    @Override
    public void tccReceived(RTCPTCCPacket tccPacket)
    {
        RTCPTCCPacket.PacketMap packetMap = tccPacket.getPackets();
        long previousArrivalTimeMs = -1;
        for (Map.Entry<Integer, Long> entry : packetMap.entrySet())
        {
            long arrivalTime250Us = entry.getValue();
            if (arrivalTime250Us == -1)
            {
                continue;
            }

            if (remoteReferenceTimeMs == -1)
            {
                remoteReferenceTimeMs = RTCPTCCPacket.getReferenceTime250us(
                        new ByteArrayBufferImpl(
                            tccPacket.fci, 0, tccPacket.fci.length)) / 4;

                localReferenceTimeMs = System.currentTimeMillis();
            }

            PacketDetail packetDetail;
            synchronized (sentPacketsSyncRoot)
            {
                packetDetail = sentPacketDetails.remove(entry.getKey());
            }

            if (packetDetail == null)
            {
                continue;
            }

            long arrivalTimeMs = arrivalTime250Us / 4
                - remoteReferenceTimeMs + localReferenceTimeMs;

            if (timeSeriesLogger.isTraceEnabled())
            {
                if (previousArrivalTimeMs != -1)
                {
                    long diff_ms = arrivalTimeMs - previousArrivalTimeMs;
                    timeSeriesLogger.trace(diagnosticContext
                            .makeTimeSeriesPoint("ingress_tcc_ack")
                            .addField("seq", entry.getKey())
                            .addField("arrival_time_ms", arrivalTimeMs)
                            .addField("diff_ms", diff_ms));
                }
                else
                {
                    timeSeriesLogger.trace(diagnosticContext
                            .makeTimeSeriesPoint("ingress_tcc_ack")
                            .addField("seq", entry.getKey())
                            .addField("arrival_time_ms", arrivalTimeMs));
                }
            }

            previousArrivalTimeMs = arrivalTimeMs;
            long sendTime24bits = RemoteBitrateEstimatorAbsSendTime
                .convertMsTo24Bits(packetDetail.packetSendTimeMs);

            bitrateEstimatorAbsSendTime.incomingPacketInfo(
                arrivalTimeMs,
                sendTime24bits,
                packetDetail.packetLength,
                tccPacket.getSourceSSRC());
        }
    }

    /**
     * Gets the engine which handles outgoing RTP packets for this instance.
     */
    public TransformEngine getEgressEngine()
    {
        return egressEngine;
    }

    /**
     * Gets the engine which handles incoming RTP packets for this instance.
     */
    public TransformEngine getIngressEngine()
    {
        return ingressEngine;
    }

    /**
     * Adds a {@link MediaStream} to the list of {@link MediaStream}s which
     * use this {@link TransportCCEngine}.
     * @param mediaStream the stream to add.
     */
    public void addMediaStream(MediaStream mediaStream)
    {
        synchronized (mediaStreams)
        {
            mediaStreams.add(mediaStream);

            // Hook us up to receive TCCs.
            MediaStreamStats stats = mediaStream.getMediaStreamStats();
            stats.addRTCPPacketListener(this);

            if (mediaStream instanceof VideoMediaStream)
            {
                anyVideoMediaStream = (VideoMediaStream) mediaStream;
                diagnosticContext.put("video_stream", mediaStream.hashCode());
            }
        }
    }

    /**
     * Removes a {@link MediaStream} from the list of {@link MediaStream}s which
     * use this {@link TransportCCEngine}.
     * @param mediaStream the stream to remove.
     */
    public void removeMediaStream(MediaStream mediaStream)
    {
        synchronized (mediaStreams)
        {
            while(mediaStreams.remove(mediaStream))
            {
                // we loop in order to remove all instances.
            }

            // Hook us up to receive TCCs.
            MediaStreamStats stats = mediaStream.getMediaStreamStats();
            stats.removeRTCPPacketListener(this);

            if (mediaStream == anyVideoMediaStream)
            {
                anyVideoMediaStream = null;
            }
        }
    }

    /**
     * @return one of the {@link MediaStream} instances which use this
     * {@link TransportCCEngine}, or null.
     */
    private MediaStream getMediaStream()
    {
        synchronized (mediaStreams)
        {
            return mediaStreams.isEmpty() ? null : mediaStreams.get(0);
        }
    }

    /**
     * {@link PacketDetail} is an object that holds the
     * length(size) of the packet in {@link #packetLength}
     * and the time stamps of the outgoing packet
     * in {@link #packetSendTimeMs}
     */
    private class PacketDetail
    {
        int packetLength;
        long packetSendTimeMs;

        PacketDetail(int length, long time)
        {
            packetLength = length;
            packetSendTimeMs = time;
        }
    }

    /**
     * Handles outgoing RTP packets for this {@link TransportCCEngine}.
     */
    public class EgressEngine
            extends SinglePacketTransformerAdapter
            implements TransformEngine
    {
        /**
         * Ctor.
         */
        private EgressEngine()
        {
            super(RTPPacketPredicate.INSTANCE);
        }

        /**
         * {@inheritDoc}
         * <p></p>
         * If the transport-cc extension is configured, update the
         * transport-wide sequence number (adding a new extension if one doesn't
         * exist already).
         */
        @Override
        public RawPacket transform(RawPacket pkt)
        {
//            if (extensionId != -1)
            {
//                RawPacket.HeaderExtension ext
//                    = pkt.getHeaderExtension((byte) extensionId);
                RawPacket.HeaderExtension ext
                        = pkt.getHeaderExtension((byte) 5);
                if (ext == null)
                {
//                    ext = pkt.addExtension((byte) extensionId, 2);
                    return pkt;
                }
//                int seq = outgoingSeq.getAndIncrement() & 0xffff;
                int seq = RTPUtils.readUint16AsInt(
                        ext.getBuffer(), ext.getOffset() + 1);
//                RTPUtils.writeShort(
//                    ext.getBuffer(),
//                    ext.getOffset() + 1,
//                    (short) seq);
//
//                if (timeSeriesLogger.isTraceEnabled())
//                {
//                    timeSeriesLogger.trace(diagnosticContext
//                            .makeTimeSeriesPoint("egress_tcc_pkt")
//                            .addField("rtp_seq", pkt.getSequenceNumber())
//                            .addField("pt", RawPacket.getPayloadType(pkt))
//                            .addField("tcc_seq", seq));
//                }

                synchronized (sentPacketsSyncRoot)
                {
                    long now = System.currentTimeMillis();
//                    logger.info("Sent packet " + seq + " at " + now);
                    sentPacketDetails.put(seq, new PacketDetail(
                                pkt.getLength(),
                                now));
                }
            }
            return pkt;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public PacketTransformer getRTPTransformer()
        {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public PacketTransformer getRTCPTransformer()
        {
            return null;
        }
    }


    /**
     * Handles incoming RTP packets for this {@link TransportCCEngine}.
     */
    public class IngressEngine
            extends SinglePacketTransformerAdapter
            implements TransformEngine
    {
        /**
         * Ctor.
         */
        private IngressEngine()
        {
            super(RTPPacketPredicate.INSTANCE);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket reverseTransform(RawPacket pkt)
        {
            if (extensionId != -1)
            {
                RawPacket.HeaderExtension he
                    = pkt.getHeaderExtension((byte) extensionId);
                if (he != null && he.getExtLength() == 2)
                {
                    int seq = RTPUtils.readUint16AsInt(
                            he.getBuffer(), he.getOffset() + 1);
                    packetReceived(
                            seq,
                            RawPacket.getPayloadType(pkt),
                            pkt.isPacketMarked());
                }
            }
            return pkt;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public PacketTransformer getRTPTransformer()
        {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public PacketTransformer getRTCPTransformer()
        {
            return null;
        }
    }
}
