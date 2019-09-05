/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import org.jetbrains.annotations.*;
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.*;
import org.jitsi.nlj.rtcp.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.*;

import java.util.*;

/**
 * Implements transport-cc functionality.
 *
 * See https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01
 *
 * @author Boris Grozev
 * @author Julian Chukwu
 * @author George Politis
 *
 */
public class TransportCCEngine
    implements RemoteBitrateObserver, RtcpListener
{
    /**
     * The maximum number of received packets and their timestamps to save.
     *
     * XXX this is an uninformed value.
     */
    private static final int MAX_OUTGOING_PACKETS_HISTORY = 1000;

    /**
     * The {@link Logger} used by this instance for logging output.
     */
    private final Logger logger;

    /**
     * Used to synchronize access to {@link #sentPacketDetails}.
     */
    private final Object sentPacketsSyncRoot = new Object();

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
     * convenience.
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
    public TransportCCEngine(
            @NotNull DiagnosticContext diagnosticContext,
            RemoteBitrateObserver remoteBitrateObserver,
            @NotNull Logger parentLogger)
    {
        logger = parentLogger.createChildLogger(getClass().getName());
        this.remoteBitrateObserver = remoteBitrateObserver;
        bitrateEstimatorAbsSendTime
            = new RemoteBitrateEstimatorAbsSendTime(this, diagnosticContext, logger);
    }

    /**
     * Called when an RTP sender has a new round-trip time estimate.
     */
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

    @Override
    public void rtcpPacketReceived(RtcpPacket rtcpPacket, long receivedTime)
    {
        if (rtcpPacket instanceof RtcpFbTccPacket)
        {
            tccReceived((RtcpFbTccPacket) rtcpPacket);
        }
    }

    private void tccReceived(RtcpFbTccPacket tccPacket)
    {
        if (remoteReferenceTimeMs == -1)
        {
            remoteReferenceTimeMs = tccPacket.GetBaseTimeUs() / 1000;
            localReferenceTimeMs = System.currentTimeMillis();
        }
        double currArrivalTimestampMs = tccPacket.GetBaseTimeUs() / 1000.0;

        for (ReceivedPacket receivedPacket : tccPacket)
        {
            int tccSeqNum = receivedPacket.getSeqNum();
            double deltaMs = receivedPacket.getDeltaTicks() / 4.0;
            currArrivalTimestampMs += deltaMs;

            PacketDetail packetDetail;
            synchronized (sentPacketsSyncRoot)
            {
                packetDetail = sentPacketDetails.remove(tccSeqNum);
            }

            if (packetDetail == null)
            {
                logger.warn("Couldn't find packet detail for " + tccSeqNum + ".");
                continue;
            }

            long arrivalTimeMsInLocalClock
                    = (long) currArrivalTimestampMs - remoteReferenceTimeMs
                        + localReferenceTimeMs;
            long sendTime24bitsInLocalClock
                    = RemoteBitrateEstimatorAbsSendTime
                        .convertMsTo24Bits(packetDetail.packetSendTimeMs);

            bitrateEstimatorAbsSendTime.incomingPacketInfo(
                    arrivalTimeMsInLocalClock,
                    sendTime24bitsInLocalClock,
                    packetDetail.packetLength,
                    tccPacket.getMediaSourceSsrc()
            );
        }
    }

    public void mediaPacketSent(int tccSeqNum, int length)
    {
        synchronized (sentPacketsSyncRoot)
        {
            long now = System.currentTimeMillis();
            sentPacketDetails.put(
                    tccSeqNum & 0xFFFF,
                    new PacketDetail(length, now));
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
}
