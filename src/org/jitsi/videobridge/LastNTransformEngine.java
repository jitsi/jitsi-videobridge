/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import javax.media.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.*;

/**
 * Implements a <tt>TransformEngine</tt> for a <tt>VideoChannel</tt> which
 * &quot;drops&quot; the received RTP packets if the
 * <tt>VideoChannel</tt> is not in any <tt>VideoChannel</tt>/<tt>Endpoint</tt>'s
 * <tt>lastN</tt>.
 *
 * @author Lyubomir Marinov
 */
class LastNTransformEngine
    implements PacketTransformer, TransformEngine
{
    /**
     * The <tt>VideoChannel</tt> whose received RTP packets are to be
     * &quot;dropped&quot; by this instance.
     */
    private final VideoChannel videoChannel;

    /**
     * Initializes a new <tt>LastNTransformEngine</tt> instance which is to
     * &quot;drop&quot; the RTP packets received by a specific
     * <tt>VideoChannel</tt> if the <tt>VideoChannel</tt> is not in any
     * <tt>VideoChannel</tt>/<tt>Endpoint</tt>'s <tt>lastN</tt>.
     * 
     * @param videoChannel the <tt>VideoChannel</tt> whose received RTP packets
     * are to be &quot;dropped&quot; by the new instance
     */
    public LastNTransformEngine(VideoChannel videoChannel)
    {
        if (videoChannel == null)
            throw new NullPointerException("videoChannel");

        this.videoChannel = videoChannel;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        // TODO Auto-generated method stub
    }

    /**
     * {@inheritDoc}
     *
     * @return <tt>null</tt> because <tt>LastNTransformEngine</tt>
     * &quot;drops&quot; received RTP packets only
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return this;
    }

    /**
     * &quot;Drops&quot; the received RTP <tt>pkt</tt> because
     * {@link #videoChannel} is not in any
     * <tt>VideoChannel</tt>/<tt>Endpoint</tt>'s <tt>lastN</tt>.
     *
     * @param pkt the received RTP packet to drop
     * @return <tt>pkt</tt>'s <tt>RawPacket</tt> after &quot;dropping&quot; it
     */
    private RawPacket reverseTransform(RawPacket pkt)
    {
        pkt.setFlags(Buffer.FLAG_SILENCE | pkt.getFlags());
        return pkt;
    }

    /**
     * {@inheritDoc}
     *
     * &quot;Drops&quot; the received RTP <tt>pkts</tt> if {@link #videoChannel}
     * is not in any <tt>VideoChannel</tt>/<tt>Endpoint</tt>'s <tt>lastN</tt>.
     */
    @Override
    public RawPacket[] reverseTransform(RawPacket[] pkts)
    {
        if ((pkts != null) && (pkts.length != 0) && !videoChannel.isInLastN())
        {
            for (int i = 0; i < pkts.length; i++)
            {
                RawPacket oldPkt = pkts[i];

                if (oldPkt != null)
                {
                    RawPacket newPkt = reverseTransform(oldPkt);

                    if (newPkt != oldPkt)
                        pkts[i] = newPkt;
                }
            }
        }
        return pkts;
    }

    /**
     * {@inheritDoc}
     *
     * Does nothing because <tt>LastNTransformEngine</tt> &quot;drops&quot;
     * received RTP packets only.
     *
     * @return <tt>pkts</tt> without any modifications to the array and/or its
     * elements because <tt>LastNTransformEngine</tt> &quot;drops&quot; received
     * RTP packets only
     */
    @Override
    public RawPacket[] transform(RawPacket[] pkts)
    {
        return pkts;
    }
}
