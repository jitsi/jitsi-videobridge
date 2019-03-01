/*
 * Copyright @ 2015-2018 Atlassian Pty Ltd
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
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.rtp.rtcp.rtcpfb.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.shim.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi_modified.impl.neomedia.rtp.*;

import java.beans.*;
import java.io.*;
import java.lang.ref.*;
import java.util.*;

/**
 * Represents an endpoint in a conference (i.e. the entity associated with
 * a participant in the conference, which connects the participant's audio
 * and video channel). This might be an endpoint connected to this instance of
 * jitsi-videobridge, or a "remote" endpoint connected to another bridge in the
 * same conference (if Octo is being used).
 *
 * @author Boris Grozev
 * @author Brian Baldino
 */
public abstract class AbstractEndpoint extends PropertyChangeNotifier
    implements EncodingsManager.EncodingsUpdateListener, PropertyChangeListener
{
    public static final String ENDPOINT_CHANGED_PROPERTY_NAME =
            AbstractEndpoint.class.getName() + ".endpoint_changed";
    /**
     * The (unique) identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt>.
     */
    private final String id;

    /**
     * The {@link Logger} used by the {@link Endpoint} class to print debug
     * information.
     */
    private static final Logger classLogger
            = Logger.getLogger(AbstractEndpoint.class);

    /**
     * The instance logger.
     */
    private final Logger logger;

    /**
     * A reference to the <tt>Conference</tt> this <tt>Endpoint</tt> belongs to.
     */
    private final Conference conference;

    /**
     * The list of {@link ChannelShim}s associated with this endpoint. This
     * allows us to expire the endpoint once all of its 'channels' have been
     * removed.
     */
    protected final List<WeakReference<ChannelShim>> channelShims
            = new LinkedList<>();

    private final LastNFilter lastNFilter;

    /**
     * The (human readable) display name of this <tt>Endpoint</tt>.
     */
    private String displayName;

    /**
     * The statistic Id of this <tt>Endpoint</tt>.
     */
    private String statsId;

    /**
     * The indicator which determines whether {@link #expire()} has been called
     * on this <tt>Endpoint</tt>.
     */
    private boolean expired = false;

     /**
     * The string used to identify this endpoint for the purposes of logging.
     */
    protected final String logPrefix;

    /**
     * Public for now since the channel needs to reach in and grab it
     */
    public Transceiver transceiver;

    /**
     * Initializes a new {@link AbstractEndpoint} instance.
     * @param conference the {@link Conference} which this endpoint is to be a
     * part of.
     * @param id the ID of the endpoint.
     */
    protected AbstractEndpoint(Conference conference, String id)
    {
        this.conference = Objects.requireNonNull(conference, "conference");
        logPrefix
            = "[id=" + id + " conference=" + conference.getID() + "] ";
        logger = Logger.getLogger(classLogger, conference.getLogger());
        this.id = Objects.requireNonNull(id, "id");
        this.lastNFilter = new LastNFilter(id);
        transceiver
                = new Transceiver(
                        id,
                        TaskPools.CPU_POOL,
                        TaskPools.CPU_POOL,
                        TaskPools.SCHEDULED_POOL,
                        logger);
        transceiver.setIncomingRtpHandler(
                new ConsumerNode("RTP receiver chain handler")
        {
            @Override
            protected void consume(@NotNull PacketInfo packetInfo)
            {
                handleIncomingRtp(packetInfo);
            }
        });
        transceiver.setIncomingRtcpHandler(
                new ConsumerNode("RTCP receiver chain handler")
        {
            @Override
            public void consume(@NotNull PacketInfo packetInfo)
            {
                handleIncomingRtcp(packetInfo);
            }
        });
        conference.encodingsManager.subscribe(this);
        conference.addPropertyChangeListener(this);
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt)
    {
    }

    @Override
    public void onNewSsrcAssociation(
            String endpointId,
            long primarySsrc,
            long secondarySsrc,
            SsrcAssociationType type)
    {
        transceiver.addSsrcAssociation(primarySsrc, secondarySsrc, type);
    }

    void speechActivityEndpointsChanged(List<String> endpoints)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(logPrefix +
                    "Got notified about active endpoints: " + endpoints);
        }
        lastNFilter.setEndpointsSortedByActivity(endpoints);
    }

    public void setLastN(Integer lastN)
    {
        lastNFilter.setLastNValue(lastN);
    }

    public void setMaxReceiveFrameHeightPx(int maxReceiveFrameHeightPx) { }

    public Integer getLastN()
    {
        return lastNFilter.getLastNValue();
    }

    public void addPayloadType(PayloadType payloadType)
    {
        transceiver.addPayloadType(payloadType);
    }

    public void setLocalSsrc(MediaType mediaType, long ssrc) {}

    //TODO(brian): the nice thing about having 'wants' as a pre-check before we forward a packet is that if
    // 'wants' returns false, we don't have to make a copy of the packet to forward down.  If, instead, we
    // had a scheme where we pass a packet reference and the egress pipeline copies once it decides the packet should
    // be accepted (or, even better, a packet is automatically copied on first modification) then we could
    // avoid this separate call and just have the pipeline
    public boolean wants(PacketInfo packetInfo, String sourceEndpointId)
    {
        // We always want audio packets
        if (packetInfo.getPacket() instanceof AudioRtpPacket)
        {
            return true;
        }
        // Video packets require more checks:
        // First check if this endpoint fits in lastN
        //TODO(brian): this doesn't take into account adaptive-last-n
        return lastNFilter.wants(sourceEndpointId);
    }

    /**
     * Handle incoming RTP packets which have been fully processed by the
     * transceiver's incoming pipeline.
     * @param packetInfo the packet.
     */
    protected void handleIncomingRtp(PacketInfo packetInfo)
    {
        // For now, just write every packet to every channel other than ourselves
        getConference().getEndpointsFast().forEach(endpoint -> {
            if (endpoint == this)
            {
                return;
            }
            //TODO(brian): we don't use a copy when passing to 'wants', which makes sense, but it would be nice
            // to be able to enforce a 'read only' version of the packet here so we can guarantee nothing is
            // changed in 'wants'
            if (endpoint.wants(packetInfo, getID()))
            {
                PacketInfo pktInfoCopy = packetInfo.clone();
                endpoint.sendRtp(pktInfoCopy);
            }
        });
    }

    public void sendRtp(PacketInfo packetInfo)
    {
        // By default just add it to the sender's queue
        transceiver.sendRtp(packetInfo);
    }

    protected void handleIncomingRtcp(PacketInfo packetInfo)
    {
        // We don't need to copy RTCP packets for each dest like we do with RTP because each one
        // will only have a single destination
        getConference().getEndpoints().forEach(endpoint -> {
            if (packetInfo.getPacket() instanceof RtcpFbPacket)
            {
                RtcpFbPacket rtcpPacket = (RtcpFbPacket) packetInfo.getPacket();
                if (endpoint.receivesSsrc(rtcpPacket.getMediaSourceSsrc()))
                {
                    endpoint.transceiver.sendRtcp(rtcpPacket);
                }
            }
        });
    }

    public boolean receivesSsrc(long ssrc)
    {
        return transceiver.receivesSsrc(ssrc);
    }

    public void addReceiveSsrc(long ssrc)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(logPrefix + "Adding receive ssrc " + ssrc);
        }
        transceiver.addReceiveSsrc(ssrc);
    }

    /**
     * @return the {@link AbstractEndpointMessageTransport} associated with
     * this endpoint.
     */
    public AbstractEndpointMessageTransport getMessageTransport()
    {
        return null;
    }

    public void addChannel(ChannelShim channelShim)
    {
        synchronized (channelShims)
        {
            channelShims.add(new WeakReference<>(channelShim));
        }
    }

    public void removeChannel(ChannelShim channelShim)
    {
        synchronized (channelShims)
        {
            for (Iterator<WeakReference<ChannelShim>> i = channelShims.iterator(); i.hasNext();)
            {
                ChannelShim existingChannelShim = i.next().get();
                if (existingChannelShim != null && existingChannelShim.equals(channelShim)) {
                    i.remove();
                }
            }
            if (channelShims.isEmpty())
            {
                expire();
            }
        }
    }

    private void setMediaStreamTracks(MediaStreamTrackDesc[] mediaStreamTracks)
    {
        transceiver.setMediaStreamTracks(mediaStreamTracks);
        firePropertyChange(ENDPOINT_CHANGED_PROPERTY_NAME, null, null);
    }

    public MediaStreamTrackDesc[] getMediaStreamTracks()
    {
        return transceiver.getMediaStreamTracks();
    }

    /**
     * Returns the display name of this <tt>Endpoint</tt>.
     *
     * @return the display name of this <tt>Endpoint</tt>.
     */
    public String getDisplayName()
    {
        return displayName;
    }

    /**
     * Returns the stats Id of this <tt>Endpoint</tt>.
     *
     * @return the stats Id of this <tt>Endpoint</tt>.
     */
    public String getStatsId()
    {
        return statsId;
    }

    /**
     * Gets the (unique) identifier/ID of this instance.
     *
     * @return the (unique) identifier/ID of this instance
     */
    public final String getID()
    {
        return id;
    }

    /**
     * Gets the <tt>Conference</tt> to which this <tt>Endpoint</tt> belongs.
     *
     * @return the <tt>Conference</tt> to which this <tt>Endpoint</tt> belongs.
     */
    public Conference getConference()
    {
        return conference;
    }

    /**
     * Checks whether or not this <tt>Endpoint</tt> is considered "expired"
     * ({@link #expire()} method has been called).
     *
     * @return <tt>true</tt> if this instance is "expired" or <tt>false</tt>
     * otherwise.
     */
    public boolean isExpired()
    {
        return expired;
    }

    /**
     * Sets the display name of this <tt>Endpoint</tt>.
     *
     * @param displayName the display name to set on this <tt>Endpoint</tt>.
     */
    public void setDisplayName(String displayName)
    {
        this.displayName = displayName;
    }

    /**
     * Sets the stats Id of this <tt>Endpoint</tt>.
     *
     * @param value the stats Id value to set on this <tt>Endpoint</tt>.
     */
    public void setStatsId(String value)
    {
        this.statsId = value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return getClass().getName() + " " + getID();
    }

    /**
     * Expires this {@link AbstractEndpoint}.
     */
    public void expire()
    {
        logger.info(logPrefix + "Expiring.");
        this.expired = true;
        this.transceiver.stop();
        logger.info(transceiver.getNodeStats().prettyPrint(0));

        transceiver.teardown();

        Conference conference = getConference();
        if (conference != null)
        {
            conference.removePropertyChangeListener(this);
            conference.endpointExpired(this);
        }
    }

    /**
     * Return true if this endpoint should expire (based on whatever logic is
     * appropriate for that endpoint implementation.
     *
     * NOTE(brian): Currently the bridge will automatically expire an endpoint
     * if all of its channel shims are removed. Maybe we should instead have
     * this logic always be called before expiring instead? But that would mean
     * that expiration in the case of channel removal would take longer.
     *
     * @return true if this endpoint should expire, false otherwise
     */
    public abstract boolean shouldExpire();

    /**
     * Get the last 'activity' (packets received or packets sent) this endpoint has seen
     * @return the timestamp, in milliseconds, of the last activity of this endpoint
     */
    public long getLastActivity()
    {
        return 0;
    }

    /**
     * @return the {@link Set} of pinned endpoints, represented as a set of
     * endpoint IDs.
     */
    public Set<String> getPinnedEndpoints()
    {
        return Collections.EMPTY_SET;
    }

    /**
     * Sends a specific {@link String} {@code msg} to the remote end of this
     * endpoint.
     *
     * @param msg message text to send.
     */
    public abstract void sendMessage(String msg)
        throws IOException;

    /**
     * Notify this endpoint that another endpoint has set it
     * as a 'selected' endpoint, meaning its HD stream has another
     * consumer.
     */
    public void incrementSelectedCount()
    {
        // No-op
    }

    /**
     * Notify this endpoint that another endpoint has stopped consuming
     * its HD stream.
     */
    public void decrementSelectedCount()
    {
        // No-op
    }

    /**
     * Recreates this {@link AbstractEndpoint}'s media stream tracks based
     * on the sources (and source groups) described in it's video channel.
     */
    public void recreateMediaStreamTracks()
    {
        ChannelShim videoChannel = getChannelOfMediaType(MediaType.VIDEO);
        if (videoChannel != null)
        {
            MediaStreamTrackDesc[] tracks =
                    MediaStreamTrackFactory.createMediaStreamTracks(
                            videoChannel.getSources(),
                            videoChannel.getSourceGroups());
            setMediaStreamTracks(tracks);
        }
    }

    /**
     * Gets this {@link AbstractEndpoint}'s channel of media type
     * {@code mediaType} (although it's not strictly enforced, endpoints have
     * at most one channel with a given media type).
     *
     * @param mediaType the media type of the channel.
     *
     * @return the channel.
     */
    private ChannelShim getChannelOfMediaType(MediaType mediaType)
    {
        return
            channelShims.stream()
                    .filter(Objects::nonNull)
                    .map(WeakReference::get)
                    .filter(
                        channel ->
                            channel != null &&
                            channel.getMediaType().equals(mediaType))
                    .findAny().orElse(null);

    }

    /**
     * Describes this endpoint's transport in the given channel bundle XML
     * element.
     *
     * @param channelBundle the channel bundle element to describe in.
     */
    public void describe(ColibriConferenceIQ.ChannelBundle channelBundle)
            throws IOException
    {
    }
}
