/*
 * Copyright @ 2019-Present 8x8, Inc
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
package org.jitsi.videobridge.shim;

import org.jetbrains.annotations.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

import java.time.*;
import java.util.*;

/**
 * Represents a Colibri {@code channel}.
 *
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class ChannelShim
{
    /**
     * The {@link Logger}
     */
    private final Logger logger;

    /**
     * Gets the {@link SsrcAssociationType} corresponding to a given
     * {@code semantics} string (from SDP).
     *
     * @param sourceGroupSemantics the semantics signaled in SDP/Colibri
     */
    private static SsrcAssociationType getAssociationType(
            String sourceGroupSemantics)
    {
        if (sourceGroupSemantics.equalsIgnoreCase(
                SourceGroupPacketExtension.SEMANTICS_FID))
        {
            return SsrcAssociationType.RTX;
        }
        else if (sourceGroupSemantics.equalsIgnoreCase(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST))
        {
            return SsrcAssociationType.SIM;
        }
        else if (sourceGroupSemantics.equalsIgnoreCase(
                SourceGroupPacketExtension.SEMANTICS_FEC))
        {
            return SsrcAssociationType.FEC;
        }
        return null;
    }

    /**
     * Parses an XML extension element into an {@link RtpExtension}. Returns
     * {@code null} on failure.
     *
     * @param ext the XML extension to parse.
     * @return the created {@link RtpExtension} or {@code null}.
     */
    static RtpExtension createRtpExtension(RTPHdrExtPacketExtension ext)
    {
        String uri = ext.getURI().toString();
        RtpExtensionType type = RtpExtensionType.Companion.createFromUri(uri);
        if (type == null)
        {
            return null;
        }

        return new RtpExtension(Byte.valueOf(ext.getID()), type);
    }

    /**
     * The ID of this channel.
     */
    private final String id;

    /**
     * This channel's endpoint.
     */
    private final Endpoint endpoint;

    /**
     * The bridge's ssrc for this channel
     */
    private final long localSsrc;

    /**
     * This channel's direction from the perspective of the bridge.
     * We expect it to be one of 'inactive', 'sendonly', 'recvonly', or
     * 'sendrecv'.
     */
    private String direction = "sendrecv";

    /**
     * The sources that were signaled for this channel.
     */
    private Collection<SourcePacketExtension> sources;

    /**
     * The source groups that were signaled for this channel.
     */
    private Collection<SourceGroupPacketExtension> sourceGroups;

    /**
     * The expire value for this channel.
     */
    private int expire;

    /**
     * Whether this channel has been expired.
     */
    private boolean expired = false;

    /**
     * The time this channel was created.
     */
    private final long creationTimestampMs;

    /**
     * This channel's content.
     */
    private final ContentShim contentShim;

    /**
     * Initializes a new {@link ChannelShim} instance.
     *
     * @param id the ID of the new channel.
     * @param endpoint the endpoint of the new channel.
     * @param localSsrc the local SSRC for this channel.
     * @param contentShim the parent content of the channel.
     */
    public ChannelShim(
            @NotNull String id,
            @NotNull Endpoint endpoint,
            long localSsrc,
            ContentShim contentShim,
            Logger parentLogger)
    {
        this.id = id;
        this.endpoint = endpoint;
        this.localSsrc = localSsrc;
        this.contentShim = contentShim;
        this.creationTimestampMs = System.currentTimeMillis();
        this.logger = parentLogger.createChildLogger(ChannelShim.class.getName());
        endpoint.addChannel(this);
    }

    /**
     * Gets this channel's creation timestamp.
     * @return
     */
    public Instant getCreationTimestamp()
    {
        return Instant.ofEpochMilli(creationTimestampMs);
    }

    /**
     * @return this's channel's {@code expire} value.
     */
    public int getExpire()
    {
        return expire;
    }

    /**
     * Sets this channel's {@code expire} value (and expires it if the value is
     * 0).
     *
     * @param expire the value to set.
     */
    public void setExpire(int expire)
    {
        this.expire = expire;
        if (expire == 0)
        {
            expired = true;
            endpoint.removeChannel(this);
            contentShim.removeChannel(this);
        }
    }

    /**
     * Checks whether this channel is expired.
     * @return
     */
    public boolean isExpired()
    {
        return expired;
    }

    /**
     * Sets the {@code lastN} value for this channel.
     * @param lastN the value to set.
     */
    public void setLastN(Integer lastN)
    {
        // Since only a single channel (the video channel) should have the
        // lastN value set, we don't worry about overriding the singular lastN
        // value on the endpoint
        endpoint.setLastN(lastN);
    }

    /**
     * Describes this channel in an IQ.
     */
    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        commonIq.setID(id);
        commonIq.setEndpoint(endpoint.getId());
        // I don't think we even support not being the initiator at this point,
        // so hard-coding this
        commonIq.setInitiator(true);
        // Elsewhere we enforce that endpoint ID == channel bundle ID
        commonIq.setChannelBundleId(endpoint.getId());
        if (commonIq instanceof ColibriConferenceIQ.Channel)
        {
            ColibriConferenceIQ.Channel iq
                    = (ColibriConferenceIQ.Channel) commonIq;
            iq.setRTPLevelRelayType(RTPLevelRelayType.TRANSLATOR);
            iq.setLastN(endpoint.getLastN());
            iq.setDirection(direction);

            if (localSsrc != -1)
            {
                SourcePacketExtension bridgeSource = new SourcePacketExtension();
                bridgeSource.setSSRC(localSsrc);
                iq.addSource(bridgeSource);
            }

            if (sources != null) {
                int[] ssrcs = sources.stream()
                        .map(SourcePacketExtension::getSSRC)
                        .filter(ssrc -> ssrc != -1L)
                        .mapToInt(Long::intValue)
                        .toArray();

                iq.setSSRCs(ssrcs);
            }

            if (sourceGroups != null) {
                sourceGroups.forEach(iq::addSourceGroup);
            }
        }
    }

    /**
     * Sets the list of sources signaled for this channel.
     */
    public void setSources(@NotNull List<SourcePacketExtension> sources)
    {
        this.sources = sources;
        sources.forEach(s -> {
            endpoint.addReceiveSsrc(s.getSSRC(), getMediaType());
        });
    }

    /**
     * Sets the list of source groups signaled for this channel.
     */
    public void setSourceGroups(List<SourceGroupPacketExtension> sourceGroups)
    {
        this.sourceGroups = sourceGroups;
        if (sourceGroups != null)
        {
            sourceGroups.forEach(sourceGroup -> {
                List<SourcePacketExtension> sources = sourceGroup.getSources();
                if (sources.size() < 2)
                {
                    logger.warn(
                        "Ignoring source group with <2 sources: "
                                + sourceGroup.toXML());
                    return;
                }
                long primarySsrc = sources.get(0).getSSRC();
                long secondarySsrc = sources.get(1).getSSRC();

                SsrcAssociationType ssrcAssociationType
                        = getAssociationType(sourceGroup.getSemantics());
                if (ssrcAssociationType != null &&
                        ssrcAssociationType != SsrcAssociationType.SIM)
                {
                    endpoint.getConference().encodingsManager
                        .addSsrcAssociation(
                                endpoint.getId(),
                                primarySsrc,
                                secondarySsrc,
                                ssrcAssociationType);
                }
            });
        }
    }

    /**
     * @return this channel's media type.
     */
    public MediaType getMediaType()
    {
        return contentShim.getMediaType();
    }

    /**
     * Adds a set of payload types to this channel.
     * @param payloadTypes
     */
    public void addPayloadTypes(
            Collection<PayloadTypePacketExtension> payloadTypes)
    {
        MediaType mediaType = getMediaType();
        payloadTypes.forEach(ext -> {
            PayloadType pt = PayloadTypeUtil.create(ext, mediaType);
            if (pt == null)
            {
                logger.warn("Unrecognized payload type " + ext.toXML());
            }
            else
            {
                logger.debug(() -> "Adding a payload type to endpoint=" + endpoint.getId() + ": " + pt);

                // Note that we never clear the endpoint's list of payload
                // types. They just accumulate (but they can be replaced). This
                // is intentional, because it simplifies the implementation
                // (since Colibri can update just one of an endpoint's channel
                // we would need to store the payload types per-media-type),
                // and it works for all currently known use-cases. The same
                // goes for RTP header extension mappings.
                // If we have a use-case which needs clearing, we need to update
                // this code.
                endpoint.addPayloadType(pt);
            }
        });
    }

    /**
     * Adds a set of RTP header extensions to this channel.
     * @param rtpHeaderExtensions
     */
    public void addRtpHeaderExtensions(
            Collection<RTPHdrExtPacketExtension> rtpHeaderExtensions)
    {
        // Like for payload types, we never clear the transceiver's list of RTP
        // header extensions. See the note in #addPayloadTypes.
        rtpHeaderExtensions.forEach(ext -> {
            RtpExtension rtpExtension = createRtpExtension(ext);
            if (rtpExtension != null)
            {
                endpoint.addRtpExtension(rtpExtension);
            }
            else
            {
                logger.warn("Ignoring unknown RTP extension type: " + ext.getURI());
            }
        });
    }

    /**
     * Checks if this media channel allows media to be sent through it (from
     * the perspective of the bridge).
     */
    public boolean allowsSendingMedia()
    {
        return "sendrecv".equalsIgnoreCase(direction) ||
            "sendonly".equalsIgnoreCase(direction);
    }

    /**
     * Checks if incoming media (from the endpoint to the bridge) is being
     * forcibly "muted"
     * @return true if media for this channel is allowed, false if it should
     * be forcibly "muted" (dropped)
     */
    public boolean allowIncomingMedia()
    {
        return "sendrecv".equalsIgnoreCase(direction) ||
            "recvonly".equalsIgnoreCase(direction);

    }

    /**
     * Sets the media direction of this channel.
     * @param direction the direction to set.
     */
    public void setDirection(String direction)
    {
        if (!Objects.equals(this.direction, direction))
        {
            this.direction = direction;
            this.endpoint.updateAcceptedMediaTypes();
            this.endpoint.updateForceMute();
        }
    }

    /**
     * @return the list of sources that were signaled to this channel.
     */
    public Collection<SourcePacketExtension> getSources()
    {
        return sources;
    }

    /**
     * @return the list of source groups that were signaled to this channel.
     */
    public Collection<SourceGroupPacketExtension> getSourceGroups()
    {
        return sourceGroups;
    }

    /**
     * @return This {@link ChannelShim}'s endpoint.
     */
    Endpoint getEndpoint()
    {
        return endpoint;
    }

    /**
     * @return the ID of this channel.
     */
    String getId()
    {
        return id;
    }
}
