/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rtcp;

import net.sf.fmj.media.rtp.*;

import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtcp.termination.strategies.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;

/**
 * This class extends the <tt>BasicRTCPTerminationStrategy</tt> to make it work
 * with features found exclusively in the video bridge like, for example, lastN
 * and simulcast.
 *
 * @author George Politis
 */
public class BasicBridgeRTCPTerminationStrategy
    extends BasicRTCPTerminationStrategy
    implements BridgeRTCPTerminationStrategy
{
    /**
     * The <tt>Conference</tt> associated to this
     * <tt>BasicBridgeRTCPTerminationStrategy</tt>
     */
    private Conference conference;

    /**
     * The <tt>BridgeSenderReporting</tt> responsible for sender reporting in
     * the bridge.
     */
    private final BridgeSenderReporting bridgeSenderReporting;

    /**
     * The <tt>BridgeReceiverReporting</tt> responsible for receiver reporting in
     * the bridge.
     */
    private final BridgeReceiverReporting bridgeReceiverReporting;

    /**
     * Constructor.
     */
    public BasicBridgeRTCPTerminationStrategy()
    {
        this.bridgeSenderReporting = new BridgeSenderReporting(this);
        this.bridgeReceiverReporting = new BridgeReceiverReporting(this);
    }

    /**
     * Sets the <tt>Conference</tt> associated to this
     * <tt>BridgeRTCPTerminationStrategy</tt>
     *
     * @param conference The <tt>Conference</tt> associated to this
     * <tt>BridgeRTCPTerminationStrategy</tt>
     */
    @Override
    public void setConference(Conference conference)
    {
        this.conference = conference;
    }

    /**
     * Gets the <tt>Conference</tt> associated to this
     * <tt>BridgeRTCPTerminationStrategy</tt>
     *
     * @return The <tt>Conference</tt> associated to this
     * <tt>BridgeRTCPTerminationStrategy</tt>
     */
    @Override
    public Conference getConference()
    {
        return this.conference;
    }

    /**
     * Implements
     * {@link org.jitsi.service.neomedia.RTCPPacketTransformer#transformRTCPPacket(net.sf.fmj.media.rtp.RTCPCompoundPacket)}
     *
     * 1. Removes receiver report blocks from RRs and SRs and kills REMBs.
     * 2. Updates the receiver feedback cache.
     * 3. "Explodes" sender reports.
     *
     * @param inPacket the incoming RTCP packet to transform.
     * @return the transformed RTCP packet. If no transformations were made,
     * the method returns the input packet. If the packet is to be dropped,
     * the method returns null.
     */
    @Override
    public RTCPCompoundPacket transformRTCPPacket(
            RTCPCompoundPacket inPacket)
    {
        // Intercept REMBs and forward them to the VideoChannel logic
        for (RTCPPacket p : inPacket.packets)
        {
            if (p != null && p.type == RTCPFBPacket.PSFB)
            {
                RTCPFBPacket psfb = (RTCPFBPacket) p;
                if (psfb.fmt == RTCPREMBPacket.FMT)
                {
                    receivedREMB((RTCPREMBPacket) psfb);
                }
            }
        }

        // Call the super method that:
        //
        // 1. Removes receiver report blocks from RRs and SRs and kills REMBs.
        // 2. Updates the receiver feedback cache.

        RTCPCompoundPacket outPacket = super.transformRTCPPacket(inPacket);

        if (outPacket.packets != null
            && outPacket.packets.length != 0
            && outPacket.packets[0].type == RTCPPacket.SR)
        {
            // 3. This is a sender report, pass it on to the bridge sender
            // reporting for "explosion".
            if (bridgeSenderReporting.explodeSenderReport(outPacket))
            {
                return null;
            }
            else
            {
                // "Explosion" failed, send as is.
                return outPacket;
            }
        }
        else
        {
            // Not an SR, don't touch.
            return outPacket;
        }
    }

    /**
     * Sends RRs using data from FMJ and and REMBs using data from our own
     * remote bitrate estimator.
     *
     * @return null
     */
    @Override
    public RTCPPacket[] makeReports()
    {
        return bridgeReceiverReporting.makeReports();
    }

    /**
     * Handles a received REMB packet by passing its bitrate to the appropriate
     * <tt>VideoChannel</tt>.
     * @param remb the REMB packet that was received.
     */
    private void receivedREMB(RTCPREMBPacket remb)
    {
        Conference conference = getConference();
        if (conference != null)
        {
            Channel channel
                    = conference.findChannelByReceiveSSRC(remb.senderSSRC,
                                                          MediaType.VIDEO);
            if (channel != null && channel instanceof VideoChannel)
            {
                ((VideoChannel) channel).receivedREMB(remb.getBitrate());
            }
        }
    }
}
