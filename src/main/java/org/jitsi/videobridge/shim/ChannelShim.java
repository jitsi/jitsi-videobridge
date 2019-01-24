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
import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * Represents a Colibri {@code channel}.
 *
 * @author Brian Baldino
 */
public class ChannelShim
{
    final String id;
    final public AbstractEndpoint endpoint;
    // The bridge's ssrc for this channel
    final long localSsrc;
    final boolean isOcto;
    MediaDirection direction;
    private Collection<PayloadTypePacketExtension> payloadTypes;
    Collection<RTPHdrExtPacketExtension> rtpHeaderExtensions;
    Collection<SourcePacketExtension> sources;
    Collection<SourceGroupPacketExtension> sourceGroups;

    private Integer expire;
    private boolean expired = false;
    private final long creationTimestampMs;

    public ChannelShim(
            @NotNull String id,
            @NotNull AbstractEndpoint endpoint,
            long localSsrc,
            boolean isOcto)
    {
        this.id = id;
        this.endpoint = endpoint;
        this.localSsrc = localSsrc;
        this.isOcto = isOcto;
        this.creationTimestampMs = System.currentTimeMillis();
        endpoint.addChannel(this);
    }

    public long getCreationTimestampMs()
    {
        return creationTimestampMs;
    }

    public Collection<PayloadTypePacketExtension> getPayloadTypes() {
        return payloadTypes;
    }

    public void setPayloadTypes(Collection<PayloadTypePacketExtension> payloadTypes) {
        this.payloadTypes = payloadTypes;
    }

    public Integer getExpire()
    {
        if (expire == null)
        {
            throw new Error("Null expire value for channel " + id);
        }
        return expire;
    }

    public void setExpire(Integer expire)
    {
        this.expire = expire;
        if (expire == 0)
        {
            expired = true;
            endpoint.removeChannel(this);
        }
    }

    public boolean isExpired()
    {
        return expired;
    }

    public void setLastN(Integer lastN)
    {
        // Since only a single channel (the video channel) should have the lastN value set, we don't worry
        // about overriding the singular lastN value on the endpoint
        endpoint.setLastN(lastN);
    }


    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        commonIq.setEndpoint(endpoint.getID());
        commonIq.setID(id);
        // I don't think we even support not being the initiator at this point, so hard-coding this
        commonIq.setInitiator(true);
        // Elsewhere we enforce that endpoint ID == channel bundle ID
        commonIq.setChannelBundleId(endpoint.getID());
        if (commonIq instanceof ColibriConferenceIQ.Channel)
        {
            ColibriConferenceIQ.Channel iq = (ColibriConferenceIQ.Channel) commonIq;
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

        if (isOcto)
        {
            commonIq.setType(ColibriConferenceIQ.OctoChannel.TYPE);
        }
    }
}
