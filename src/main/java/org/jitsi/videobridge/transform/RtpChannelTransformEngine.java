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
package org.jitsi.videobridge.transform;

import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rtcp.*;
import org.jitsi.videobridge.simulcast.*;

import java.util.*;

/**
 * Implements a <tt>TransformEngine</tt> for a specific <tt>RtpChannel</tt>.
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class RtpChannelTransformEngine
    extends TransformEngineChain
{
    /**
     * The payload type number for RED packets. We should set this dynamically
     * but it is not clear exactly how to do it, because it isn't used on this
     * <tt>RtpChannel</tt>, but on other channels from the <tt>Content</tt>.
     */
    private static final byte RED_PAYLOAD_TYPE = 116;

    /**
     * The name of the property used to disable retransmission requests from
     * the bridge.
     */
    public static final String DISABLE_RETRANSMISSION_REQUESTS
        = "org.jitsi.videobridge.DISABLE_RETRANSMISSION_REQUESTS";

    /**
     * The <tt>Logger</tt> used by the <tt>RtpChannelTransformEngine</tt> class
     * and its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(RtpChannelTransformEngine.class);

    /**
     * The <tt>RtpChannel</tt> associated with this transformer.
     */
    private final RtpChannel channel;

    /**
     * The transformer which strips RED encapsulation.
     */
    private REDFilterTransformEngine redFilter;

    /**
     * The transformer which intercepts RTCP packets and passes them on to the
     * channel logic.
     */
    private RTCPNotifier rtcpNotifier;

    /**
     * The <tt>RetransmissionRequester</tt> instance, if any, used by the
     * <tt>RtpChannel</tt>.
     */
    private RetransmissionRequester retransmissionRequester;

    /**
     * The <tt>SimulcastEngine</tt> instance, if any, used by the
     * <tt>RtpChannel</tt>.
     */
    private SimulcastEngine simulcastEngine;

    /**
     * The transformer which handles outgoing rtx (RFC-4588) packets for this
     * channel.
     */
    private RtxTransformer rtxTransformer;

    /**
     * Initializes a new <tt>RtpChannelTransformEngine</tt> for a specific
     * <tt>RtpChannel</tt>.
     * @param channel the <tt>RtpChannel</tt>.
     */
    public RtpChannelTransformEngine(RtpChannel channel)
    {
        this.channel = channel;

        this.engineChain = createChain();
    }

    /**
     * Initializes the transformers used by this instance and returns them as
     * an array.
     */
    private TransformEngine[] createChain()
    {
        Conference conference = channel.getContent().getConference();
        ConfigurationService cfg
                = conference.getVideobridge().getConfigurationService();

        boolean video = (channel instanceof VideoChannel);
        boolean enableNackTermination = video;
        boolean enableRetransmissionsRequests = video;

        if (cfg != null)
        {
            enableNackTermination
                &= !cfg.getBoolean(VideoChannel.DISABLE_NACK_TERMINATION_PNAME,
                                   false);
            enableRetransmissionsRequests
                &= !cfg.getBoolean(DISABLE_RETRANSMISSION_REQUESTS, false);
        }

        // Bridge-end (the first transformer in the list executes first for
        // packets from the bridge, and last for packets to the bridge)
        List<TransformEngine> transformerList
            = new LinkedList<TransformEngine>();

        if (video)
        {
            rtcpNotifier = new RTCPNotifier(channel);
            rtcpNotifier.setDropNackPackets(enableNackTermination);
            transformerList.add(rtcpNotifier);

            rtxTransformer = new RtxTransformer(channel);
            transformerList.add(rtxTransformer);

            if (enableRetransmissionsRequests)
            {
                retransmissionRequester = new RetransmissionRequester(channel);
                transformerList.add(retransmissionRequester);
                if (logger.isDebugEnabled())
                {
                    logger.debug("Enabling retransmission requests for channel"
                                     + channel.getID());
                }
            }

            VideoChannel videoChannel = (VideoChannel) channel;
            simulcastEngine = new SimulcastEngine(videoChannel);
            transformerList.add(simulcastEngine);

            redFilter = new REDFilterTransformEngine(RED_PAYLOAD_TYPE);
            transformerList.add(redFilter);
        }

        // Endpoint-end (the last transformer in the list executes last for
        // packets from the bridge, and first for packets to the bridge)

        return
            transformerList.toArray(new TransformEngine[transformerList.size()]);
    }

    /**
     * Enables stripping the RED encapsulation.
     * @param enabled whether to enable or disable.
     */
    public void enableREDFilter(boolean enabled)
    {
        if (redFilter != null)
            redFilter.setEnabled(enabled);
    }

    /**
     * Checks whether retransmission requests are enabled for the
     * <tt>RtpChannel</tt>.
     * @return <tt>true</tt> if retransmission requests are enabled for the
     * <tt>RtpChannel</tt>.
     */
    public boolean retransmissionsRequestsEnabled()
    {
        return retransmissionRequester != null;
    }

    /**
     * Gets the <tt>SimulcastEngine</tt> instance, if any, used by the
     * <tt>RtpChannel</tt>.
     *
     * @return the <tt>SimulcastEngine</tt> instance used by the
     * <tt>RtpChannel</tt>, or null.
     */
    public SimulcastEngine getSimulcastEngine()
    {
        return simulcastEngine;
    }

    public RtxTransformer getRtxTransformer()
    {
        return rtxTransformer;
    }
}
