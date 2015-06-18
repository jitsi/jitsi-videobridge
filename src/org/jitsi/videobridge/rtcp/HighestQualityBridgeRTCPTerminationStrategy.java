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
