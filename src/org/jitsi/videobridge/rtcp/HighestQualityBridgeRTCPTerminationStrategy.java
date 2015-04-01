/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rtcp;

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.rtcp.termination.strategies.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

/**
 * @author George Politis
 */
@Deprecated
public class HighestQualityBridgeRTCPTerminationStrategy
        extends AbstractBridgeRTCPTerminationStrategy
{
    private static final Logger logger =
            Logger.getLogger(HighestQualityBridgeRTCPTerminationStrategy.class);

    /**
     * The cache processor that will be making the RTCP reports coming from
     * the bridge.
     */
    private final FeedbackCacheProcessor feedbackCacheProcessor;

    /**
     * A cache of media receiver feedback. It contains both receiver report
     * blocks and REMB packets.
     */
    private final FeedbackCache feedbackCache;

    public HighestQualityBridgeRTCPTerminationStrategy()
    {
        logger.warn("This RTCP termination strategy is deprecated and should" +
                "not be used!");

        this.feedbackCache = new FeedbackCache();
        this.feedbackCacheProcessor
                = new FeedbackCacheProcessor(feedbackCache);

        // TODO(gp) make percentile configurable.
        this.feedbackCacheProcessor.setPercentile(70);

        setTransformerChain(new Transformer[]{
                new REMBNotifier(this),
                new FeedbackCacheUpdater(feedbackCache),
                new ReceiverFeedbackFilter(),
                new SenderFeedbackExploder(this)
        });
    }

    @Override
    public RTCPPacket[] makeReports()
    {
        // Uses the cache processor to make the RTCP reports.

        RTPTranslator t = this.getRTPTranslator();
        if (t == null || !(t instanceof RTPTranslatorImpl))
            return new RTCPPacket[0];

        long localSSRC = ((RTPTranslatorImpl)t).getLocalSSRC(null);

        RTCPPacket[] packets = feedbackCacheProcessor.makeReports(
                (int) localSSRC);

        return packets;
    }
}
