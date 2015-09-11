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
import org.jitsi.videobridge.rewriting.*;
import org.jitsi.videobridge.rtcp.*;

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
     * The name of the property used to disable NACK termination.
     */
    private static final String DISABLE_NACK_TERMINATION_PNAME
        = "org.jitsi.videobridge.DISABLE_NACK_TERMINATION";

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
     * The transformer which caches outgoing RTP packets.
     */
    private CachingTransformer cache;

    /**
     * The transformer which intercepts NACK packets and passes them on to the
     * channel logic.
     */
    private NACKNotifier nackNotifier;

    /**
     * The transformer which intercepts REMB packets and passes them on to the
     * channel logic.
     */
    private REMBNotifier rembNotifier;

    /**
     * The transformer which replaces the timestamp in an abs-send-time RTP
     * header extension.
     */
    private AbsSendTimeEngine absSendTime;

    /**
     * The <tt>RetransmissionRequester</tt> instance, if any, used by the
     * <tt>RtpChannel</tt>.
     */
    private RetransmissionRequester retransmissionRequester;

    /**
     * The transformer which handles SSRC rewriting.
     */
    private SsrcRewritingEngine ssrcRewritingEngine;

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

        List<TransformEngine> transformerList
            = new LinkedList<TransformEngine>();

        if (cfg == null
                || !cfg.getBoolean(DISABLE_RETRANSMISSION_REQUESTS, false))
        {
            retransmissionRequester = new RetransmissionRequester(channel);
            transformerList.add(retransmissionRequester);
            if (logger.isDebugEnabled())
            {
                logger.debug("Enabling retransmission requests for channel"
                                     + channel.getID());
            }
        }

        redFilter = new REDFilterTransformEngine(RED_PAYLOAD_TYPE);
        transformerList.add(redFilter);

        absSendTime = new AbsSendTimeEngine();
        transformerList.add(absSendTime);

        boolean enableNackTermination = true;
        if (conference != null)
        {
            if (cfg != null)
                enableNackTermination
                    = !cfg.getBoolean(DISABLE_NACK_TERMINATION_PNAME, false);
        }

        if (enableNackTermination && channel instanceof NACKHandler)
        {
            logger.info("Enabling NACK termination for channel "
                                + channel.getID());
            cache = new CachingTransformer();
            transformerList.add(cache);

            nackNotifier = new NACKNotifier((NACKHandler) channel);
            transformerList.add(nackNotifier);
        }

        if (channel instanceof VideoChannel)
        {
            VideoChannel videoChannel = (VideoChannel) channel;
            rembNotifier = new REMBNotifier(videoChannel);
            transformerList.add(rembNotifier);
            ssrcRewritingEngine = new SsrcRewritingEngine(videoChannel);
            transformerList.add(ssrcRewritingEngine);
        }

        return
            transformerList.toArray(new TransformEngine[transformerList.size()]);
    }

    /**
     * Enables replacement of the timestamp in abs-send-time RTP header
     * extensions with the given extension ID.
     * @param extensionID the ID of the RTP header extension.
     */
    public void enableAbsSendTime(int extensionID)
    {
        if (absSendTime != null)
            absSendTime.setExtensionID(extensionID);
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
     * Returns the cache of outgoing packets.
     * @return the cache of outgoing packets.
     */
    public RawPacketCache getCache()
    {
        return cache;
    }

    /**
     * Enables SSRC re-writing.
     *
     * @param enabled whether to enable or disable.
     */
    public void enableSsrcRewriting(boolean enabled)
    {
        if (ssrcRewritingEngine != null)
            ssrcRewritingEngine.setEnabled(enabled);
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
     * Gets a boolean value indicating whether SSRC re-writing is enabled or
     * not.
     *
     * @return a boolean value indicating whether SSRC re-writing is enabled or
     * not.
     */
    public boolean isSsrcRewritingEnabled()
    {
        return ssrcRewritingEngine.isEnabled();
    }
}
