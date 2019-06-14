/*
 * Copyright @ 2018 - present 8x8, Inc.
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
package org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation;

import org.jetbrains.annotations.*;
import org.jitsi.impl.neomedia.rtp.remotebitrateestimator.*;
import org.jitsi.rtp.rtcp.*;

import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;

import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging.*;
import org.jitsi_modified.service.neomedia.rtp.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Implements part of the send-side bandwidth estimation described in
 * https://tools.ietf.org/html/draft-ietf-rmcat-gcc-01
 * Heavily based on code from webrtc.org (bitrate_controller_impl.cc, commit ID
 * 7ad9e661f8a035d49d049ccdb87c77ae8ecdfa35).
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class BandwidthEstimatorImpl
    implements BandwidthEstimator, RecurringRunnable, RemoteBitrateObserver
{
    /**
     * The system property name of the initial value of the estimation, in bits
     * per second.
     */
    public static final String START_BITRATE_BPS_PNAME = "org.jitsi.impl" +
        ".neomedia.rtp.sendsidebandwidthestimation.BandwidthEstimatorImpl" +
        ".START_BITRATE_BPS";

    /**
     * The minimum value to be output by this estimator, in bits per second.
     */
    private final static int MIN_BITRATE_BPS = 30000;

    /**
     * The maximum value to be output by this estimator, in bits per second.
     */
    private final static int MAX_BITRATE_BPS = 20 * 1000 * 1000;

    /**
     * The ConfigurationService to get config values from.
     */
    private static final ConfigurationService cfg
        = LibJitsi.getConfigurationService();

    /**
     * The initial value of the estimation, in bits per second.
     */
    private static final long START_BITRATE_BPS
        = cfg != null ? cfg.getLong(START_BITRATE_BPS_PNAME, 300000) : 300000;

    /**
     * bitrate_controller_impl.h
     */
    private Map<Long,Long> ssrc_to_last_received_extended_high_seq_num_
        = new ConcurrentHashMap<>();

    private long lastUpdateTime = -1;

    /**
     * bitrate_controller_impl.h
     */
    private final SendSideBandwidthEstimation sendSideBandwidthEstimation;

    /**
     * Initializes a new instance which is to belong to a particular
     * {@link MediaStream}.
     */
    public BandwidthEstimatorImpl(DiagnosticContext diagnosticContext)
    {
        sendSideBandwidthEstimation
            = new SendSideBandwidthEstimation(diagnosticContext, START_BITRATE_BPS);
        sendSideBandwidthEstimation.setMinMaxBitrate(
                MIN_BITRATE_BPS, MAX_BITRATE_BPS);
    }

    public void rtcpReportBlocksReceived(Collection<RtcpReportBlock> reportBlocks)
    {
        long total_number_of_packets = 0;
        long fraction_lost_aggregate = 0;
        for (RtcpReportBlock reportBlock : reportBlocks)
        {
            long ssrc = reportBlock.getSsrc();
            long extSeqNum = reportBlock.getExtendedHighestSeqNum();
            long lastEHSN = ssrc_to_last_received_extended_high_seq_num_
                    .getOrDefault(ssrc, extSeqNum);
            ssrc_to_last_received_extended_high_seq_num_
                    .put(ssrc, Math.max(lastEHSN, extSeqNum));

            if (lastEHSN >= extSeqNum)
            {
                //the first report for this SSRC
                continue;
            }
            long number_of_packets = extSeqNum - lastEHSN;
            fraction_lost_aggregate += number_of_packets * reportBlock.getFractionLost();
            total_number_of_packets += number_of_packets;
        }
        if (total_number_of_packets == 0)
        {
            fraction_lost_aggregate = 0;
        }
        else
        {
            fraction_lost_aggregate = (fraction_lost_aggregate +
                    total_number_of_packets / 2) / total_number_of_packets;
        }
        if (fraction_lost_aggregate > 255)
        {
            return;
        }
        synchronized (sendSideBandwidthEstimation)
        {
            lastUpdateTime = System.currentTimeMillis();
            sendSideBandwidthEstimation.updateReceiverBlock(
                    fraction_lost_aggregate,
                    total_number_of_packets,
                    lastUpdateTime);
        }
    }

    @Override
    public void addListener(Listener listener)
    {
        sendSideBandwidthEstimation.addListener(listener);
    }

    @Override
    public void removeListener(Listener listener)
    {
        sendSideBandwidthEstimation.removeListener(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLatestEstimate()
    {
        return sendSideBandwidthEstimation.getLatestEstimate();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLatestREMB()
    {
        return sendSideBandwidthEstimation.getLatestREMB();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateReceiverEstimate(long bandwidth)
    {
        sendSideBandwidthEstimation.updateReceiverEstimate(bandwidth);
    }

    @Override
    public void onRttUpdate(double newRtt)
    {
        sendSideBandwidthEstimation.onRttUpdate(newRtt);
    }

    @Override
    public void rtcpPacketReceived(@NotNull RtcpPacket packet, long receivedTime)
    {
        if (packet instanceof RtcpSrPacket ||
                packet instanceof RtcpRrPacket)
        {
            if (packet instanceof RtcpSrPacket)
            {
                RtcpSrPacket srPacket = (RtcpSrPacket)packet;
                rtcpReportBlocksReceived(srPacket.getReportBlocks());
            }
            else
            {
                RtcpRrPacket rrPacket = (RtcpRrPacket)packet;
                rtcpReportBlocksReceived(rrPacket.getReportBlocks());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getLatestFractionLoss()
    {
        return sendSideBandwidthEstimation.getLatestFractionLoss();
    }

    /**
     * @return the send-side bwe-specific statistics.
     */
    @Override
    public SendSideBandwidthEstimation.StatisticsImpl getStatistics()
    {
        return sendSideBandwidthEstimation.getStatistics();
    }

    @Override
    public long getTimeUntilNextRun()
    {
        long timeSinceLastProcess
            = Math.max(System.currentTimeMillis() - lastUpdateTime, 0);

        return Math.max(25 - timeSinceLastProcess, 0);
    }

    @Override
    public void run()
    {
        synchronized (sendSideBandwidthEstimation)
        {
            lastUpdateTime = System.currentTimeMillis();
            sendSideBandwidthEstimation.updateEstimate(lastUpdateTime);
        }
    }

    /**
     * This is hooked up to the
     * {@link org.jitsi.service.neomedia.rtp.RemoteBitrateEstimator} in
     * {@link org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine}, which
     * performs the delay-based estimation (also referred to as
     * "receiver estimate").
     *
     */
    @Override
    public void onReceiveBitrateChanged(
            Collection<Long> ssrcs, long delayBasedEstimateBps)
    {
        updateReceiverEstimate(delayBasedEstimateBps);
    }
}
