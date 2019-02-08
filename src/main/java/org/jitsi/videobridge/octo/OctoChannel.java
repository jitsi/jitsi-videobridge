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
package org.jitsi.videobridge.octo;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.xmpp.*;

import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.stream.*;

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
     * Expiration timeout for Octo channels.
     */
    private static final int OCTO_EXPIRE = 2 * 60 * 60 * 1000;

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
        = new OctoDatagramPacketFilter(false /* rtcp */);

    /**
     * The {@link org.ice4j.socket.DatagramPacketFilter} which (only) accepts
     * RTCP packets for this channel.
     */
    private RtpChannelDatagramFilter rtcpFilter
        = new OctoDatagramPacketFilter(true /* rtcp */);

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * The {@link OctoEndpoints} instance which manages the conference
     * {@link AbstractEndpoint}s associated with this channel.
     * The audio and video {@code Octo} channels should have the same set of
     * endpoints, but since the endpoints of the audio channel a
     *
     */
    private final OctoEndpoints octoEndpoints;

    /**
     * Whether this channel should handle Octo data packets. We always have
     * the Octo video channel handle data packets, while the Octo audio channel
     * ignores them.
     */
    private final boolean handleData;

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
     */
    public OctoChannel(Content content, String id)
    {
        super(
                content, id, null /*channelBundleId*/,
                OctoTransportManager.NAMESPACE, false /*initiator*/);

        Conference conference = content.getConference();
        conferenceId = conference.getGid();
        mediaType = content.getMediaType();

        octoEndpoints = conference.getOctoEndpoints();
        octoEndpoints.setChannel(mediaType, this);

        // We are going to use one of the threads already reading from the
        // socket to handle data packets. Since both the audio and the video
        // channel's filters will be given the packet, we want to accept it only
        // in one of them. We chose to accept in the video channel.
        handleData = MediaType.VIDEO.equals(mediaType);

        logger
            = Logger.getLogger(classLogger, content.getConference().getLogger());

        setExpire(OCTO_EXPIRE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        super.describe(commonIq);

        commonIq.setType(ColibriConferenceIQ.OctoChannel.TYPE);
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

    @Override
    public boolean setRtpEncodingParameters(
        List<SourcePacketExtension> sources,
        List<SourceGroupPacketExtension> sourceGroups)
    {
        boolean changed = super.setRtpEncodingParameters(sources, sourceGroups);

        if (changed && octoEndpoints != null)
        {
            octoEndpoints.updateEndpoints(
                Arrays.stream(
                    getStream().getMediaStreamTrackReceiver()
                        .getMediaStreamTracks())
                    .map(MediaStreamTrackDesc::getOwner)
                    .collect(Collectors.toSet()));

            for (SourcePacketExtension s : sources)
            {
                if (MediaStreamTrackFactory.getOwner(s) == null)
                {
                    logger.warn("Received a source without an owner tag.");
                }
            }
        }

        return changed;
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
     * {@inheritDoc}
     */
    @Override
    protected void configureStream(MediaStream stream)
    {
        // Intentionally do not call super#configureStream in order to prevent
        // things like retransmission requests to be enabled.

        if (stream != null && stream instanceof AudioMediaStream)
        {
            ((AudioMediaStream) stream)
                .setCsrcAudioLevelListener(
                    new AudioChannelAudioLevelListener(this));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void removeStreamListeners()
    {
        MediaStream stream = getStream();
        if (stream != null && stream instanceof AudioMediaStream)
        {
            ((AudioMediaStream) stream).setCsrcAudioLevelListener(null);
        }
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
        private OctoDatagramPacketFilter(boolean rtcp)
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

            if (mediaType.equals(packetMediaType))
            {
                // The RTP/RTCP packet is preceded by the fixed length Octo
                // header.
                boolean packetIsRtcp
                    = RTCPUtils.isRtcp(
                        p.getData(),
                        p.getOffset() + OctoPacket.OCTO_HEADER_LENGTH,
                        p.getLength() - OctoPacket.OCTO_HEADER_LENGTH);

                // Note that the rtp socket gets all non-rtcp packets.
                return rtcp == packetIsRtcp;
            }

            if (MediaType.DATA.equals(packetMediaType) && handleData)
            {
                // In this case we're going to return 'false' anyway, because
                // we already read the contents and we don't want the packet to
                // be accepted by libjitsi.
                handleDataPacket(p);
            }

            return false;
        }
    }

    /**
     * Handles an incoming Octo packet of type {@code data}.
     */
    private void handleDataPacket(DatagramPacket p)
    {
        byte[] msgBytes = new byte[p.getLength() - OctoPacket.OCTO_HEADER_LENGTH];
        System.arraycopy(
            p.getData(), p.getOffset() + OctoPacket.OCTO_HEADER_LENGTH,
            msgBytes, 0,
            msgBytes.length);
        String msg = new String(msgBytes, StandardCharsets.UTF_8);

        if (logger.isDebugEnabled())
        {
            logger.debug("Received a message in an Octo data packet: " + msg);
        }

        octoEndpoints.messageTransport.onMessage(this, msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractEndpoint getEndpoint(long ssrc)
    {
        return octoEndpoints == null ? null : octoEndpoints.findEndpoint(ssrc);
    }

    /**
     * Sends a string message through the Octo transport.
     * @param msg the message to send
     * @param sourceEndpointId the ID of the source endpoint.
     */
    void sendMessage(String msg, String sourceEndpointId)
    {
        getOctoTransportManager()
            .sendMessage(msg, sourceEndpointId, getConferenceId());
    }

    /**
     * {@inheritDoc}
     * </p>
     * Updates the octo-specific fields.
     */
    @Override
    protected void updatePacketsAndBytes(
        Conference.Statistics conferenceStatistics)
    {
        if (conferenceStatistics != null)
        {
            conferenceStatistics.totalBytesReceivedOcto
                .addAndGet(statistics.bytesReceived);
            conferenceStatistics.totalBytesSentOcto
                .addAndGet(statistics.bytesSent);
            conferenceStatistics.totalPacketsReceivedOcto
                .addAndGet(statistics.packetsReceived);
            conferenceStatistics.totalPacketsSentOcto
                .addAndGet(statistics.packetsSent);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean expire()
    {
        if (super.expire())
        {
            octoEndpoints.setChannel(getMediaType(), null);
            return true;
        }

        return false;
    }

    /**
     * Don't expire Octo channels due to lack of transport activity, but allow
     * for them to be explicitly expired by signaling (by setting expire=0).
     */
    @Override
    public void setExpire(int expire)
    {
        if (expire > 0)
        {
            expire = Math.max(expire, OCTO_EXPIRE);
        }
        super.setExpire(expire);
    }
}
