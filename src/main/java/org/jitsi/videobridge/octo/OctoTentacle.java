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
package org.jitsi.videobridge.octo;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.osgi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.osgi.framework.*;

import java.net.*;
import java.util.*;
import java.util.stream.*;

import static org.jitsi.videobridge.AbstractEndpoint.ENDPOINT_CHANGED_PROPERTY_NAME;

/**
 * The single class in the octo package which serves as a link between a
 * {@link Conference} and its Octo-related functionality.
 *
 * @author Boris Grozev
 */
public class OctoTentacle extends PropertyChangeNotifier
{
    /**
     * The conference for this {@link OctoTentacle}.
     */
    private final Conference conference;

    /**
     * The {@link OctoEndpoints} instance which maintains the list of Octo
     * endpoints in the conference.
     */
    private final OctoEndpoints octoEndpoints;

    /**
     * The {@link OctoTransceiver} instance which handles RTP/RTCP processing.
     */
    final OctoTransceiver transceiver;

    /**
     * The {@link OctoRelay} used to actually send and receive Octo packets.
     */
    private final OctoRelay relay;

    /**
     * The audio level listener for this instance.
     */
    private final AudioLevelListener audioLevelListener;

    /**
     * The list of remote Octo targets.
     */
    private Set<SocketAddress> targets
            = Collections.unmodifiableSet(new HashSet<>());

    /**
     * Initializes a new {@link OctoTentacle} instance.
     * @param conference the conference.
     */
    public OctoTentacle(Conference conference)
    {
        this.conference = conference;
        audioLevelListener
                = new AudioLevelListenerImpl(conference.getSpeechActivity());
        octoEndpoints = new OctoEndpoints(conference);
        transceiver = new OctoTransceiver(this);

        BundleContext bundleContext = conference.getBundleContext();
        OctoRelayService octoRelayService
            = bundleContext == null ? null :
                ServiceUtils2.getService(bundleContext, OctoRelayService.class);

        relay = octoRelayService == null ? null : octoRelayService.getRelay();
    }

    /**
     * Gets the audio level listener.
     * @return
     */
    AudioLevelListener getAudioLevelListener()
    {
        return audioLevelListener;
    }

    /**
     * Adds a {@link PayloadType}
     */
    public void addPayloadType(PayloadType payloadType)
    {
        transceiver.addPayloadType(payloadType);
    }

    /**
     * Sends an RTP/RTCP packet through the relay to the remote Octo targets
     * @param packetInfo the packet to send.
     * @param source the endpoint which is the source of the packet.
     */
    public void sendRtp(PacketInfo packetInfo, AbstractEndpoint source)
    {
        relay.sendRtp(
                packetInfo.getPacket(),
                targets,
                conference.getGid(),
                source.getID());
    }

    /**
     * Sets the list of remote relays to send packets to.
     * @param relays the list of relay IDs, which are converted to addresses
     * using the logic in {@link OctoRelay}.
     */
    public void setRelays(List<String> relays)
    {
        Objects.requireNonNull(
                relay,
                "Octo requested but not configured");

        Set<SocketAddress> socketAddresses = new HashSet<>();
        for (String relay : relays)
        {
            SocketAddress socketAddress = OctoRelay.relayIdToSocketAddress(relay);
            if (socketAddress != null)
            {
                socketAddresses.add(socketAddress);
            }
        }

        setTargets(socketAddresses);
    }

    /**
     * Checks whether a specific packet should be accepted for sending via
     * Octo.
     * @param packetInfo the packet.
     * @param source the endpoint which is the source of the packet.
     * @return {@code true} if the packet is to be accepted, and {@code false}
     * otherwise.
     */
    public boolean wants(PacketInfo packetInfo, AbstractEndpoint source)
    {
        // Cthulhu devours everything (as long as it's not coming from
        // itself, and we have targets).
        return source != null && !targets.isEmpty();
    }

    /**
     * Sets the list of sources and source groups which describe the RTP streams
     * we expect to receive from remote Octo relays.
     *
     * @param sources the list of sources.
     * @param sourceGroups the list of source groups.
     */
    public void setSources(List<SourcePacketExtension> sources, List<SourceGroupPacketExtension> sourceGroups)
    {
        MediaStreamTrackDesc[] tracks =
                MediaStreamTrackFactory.createMediaStreamTracks(
                        sources,
                        sourceGroups);
        if (transceiver.setMediaStreamTracks(tracks))
        {
            // Octo endpoints are created and expired solely based on signaling
            // of sources. We maintain endpoint objects for those endpoints
            // which are the owner of a track.
            Set<String> newEndpointIds
                = Arrays.stream(transceiver.getMediaStreamTracks())
                    .map(MediaStreamTrackDesc::getOwner)
                    .collect(Collectors.toSet());
            octoEndpoints.updateEndpoints(newEndpointIds);

            firePropertyChange(
                    ENDPOINT_CHANGED_PROPERTY_NAME,
                    null,
                    null);
        }
    }

    /**
     * Handles and RTP packet coming from a remote Octo relay after it has
     * been parsed and handled by our {@link #transceiver}.
     * @param packetInfo the packet to handle.
     */
    void handleIncomingRtp(PacketInfo packetInfo)
    {
        conference.handleIncomingRtp(packetInfo, null);
    }

    /**
     * Sets the list of remote addresses to send Octo packets to.
     * @param targets the list of addresses.
     */
    private void setTargets(Set<SocketAddress> targets)
    {
        if (!targets.equals(this.targets))
        {
            this.targets = Collections.unmodifiableSet(targets);

            if (targets.isEmpty())
            {
                System.err.println("xxx remove Octo handler " + conference.getGid());
                relay.removeHandler(conference.getGid());
            }
            else
            {
                System.err.println("xxx register Octo handler "+conference.getGid());
                relay.addHandler(conference.getGid(), transceiver);
            }
        }
    }

    /**
     * Adds an RTP header extension.
     * @param extensionId
     * @param rtpExtension
     */
    public void addRtpExtension(Byte extensionId, RTPExtension rtpExtension)
    {
        transceiver.addRtpExtension(extensionId, rtpExtension);
    }
}
