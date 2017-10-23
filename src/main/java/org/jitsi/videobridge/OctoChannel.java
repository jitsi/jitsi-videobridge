/*
 * Copyright @ 2017 Atlassian Pty Ltd
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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.octo.*;

import java.net.*;
import java.util.*;

/**
 * Represents an Octo channel, used for bridge-to-bridge communication.
 *
 * @author Boris Grozev
 */
public class OctoChannel
    extends RtpChannel
{
    /**
     * The {@link Logger} used by the {@link RtpChannel} class to print debug
     * information. Note that instances should use {@link #logger} instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(OctoChannel.class);

    /**
     * The Octo ID of the conference, configured in {@link Conference} as the
     * global ID ({@link Conference#getGid()}).
     */
    private final String conferenceId;

    /**
     * The {@link MediaType} for this channel.
     */
    private final MediaType mediaType;

    /**
     * The {@link TransportManager} if this channel.
     */
    private OctoTransportManager transportManager;

    /**
     * The {@link org.ice4j.socket.DatagramPacketFilter} which (only) accepts
     * RTP packets for this channel.
     */
    private RtpChannelDatagramFilter rtpFilter
        = new OctoDatagramPacketFilter(false);

    /**
     * The {@link org.ice4j.socket.DatagramPacketFilter} which (only) accepts
     * RTCP packets for this channel.
     */
    private RtpChannelDatagramFilter rtcpFilter
        = new OctoDatagramPacketFilter(true);

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Initializes a new <tt>Channel</tt> instance which is to have a specific
     * ID. The initialization is to be considered requested by a specific
     * <tt>Content</tt>.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param id the ID of the new instance. It is expected to be unique
     * within the
     * list of <tt>Channel</tt>s listed in <tt>content</tt> while the new
     * instance
     * is listed there as well.
     * @throws Exception if an error occurs while initializing the new instance
     */
    public OctoChannel(Content content, String id)
        throws Exception
    {
        super(
                content, id, null /*channelBundleId*/,
                OctoTransportManager.NAMESPACE, false /*initiator*/);

        conferenceId = content.getConference().getGid();
        mediaType = content.getMediaType();
        logger
            = Logger.getLogger(classLogger, content.getConference().getLogger());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        super.describe(commonIq);

        // TODO: uncomment after the mvn versions are updated
        //commonIq.setType(ColibriConferenceIQ.OctoChannel.TYPE);
    }

    /**
     * @return The Octo ID of the conference.
     */
    public String getConferenceId()
    {
        return conferenceId;
    }

    /**
     * Sets the list of remote Octo relays that this channel should transmit to.
     * @param relayIds the list of strings which identify a remote relay.
     */
    public void setRelayIds(List<String> relayIds)
    {
        OctoTransportManager transportManager = getOctoTransportManager();

        transportManager.setRelayIds(relayIds);
    }

    /**
     * @return The transport manager of this {@link OctoChannel} as a
     * {@link OctoTransportManager} instance.
     */
    private OctoTransportManager getOctoTransportManager()
    {
        if (transportManager == null)
        {
            transportManager = (OctoTransportManager) getTransportManager();
        }
        return transportManager;
    }

    /**
     * @return the {@link MediaType} of this channel.
     */
    public MediaType getMediaType()
    {
        return mediaType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RtpChannelDatagramFilter getDatagramFilter(boolean rtcp)
    {
        return rtcp ? rtcpFilter : rtpFilter;
    }

    /**
     * TODO: this should be in a utility somewhere.
     * @param data
     * @param off
     * @param len
     * @return
     */
    private boolean isRTCP(byte[] data, int off, int len)
    {
        // The shortest RTCP packet (an empty RR) is 8 bytes long, RTP packets
        // are at least 12 bytes long (due to the fixed header).
        if (len >= 8)
        {
            if (((data[off] & 0xc0) >> 6) == 2) // RTP/RTCP version field
            {
                int pt = data[off + 1] & 0xff;

                if (200 <= pt && pt <= 211)
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void configureStream(MediaStream stream)
    {
        // Prevent things like retransmission requests to be enabled.
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This is called by the {@link MediaStream}'s input thread
     * ({@link org.jitsi.impl.neomedia.RTPConnectorInputStream}, either RTP or
     * RTCP), after if has already {@code receive()}ed a packet from its
     * socket. The flow of this thread is:
     * <pre>
     * 1. Call {@code receive()} on the
     * {@link org.ice4j.socket.MultiplexedDatagramSocket}
     *     1.1. The socket's {@link org.ice4j.socket.DatagramPacketFilter}s are
     *     applied (i.e. the associated {@link OctoDatagramPacketFilter}) to
     *     potential packets to be accepted.
     *     1.2. {@code receive()} only returns when a matching packet is
     *     available.
     * 2. The {@link org.jitsi.impl.neomedia.RTPConnectorInputStream}'s
     * datagram packet filters are applied, and this is where
     * {@link #acceptDataInputStreamDatagramPacket} executes. If any of the
     * filters reject the packet, it is dropped.
     * 3. The packet is converted to a {@link RawPacket} and passed through the
     * {@link MediaStream}'s transform chain.
     * </pre>
     */
    @Override
    protected boolean acceptDataInputStreamDatagramPacket(DatagramPacket p)
    {
        super.acceptDataInputStreamDatagramPacket(p);

        // super touches only if it determined that it should accept.
        touch(ActivityType.PAYLOAD /* data received */);
        return true;
    }

    /**
     * {@inheritDoc}
     * <p/>
     * See {@link #acceptDataInputStreamDatagramPacket(DatagramPacket)}.
     */
    @Override
    protected boolean acceptControlInputStreamDatagramPacket(DatagramPacket p)
    {
        super.acceptControlInputStreamDatagramPacket(p);

        // super touches only if it determined that it should accept.
        touch(ActivityType.PAYLOAD /* data received */);
        return true;
    }

    /**
     * Implements a {@link org.ice4j.socket.DatagramPacketFilter} which accepts
     * only RTP (or only RTCP) packets for this specific {@link OctoChannel}.
     */
    private class OctoDatagramPacketFilter
        extends RtpChannelDatagramFilter
    {
        /**
         * Whether to accept RTCP or RTP.
         */
        private boolean rtcp;

        /**
         * Initializes a new {@link OctoDatagramPacketFilter} instance.
         * @param rtcp whether to accept RTCP or RTP.
         */
        private OctoDatagramPacketFilter(
            boolean rtcp)
        {
            super(OctoChannel.this, rtcp);
            this.rtcp = rtcp;
        }

        /**
         * This filter accepts only packets for its associated
         * {@link OctoChannel}. A packet is accepted if the conference ID from
         * the Octo header matches the ID of the channel's conference, and the
         * channel's filter itself accepts it (based on the configured Payload
         * Type numbers and SSRCs). The second part is needed because we expect
         * a conference to have two Octo channels -- one for audio and one for
         * video.
         * @param p the <tt>DatagramPacket</tt> which is assumed to contain an
         * Octo packet and is to be accepted (or not) by this filter.
         * </p>
         * Note that this is meant to work on Octo packets, not RTP packets.
         * @return {@code true} if the packet should be accepted, and
         * {@code false} otherwise.
         */
        @Override
        public boolean accept(DatagramPacket p)
        {
            String packetCid
                = OctoPacket.readConferenceId(
                        p.getData(), p.getOffset(), p.getLength());
            if (!packetCid.equals(conferenceId))
            {
                return false;
            }

            MediaType packetMediaType
                = OctoPacket.readMediaType(
                        p.getData(), p.getOffset(), p.getLength());
            if (packetMediaType != mediaType)
            {
                return false;
            }

            // The RTP/RTCP packet is preceded by the fixed length Octo header
            boolean packetIsRtcp
                = isRTCP(
                        p.getData(),
                        p.getOffset() + OctoPacket.OCTO_HEADER_LENGTH,
                        p.getLength() - OctoPacket.OCTO_HEADER_LENGTH);

            // Note that the rtp socket gets all non-rtcp
            return rtcp == packetIsRtcp;
        }
    }
}
