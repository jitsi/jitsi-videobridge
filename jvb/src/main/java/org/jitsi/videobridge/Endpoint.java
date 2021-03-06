/*
 * Copyright @ 2015 - Present, 8x8 Inc
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

import kotlin.*;
import kotlin.jvm.functions.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.*;
import org.jitsi.rtp.extensions.*;
import org.jitsi.utils.*;
import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.cc.allocation.*;
import org.jitsi.videobridge.datachannel.*;
import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi.videobridge.message.*;
import org.jitsi.videobridge.rest.root.debug.*;
import org.jitsi.videobridge.sctp.*;
import org.jitsi.videobridge.shim.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.transport.dtls.*;
import org.jitsi.videobridge.transport.ice.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.websocket.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi_modified.sctp4j.*;

import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.function.*;
import java.util.stream.*;

/**
 * Represents an endpoint of a participant in a <tt>Conference</tt>.
 *
 * @author Lyubomir Marinov
 * @author Boris Grozev
 * @author Pawel Domas
 * @author George Politis
 */
public abstract class Endpoint
    extends AbstractEndpoint implements PotentialPacketHandler,
        EncodingsManager.EncodingsUpdateListener
{
    /**
     * Initializes a new <tt>Endpoint</tt> instance with a specific (unique)
     * identifier/ID of the endpoint of a participant in a <tt>Conference</tt>.
     *
     * @param id the identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt> with which the new instance is to be initialized
     * @param conference conference this endpoint belongs to
     * @param iceControlling {@code true} if the ICE agent of this endpoint's
     * transport will be initialized to serve as a controlling ICE agent;
     * otherwise - {@code false}
     */
    protected Endpoint(
        String id,
        Conference conference,
        Logger parentLogger,
        boolean iceControlling,
        Clock clock)
    {
        super(conference, id, parentLogger);
    }

    protected Endpoint(
        String id,
        Conference conference,
        Logger parentLogger,
        boolean iceControlling)
    {
        this(id, conference, parentLogger, iceControlling, Clock.systemUTC());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public abstract EndpointMessageTransport getMessageTransport();

    protected abstract void setupIceTransport();

    protected abstract void setupDtlsTransport();

    protected abstract boolean doSendSrtp(PacketInfo packetInfo);

    /**
     * Notifies this {@code Endpoint} that the ordered list of {@code Endpoint}s changed.
     */
    abstract void lastNEndpointsChanged();

    /**
     * {@inheritDoc}
     */
    public abstract void setLastN(Integer lastN);

    /**
     * Gets the LastN value for this endpoint.
     */
    public abstract int getLastN();

    /**
     * Sets the local SSRC for this endpoint.
     * @param mediaType
     * @param ssrc
     */
    public abstract void setLocalSsrc(MediaType mediaType, long ssrc);

    public abstract double getRtt();

    public abstract void endpointMessageTransportConnected();

    protected abstract void effectiveVideoConstraintsChanged(
        Map<String, VideoConstraints> oldEffectiveConstraints,
        Map<String, VideoConstraints> newEffectiveConstraints);

    public abstract void createSctpConnection();

    public abstract boolean acceptWebSocket(String password);

    protected abstract String getIcePassword();
    protected abstract void sendForwardedEndpointsMessage(Collection<String> forwardedEndpoints);

    public abstract void setTransportInfo(IceUdpTransportPacketExtension transportInfo);

    /**
     * Sets the media sources.
     * @param mediaSources
     */
    protected abstract void setMediaSources(MediaSourceDesc[] mediaSources);

    abstract Instant getMostRecentChannelCreatedTime();

    public abstract void addChannel(ChannelShim channelShim);

    public abstract void removeChannel(ChannelShim channelShim);

    public abstract void updateMediaDirection(MediaType type, String direction);

    public abstract Transceiver getTransceiver();

    public abstract void updateAcceptedMediaTypes();

    public abstract void updateForceMute();

    public abstract int numForwardedEndpoints();

    /**
     * Enables/disables the given feature, if the endpoint implementation supports it.
     *
     * @param feature the feature to enable or disable.
     * @param enabled the state of the feature.
     */
    public abstract void setFeature(EndpointDebugFeatures feature, boolean enabled);

    public abstract boolean isFeatureEnabled(EndpointDebugFeatures feature);

    public abstract boolean isOversending();

    abstract void setSelectedEndpoints(List<String> selectedEndpoints);

    abstract void setMaxFrameHeight(int maxFrameHeight);

    abstract void setBandwidthAllocationSettings(ReceiverVideoConstraintsMessage message);
}
