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

/**
 * Created by gp on 22/07/14.
 */
public class HighestQualityBridgeRTCPTerminationStrategy
        extends BasicBridgeRTCPTerminationStrategy
{
    /**
     * The cache processor that will be making the RTCP reports coming from
     * the bridge.
     */
    private FeedbackCacheProcessor feedbackCacheProcessor;

    @Override
    public RTCPPacket[] makeReports()
    {
        // Uses the cache processor to make the RTCP reports.

        RTPTranslator t = this.translator;
        if (t == null || !(t instanceof RTPTranslatorImpl))
            return new RTCPPacket[0];

        long localSSRC = ((RTPTranslatorImpl)t).getLocalSSRC(null);

        if (this.feedbackCacheProcessor == null)
        {
            this.feedbackCacheProcessor
                    = new FeedbackCacheProcessor(feedbackCache);

            // TODO(gp) make percentile configurable.
            this.feedbackCacheProcessor.setPercentile(70);
        }

        RTCPPacket[] packets = feedbackCacheProcessor.makeReports(
                (int) localSSRC);

        return packets;
    }
}
