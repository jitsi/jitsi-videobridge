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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

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
     * The {@link Logger} used by the {@link VideobridgeShim} class and its
     * instances to print debug information.
     */
    private static final Logger logger =
            Logger.getLogger(VideobridgeShim.class);

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
     * The ID of this channel.
     */
    private final String id;

    /**
     * This channel's endpoint.
     */
    private final AbstractEndpoint endpoint;

    /**
     * The bridge's ssrc for this channel
     */
    private final long localSsrc;

    /**
     * This channel's direction.
     */
    private MediaDirection direction = MediaDirection.SENDRECV;

    /**
     * The sources that were signaled for this channel.
     */
    private Collection<SourcePacketExtension> sources;

    /**
     * The source groups that were signaled for this channel.
     */
    Collection<SourceGroupPacketExtension> sourceGroups;

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
            @NotNull AbstractEndpoint endpoint,
            long localSsrc,
            ContentShim contentShim)
    {
        this.id = id;
        this.endpoint = endpoint;
        this.localSsrc = localSsrc;
        this.contentShim = contentShim;
        this.creationTimestampMs = System.currentTimeMillis();
        endpoint.addChannel(this);
    }

    /**
     * Gets this channel's creation timestamp.
     * @return
     */
    public long getCreationTimestampMs()
    {
        return creationTimestampMs;
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

    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        commonIq.setID(id);
        commonIq.setEndpoint(endpoint.getID());
        // I don't think we even support not being the initiator at this point,
        // so hard-coding this
        commonIq.setInitiator(true);
        // Elsewhere we enforce that endpoint ID == channel bundle ID
        commonIq.setChannelBundleId(endpoint.getID());
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
        }
    }

    /**
     * Sets the list of sources signaled for this channel.
     */
    public void setSources(@NotNull List<SourcePacketExtension> sources)
    {
        this.sources = sources;
        sources.stream()
            .map(source -> source.getSSRC())
            .forEach(endpoint::addReceiveSsrc);
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
                long primarySsrc = sourceGroup.getSources().get(0).getSSRC();
                long secondarySsrc = sourceGroup.getSources().get(1).getSSRC();

                SsrcAssociationType ssrcAssociationType
                        = getAssociationType(sourceGroup.getSemantics());
                if (ssrcAssociationType != null &&
                        ssrcAssociationType != SsrcAssociationType.SIM)
                {
                    endpoint.getConference().encodingsManager
                        .addSsrcAssociation(
                                endpoint.getID(),
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
        payloadTypes.forEach(ext -> {
            PayloadType pt = PayloadTypeUtil.create(ext);
            if (pt == null)
            {
                logger.warn("Unrecognized payload type " + ext.toXML());
            }
            else
            {
                logger.debug(
                        "Adding a payload type to endpoint="
                                + endpoint.getID() + ": " + pt);

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
        rtpHeaderExtensions.forEach(ext ->
            endpoint.transceiver.addRtpExtension(
                    Byte.valueOf(ext.getID()),
                    new RTPExtension(ext.getURI())));
    }

    /**
     * Sets the direction of this channel.
     * @param direction the direction to set.
     */
    public void setDirection(MediaDirection direction)
    {
        this.direction = direction;
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
    AbstractEndpoint getEndpoint()
    {
        return endpoint;
    }
}
