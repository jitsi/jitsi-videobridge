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

import kotlin.*;
import org.jetbrains.annotations.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.rtp.remotebitrateestimator.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.rtp.rtcp.rtcpfb.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateEstimatorAbsSendTime;

import java.util.*;
import java.lang.Deprecated;

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
     * The list of {@link MediaStream} that are using this
     * {@link TransportCCEngine}.
     */
    private final List<MediaStream> mediaStreams = new LinkedList<>();

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

    private final RemoteBitrateObserver remoteBitrateObserver;

    /**
     * Ctor.
     *
     * @param diagnosticContext the {@link DiagnosticContext} of this instance.
     */
    public TransportCCEngine(@NotNull DiagnosticContext diagnosticContext, RemoteBitrateObserver remoteBitrateObserver)
    {
        this.diagnosticContext = diagnosticContext;
        this.remoteBitrateObserver = remoteBitrateObserver;
        bitrateEstimatorAbsSendTime
            = new RemoteBitrateEstimatorAbsSendTime(this, diagnosticContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRttUpdate(long avgRttMs, long maxRttMs)
    {
        bitrateEstimatorAbsSendTime.onRttUpdate(avgRttMs, maxRttMs);
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
        remoteBitrateObserver.onReceiveBitrateChanged(ssrcs, bitrate);
    }

    public void tccReceived(RtcpFbTccPacket tccPacket)
    {
        tccPacket.forEach((tccSeqNum, recvTimestamp) ->
        {
            if (recvTimestamp == -1)
            {
                return Unit.INSTANCE;
            }
            if (remoteReferenceTimeMs == -1)
            {
                remoteReferenceTimeMs = tccPacket.getReferenceTimeMs();
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
            long delta = recvTimestamp - tccPacket.getReferenceTimeMs();
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
    @Deprecated
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
     * //TODO(brian): we still send outgoing packets through this in tccseqnumtagger, but we don't actually need
     * this class anymore, we can change things to have tccseqnumtagger call the transportccengine method directly
     * (instead of going through this EgressEngine)
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
            //TODO(brian): plumb through the extension ID instead of hard-coding it
            RawPacket.HeaderExtension ext
                    = pkt.getHeaderExtension((byte) 5);
            if (ext == null)
            {
                return pkt;
            }
            int seq = RTPUtils.readUint16AsInt(
                    ext.getBuffer(), ext.getOffset() + 1);

            synchronized (sentPacketsSyncRoot)
            {
                long now = System.currentTimeMillis();
                sentPacketDetails.put(seq, new PacketDetail(
                            pkt.getLength(),
                            now));
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
