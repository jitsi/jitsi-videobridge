/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.transform;

import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rtcp.*;

import java.util.*;

/**
 * Implements a <tt>TransformEngine</tt> for a specific <tt>RtpChannel</tt>.
 *
 * @author Boris Grozev
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
     * The transformer which parses incoming RTCP packets.
     */
    private RTCPTransformEngine rtcpTransformEngine;

    /**
     * The transformer which intercepts NACK packets and passes them on to the
     * channel logic.
     */
    private NACKNotifier nackNotifier;

    /**
     * The transformer which replaces the timestamp in an abs-send-time RTP
     * header extension.
     */
    private AbsSendTimeEngine absSendTime;

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
        List<TransformEngine> transformerList
            = new LinkedList<TransformEngine>();

        redFilter = new REDFilterTransformEngine(RED_PAYLOAD_TYPE);
        transformerList.add(redFilter);

        absSendTime = new AbsSendTimeEngine();
        transformerList.add(absSendTime);

        boolean enableNackTermination = true;
        Conference conference = channel.getContent().getConference();
        if (conference != null)
        {
            ConfigurationService cfg
                    = conference.getVideobridge().getConfigurationService();
            if (cfg != null)
                enableNackTermination
                    = !cfg.getBoolean(DISABLE_NACK_TERMINATION_PNAME, false);
        }

        if (enableNackTermination && channel instanceof NACKHandler)
        {
            cache = new CachingTransformer();
            transformerList.add(cache);

            // Note: we use a separate RTCPTransformer here, instead of using
            // the RTCPTerminationStrategy, because interpreting RTCP NACK
            // packets should happen in the context of a specific channel, and
            // the RTCPTermination strategy is a single instance for a
            // conference. The current intention/idea is to eventually move
            // the RTCP parsing code from the RTCPTerminationStrategy here, so
            // that we only parse RTCP once, and so that the REMB/RR code
            // doesn't have to find the source Channel by SSRC.
            nackNotifier = new NACKNotifier((NACKHandler) channel);
            rtcpTransformEngine
                    = new RTCPTransformEngine(new Transformer[] {nackNotifier});
            transformerList.add(rtcpTransformEngine);
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

}
