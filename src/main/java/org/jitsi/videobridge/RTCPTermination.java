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
package org.jitsi.videobridge;

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi.util.concurrent.*;
import org.jitsi.util.function.*;
import org.jitsi.videobridge.transform.*;

import javax.media.rtp.*;
import java.util.*;

/**
 * TODO move to LJ.
 *
 * @author George Politis
 */
public class RTCPTermination
    extends RTCPPacketListenerAdapter
    implements RecurringRunnable
{
    /**
     * The maximum number of RTCP report blocks that an RR can contain.
     */
    private static final int MAX_RTCP_REPORT_BLOCKS = 31;

    /**
     * The minimum number of RTCP report blocks that an RR can contain.
     */
    private static final int MIN_RTCP_REPORT_BLOCKS = 0;

    /**
     * The reporting period for RRs and REMBs.
     */
    private static final long REPORT_PERIOD_MS = 500;

    /**
     * The generator that generates <tt>RawPacket</tt>s from
     * <tt>RTCPCompoundPacket</tt>s.
     */
    private final RTCPGenerator generator = new RTCPGenerator();

    /**
     * A reusable array that holds {@link #MIN_RTCP_REPORT_BLOCKS}
     * <tt>RTCPReportBlock</tt>s.
     *
     * FIXME this should be somewhere else, probably inside the
     * RTCPReportBlock class.
     */
    private static final RTCPReportBlock[] MIN_RTCP_REPORT_BLOCKS_ARRAY
        = new RTCPReportBlock[MIN_RTCP_REPORT_BLOCKS];

    /**
     * The {@link Logger} used by the {@link RTCPTermination} class to print
     * debug information.
     */
    private static final Logger logger
        = Logger.getLogger(RTCPTermination.class);

    /**
     * The {@link RtpChannel} that owns this instance.
     */
    private final RtpChannel dstChannel;

    /**
     * The time (in millis) that this instance was last "run".
     */
    private long lastRunMs = -1;

    /**
     * Ctor.
     *
     * @param dstChannel the {@link RtpChannel} that owns this instance.
     */
    RTCPTermination(RtpChannel dstChannel)
    {
        this.dstChannel = dstChannel;

        MediaStream stream = dstChannel.getStream();
        RawPacketCache cache = stream.getPacketCache();
        if (cache != null)
        {
            cache.setEnabled(true);
        }
        else
        {
            logger.warn("NACK termination is enabled, but we don't have" +
                " a packet cache.");
        }

        stream.getMediaStreamStats().addRTCPPacketListener(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void firReceived(FIRPacket firPacket)
    {
        dstChannel.askForKeyframes(new int[] { (int) firPacket.senderSSRC });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void pliReceived(PLIPacket pliPacket)
    {
        dstChannel.askForKeyframes(new int[] { (int) pliPacket.senderSSRC });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void nackReceived(NACKPacket nackPacket)
    {
        long ssrc = nackPacket.sourceSSRC;
        Set<Integer> lostPackets = new TreeSet<>(nackPacket.getLostPackets());

        if (logger.isDebugEnabled())
        {
            logger.debug(Logger.Category.STATISTICS,
                "nack_received," + dstChannel.getLoggingId()
                    + " ssrc=" + ssrc
                    + ",lost_packets=" + lostPackets);
        }

        RawPacketCache cache;
        RtxTransformer rtxTransformer;
        MediaStream stream = dstChannel.getStream();

        if (stream != null && (cache = stream.getPacketCache()) != null
            && (rtxTransformer = dstChannel.getTransformEngine().getRtxTransformer())
            != null)
        {
            // XXX The retransmission of packets MUST take into account SSRC
            // rewriting. Which it may do by injecting retransmitted packets
            // AFTER the SsrcRewritingEngine.
            // Also, the cache MUST be notified of packets being retransmitted,
            // in order for it to update their timestamp. We do this here by
            // simply letting retransmitted packets pass through the cache again.
            // We use the retransmission requester here simply because it is
            // the transformer right before the cache, not because of anything
            // intrinsic to it.
            RetransmissionRequester rr = stream.getRetransmissionRequester();
            TransformEngine after
                = (rr instanceof TransformEngine) ? (TransformEngine) rr : null;

            long rtt = stream.getMediaStreamStats().getSendStats().getRtt();
            long now = System.currentTimeMillis();

            for (Iterator<Integer> i = lostPackets.iterator(); i.hasNext();)
            {
                int seq = i.next();
                RawPacketCache.Container container
                    = cache.getContainer(ssrc, seq);


                if (container != null)
                {
                    // Cache hit.
                    long delay = now - container.timeAdded;
                    boolean send = (rtt == -1) ||
                        (delay >= Math.min(rtt * 0.9, rtt - 5));

                    if (logger.isDebugEnabled())
                    {
                        logger.debug(Logger.Category.STATISTICS,
                            "retransmitting," + dstChannel.getLoggingId()
                                + " ssrc=" + ssrc
                                + ",seq=" + seq
                                + ",send=" + send);
                    }

                    if (send && rtxTransformer.retransmit(container.pkt, after))
                    {
                        dstChannel.statistics.packetsRetransmitted.incrementAndGet();
                        dstChannel.statistics.bytesRetransmitted.addAndGet(
                            container.pkt.getLength());
                        i.remove();
                    }

                    if (!send)
                    {
                        dstChannel.statistics.packetsNotRetransmitted.incrementAndGet();
                        dstChannel.statistics.bytesNotRetransmitted.addAndGet(
                            container.pkt.getLength());
                        i.remove();
                    }

                }
                else
                {
                    dstChannel.statistics.packetsMissingFromCache.incrementAndGet();
                }
            }
        }

        if (!lostPackets.isEmpty())
        {
            // If retransmission requests are enabled, videobridge assumes
            // the responsibility of requesting missing packets.
            logger.debug("Packets missing from the cache.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getTimeUntilNextRun()
    {
        return (lastRunMs + REPORT_PERIOD_MS) - System.currentTimeMillis();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run()
    {
        lastRunMs = System.currentTimeMillis();

        // RRs
        RTCPRRPacket[] rrs = makeRRs(lastRunMs);

        // Bail out (early) if we have nothing to report.
        if (ArrayUtils.isNullOrEmpty(rrs))
        {
            return;
        }

        // REMB
        RTCPREMBPacket remb = makeREMB();

        // Build the RTCPCompoundPackets to return.
        RTCPCompoundPacket[] compounds = compound(rrs, remb);

        // inject the packets into the MediaStream.
        for (int i = 0; i < compounds.length; i++)
        {
            RawPacket pkt = generator.apply(compounds[i]);

            try
            {
                dstChannel.getStream().injectPacket(pkt, false, null);
            }
            catch (TransmissionFailedException e)
            {
                logger.error("transmission of an RTCP packet failed.", e);
            }
        }
    }

    /**
     * Constructs a new {@code RTCPCompoundPacket}s out of specific SRs, RRs,
     * SDES, and other RTCP packets.
     *
     * @param rrs
     * @param others other {@code RTCPPacket}s to be included in the new
     * {@code RTCPCompoundPacket}
     * @return a new {@code RTCPCompoundPacket} consisting of the specified
     * {@code srs}, {@code rrs}, {@code sdes}, and {@code others}
     */
    private RTCPCompoundPacket[] compound(
        RTCPRRPacket[] rrs,
        RTCPPacket... others)
    {
        RTCPCompoundPacket[] compounds = new RTCPCompoundPacket[rrs.length];

        for (int j = 0; j < rrs.length; j++)
        {
            RTCPPacket[] rtcps = new RTCPPacket[others.length + 1];

            rtcps[0] = rrs[j];

            // RTCP packets other than SR, RR, and SDES such as REMB.
            if (j == 0 && others.length > 0)
            {
                for (int i = 0; i < others.length; ++i)
                {
                    RTCPPacket other = others[i];

                    if (other != null)
                    {
                        rtcps[1 + j] = other;
                        // We've included the current other in the current
                        // compound packet and we don't want to include it in
                        // subsequent compound packets.
                        others[i] = null;
                    }
                }
            }

            RTCPCompoundPacket compound = new RTCPCompoundPacket(rtcps);

            compounds[j] = compound;
        }

        return compounds;
    }

    /**
     * (attempts) to get the local SSRC that will be used in the media sender
     * SSRC field of the RTCP reports. TAG(cat4-local-ssrc-hurricane)
     *
     * @return
     */
    private long getLocalSSRC()
    {
        return dstChannel.getStream().getStreamRTPManager().getLocalSSRC();
    }


    /**
     * Makes <tt>RTCPRRPacket</tt>s using information in FMJ.
     *
     * @param time
     * @return A <tt>List</tt> of <tt>RTCPRRPacket</tt>s to inject into the
     * <tt>MediaStream</tt>.
     */
    private RTCPRRPacket[] makeRRs(long time)
    {
        RTCPReportBlock[] reportBlocks = makeReportBlocks(time);
        if (ArrayUtils.isNullOrEmpty(reportBlocks))
        {
            return null;
        }

        int mod = reportBlocks.length % MAX_RTCP_REPORT_BLOCKS;
        int div = reportBlocks.length / MAX_RTCP_REPORT_BLOCKS;

        RTCPRRPacket[] rrs = new RTCPRRPacket[mod == 0 ? div : div + 1];

        // We use the stream's local source ID (SSRC) as the SSRC of packet
        // sender.
        int streamSSRC = (int) getLocalSSRC();

        // Since a maximum of 31 reception report blocks will fit in an SR
        // or RR packet, additional RR packets SHOULD be stacked after the
        // initial SR or RR packet as needed to contain the reception
        // reports for all sources heard during the interval since the last
        // report.
        if (reportBlocks.length > MAX_RTCP_REPORT_BLOCKS)
        {
            int rrIdx = 0;
            for (int offset = 0;
                 offset < reportBlocks.length;
                 offset += MAX_RTCP_REPORT_BLOCKS)
            {
                int blockCount
                    = Math.min(
                    reportBlocks.length - offset,
                    MAX_RTCP_REPORT_BLOCKS);
                RTCPReportBlock[] blocks = new RTCPReportBlock[blockCount];

                System.arraycopy(
                    reportBlocks, offset,
                    blocks, 0,
                    blocks.length);

                rrs[rrIdx++] = new RTCPRRPacket(streamSSRC, blocks);
            }
        }
        else
        {
            rrs[0] = new RTCPRRPacket(streamSSRC, reportBlocks);
        }

        return rrs;
    }

    /**
     * Iterate through all the <tt>ReceiveStream</tt>s that this
     * <tt>MediaStream</tt> has and make <tt>RTCPReportBlock</tt>s for all of
     * them.
     *
     * @param time
     * @return
     */
    private RTCPReportBlock[] makeReportBlocks(long time)
    {
        MediaStream stream = dstChannel.getStream();
        // State validation.
        if (stream == null)
        {
            logger.warn("stream is null.");
            return MIN_RTCP_REPORT_BLOCKS_ARRAY;
        }

        StreamRTPManager streamRTPManager = stream.getStreamRTPManager();
        if (streamRTPManager == null)
        {
            logger.warn("streamRTPManager is null.");
            return MIN_RTCP_REPORT_BLOCKS_ARRAY;
        }

        Collection<ReceiveStream> receiveStreams;

        // XXX MediaStreamImpl's implementation of #getReceiveStreams() says
        // that, unfortunately, it has been observed that sometimes there are
        // valid ReceiveStreams in MediaStreamImpl which are not returned by
        // FMJ's RTPManager. Since (1) MediaStreamImpl#getReceiveStreams() will
        // include the results of StreamRTPManager#getReceiveStreams() and (2)
        // we are going to check the results against SSRCCache, it should be
        // relatively safe to rely on MediaStreamImpl's implementation.
        if (stream instanceof MediaStreamImpl)
        {
            receiveStreams = ((MediaStreamImpl) stream).getReceiveStreams();
        }
        else
        {
            receiveStreams = streamRTPManager.getReceiveStreams();
        }
        if (receiveStreams == null || receiveStreams.isEmpty())
        {
            logger.info(
                "There are no receive streams to build report blocks for.");
            return MIN_RTCP_REPORT_BLOCKS_ARRAY;
        }

        SSRCCache cache = streamRTPManager.getSSRCCache();
        if (cache == null)
        {
            logger.info("cache is null.");
            return MIN_RTCP_REPORT_BLOCKS_ARRAY;
        }

        // Create and populate the return object.
        Collection<RTCPReportBlock> reportBlocks = new ArrayList<>();

        for (ReceiveStream receiveStream : receiveStreams)
        {
            // Dig into the guts of FMJ and get the stats for the current
            // receiveStream.
            SSRCInfo info = cache.cache.get((int) receiveStream.getSSRC());

            if (info == null)
            {
                logger.warn("We have a ReceiveStream but not an SSRCInfo for " +
                    "that ReceiveStream.");
                continue;
            }
            if (!info.ours && info.sender)
            {
                RTCPReportBlock reportBlock = info.makeReceiverReport(time);
                reportBlocks.add(reportBlock);
            }
        }

        return reportBlocks.toArray(new RTCPReportBlock[reportBlocks.size()]);
    }

    /**
     * Makes an <tt>RTCPREMBPacket</tt> that provides receiver feedback to the
     * endpoint from which we receive.
     *
     * @return an <tt>RTCPREMBPacket</tt> that provides receiver feedback to the
     * endpoint from which we receive.
     */
    private RTCPREMBPacket makeREMB()
    {
        // TODO we should only make REMBs if REMB support has been advertised.
        // Destination
        RemoteBitrateEstimator remoteBitrateEstimator
            = ((VideoMediaStream) dstChannel.getStream()).getRemoteBitrateEstimator();

        Collection<Integer> ssrcs = remoteBitrateEstimator.getSsrcs();

        // TODO(gp) intersect with SSRCs from signaled simulcast layers
        // NOTE(gp) The Google Congestion Control algorithm (sender side)
        // doesn't seem to care about the SSRCs in the dest field.
        long[] dest = new long[ssrcs.size()];
        int i = 0;

        for (Integer ssrc : ssrcs)
            dest[i++] = ssrc & 0xFFFFFFFFL;

        // Create and return the packet.
        // We use the stream's local source ID (SSRC) as the SSRC of packet
        // sender.
        long streamSSRC = getLocalSSRC();

        // Exp & mantissa
        long bitrate = remoteBitrateEstimator.getLatestEstimate();

        if (logger.isDebugEnabled())
        {
            logger.debug(
                "Estimated bitrate (bps): " + bitrate + ", dest: "
                    + Arrays.toString(dest) + ", time (ms): "
                    + System.currentTimeMillis());
        }
        if (bitrate == -1)
        {
            return null;
        }
        else
        {
            return new RTCPREMBPacket(streamSSRC, 0L, bitrate, dest);
        }
    }
}
