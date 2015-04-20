/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.transform;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

/**
 * Implements a <tt>TransformEngine</tt> for a specific <tt>RtpChannel</tt>.
 *
 * @author Boris Grozev
 */
public class RtpChannelTransformEngine
    implements TransformEngine, PacketTransformer
{
    /**
     * The payload type number for RED packets. We should set this dynamically
     * but it is not clear exactly how to do it, because it isn't used on this
     * <tt>RtpChannel</tt>, but on other channels from the <tt>Content</tt>.
     */
    private static final byte RED_PAYLOAD_TYPE = 116;

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
     * The chain of <tt>PacketTransformer</tt>s which will be used to transform
     * outgoing packets.
     */
    private final PacketTransformer[] chain;

    /**
     * The transformer which strips RED encapsulation.
     */
    private final REDFilterTransformEngine redFilter;

    /**
     * The transformer which replaces the timestamp in an abs-send-time RTP
     * header extension.
     */
    private final AbsSendTimeEngine absSendTime;

    RtpChannelTransformEngine(RtpChannel channel)
    {
        this.channel = channel;

        redFilter = new REDFilterTransformEngine(RED_PAYLOAD_TYPE);
        absSendTime = new AbsSendTimeEngine();

        chain = new PacketTransformer[]{ redFilter, absSendTime};
    }

    /**
     * {@inheritDoc}
     * Implements
     * {@link org.jitsi.impl.neomedia.transform.TransformEngine#getRTPTransformer()}
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return this;
    }

    /**
     * {@inheritDoc}
     * Implements
     * {@link org.jitsi.impl.neomedia.transform.TransformEngine#getRTCPTransformer()}
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return null;
    }

    /**
     * {@inheritDoc}
     * Implements
     * {@link org.jitsi.impl.neomedia.transform.PacketTransformer#close()}
     */
    @Override
    public void close()
    {

    }

    /**
     * {@inheritDoc}
     * Implements
     * {@link org.jitsi.impl.neomedia.transform.PacketTransformer#reverseTransform(org.jitsi.impl.neomedia.RawPacket[])}
     */
    @Override
    public RawPacket[] reverseTransform(RawPacket[] pkts)
    {
        return pkts;
    }

    /**
     * {@inheritDoc}
     *
     * Transforms outgoing packets, by using each element of {@link #chain} in
     * order.
     */
    @Override
    public RawPacket[] transform(RawPacket[] pkts)
    {
        if (pkts == null)
            return pkts;

        for (PacketTransformer packetTransformer : chain)
        {
            if (packetTransformer != null)
                pkts = packetTransformer.transform(pkts);
        }

        return pkts;
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
        logger.warn("XXXX "+channel.getEndpoint().getID()+" AST ID="+extensionID);
    }

    /**
     * Enables stripping the RED encapsulation.
     * @param enabled whether to enable or disable.
     */
    public void enableREDFilter(boolean enabled)
    {
        if (redFilter != null)
            redFilter.setEnabled(enabled);
        logger.warn("XXXX "+channel.getEndpoint().getID()+" RED filter: " +enabled);
    }
}
