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
import org.jitsi.nlj.rtp.*;
import org.jitsi.osgi.*;
import org.jitsi.util.*;
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
public class OctoTentacle extends PropertyChangeNotifier implements PotentialPacketHandler
{
    /**
     * The {@link Logger} used by the {@link OctoTentacle} class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(OctoTentacle.class);

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
        return conference.getAudioLevelListener();
    }

    /**
     * Adds a {@link PayloadType}
     */
    public void addPayloadType(PayloadType payloadType)
    {
        transceiver.addPayloadType(payloadType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendRtp(PacketInfo packetInfo, String sourceEpId)
    {
        relay.sendRtp(
                packetInfo.getPacket(),
                targets,
                conference.getGid(),
                sourceEpId);
    }

    /**
     * Sets the list of remote relays to send packets to.
     * @param relays the list of relay IDs, which are converted to addresses
     * using the logic in {@link OctoRelay}.
     */
    public void setRelays(Collection<String> relays)
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
     * {@inheritDoc}
     */
    @Override
    public boolean wants(PacketInfo packetInfo, String sourceEpId)
    {
        // Cthulhu devours everything (as long as it's not coming from
        // itself, and we have targets).
        return sourceEpId != null && !targets.isEmpty();
    }

    /**
     * Sets the list of sources and source groups which describe the RTP streams
     * we expect to receive from remote Octo relays.
     *
     * @param sources the list of sources.
     * @param sourceGroups the list of source groups.
     */
    public void setSources(
            List<SourcePacketExtension> audioSources,
            List<SourcePacketExtension> videoSources,
            List<SourceGroupPacketExtension> videoSourceGroups)
    {
        MediaStreamTrackDesc[] tracks =
            MediaStreamTrackFactory.createMediaStreamTracks(
                    videoSources, videoSourceGroups);
        transceiver.setMediaStreamTracks(tracks);

        List<SourcePacketExtension> allSources = new LinkedList<>(audioSources);
        allSources.addAll(videoSources);

        Set<String> endpointIds
                = allSources.stream()
                    .map(source -> MediaStreamTrackFactory.getOwner(source))
                    .collect(Collectors.toSet());

        if (octoEndpoints.setEndpoints(endpointIds))
        {
            firePropertyChange(
                ENDPOINT_CHANGED_PROPERTY_NAME,
                null,
                null);
        }

        allSources.forEach(source ->
        {
            String owner = MediaStreamTrackFactory.getOwner(source);
            if (owner == null)
            {
                logger.warn("Source has no owner. Can not add receive SSRC.");
                return;
            }

            AbstractEndpoint endpoint = conference.getEndpoint(owner);
            if (endpoint == null)
            {
                logger.warn(
                    "No endpoint for a source's owner. Can not add receive SSRC.");
                return;
            }

            endpoint.addReceiveSsrc(source.getSSRC());
        });
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
    public void addRtpExtension(RtpExtension rtpExtension)
    {
        transceiver.addRtpExtension(rtpExtension);
    }

    /**
     * Expires the Octo-related parts of a confence.
     */
    public void expire()
    {
        setRelays(new LinkedList<>());
        octoEndpoints.setEndpoints(Collections.EMPTY_SET);
    }
}
