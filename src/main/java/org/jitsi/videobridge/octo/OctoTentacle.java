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

import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.osgi.*;
import org.jitsi.utils.*;
import org.jitsi.utils.event.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.transport.octo.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.json.simple.*;
import org.osgi.framework.*;

import java.net.*;
import java.util.*;
import java.util.stream.*;

/**
 * The single class in the octo package which serves as a link between a
 * {@link Conference} and its Octo-related functionality.
 *
 * @author Boris Grozev
 */
public class OctoTentacle extends PropertyChangeNotifier
    implements PotentialPacketHandler
{
    /**
     * The {@link Logger} used by the {@link OctoTentacle} class and its
     * instances to print debug information.
     */
    private final Logger logger;

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
    private final OctoTransceiver transceiver;

    /**
     * The {@link OctoTransport} used to actually send and receive Octo packets.
     */
    private final OctoTransport octoTransport;

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
        this.logger = conference.getLogger().createChildLogger(this.getClass().getName());
        BundleContext bundleContext = conference.getBundleContext();
        OctoRelayService octoRelayService
            = bundleContext == null ? null :
            ServiceUtils2.getService(bundleContext, OctoRelayService.class);

        if (octoRelayService == null)
        {
            throw new IllegalStateException("Couldn't get OctoRelayService");
        }

        octoTransport = octoRelayService.getOctoTransport();
        if (octoTransport == null)
        {
            throw new IllegalStateException("Couldn't get OctoTransport");
        }

        octoEndpoints = new OctoEndpoints(conference);
        transceiver = new OctoTransceiver(this, logger);

        transceiver.setAudioLevelListener(conference.getAudioLevelListener());
        transceiver.setIncomingPacketHandler(conference::handleIncomingPacket);
        transceiver.setOutgoingPacketHandler(packetInfo ->
        {
            packetInfo.sent();
            octoTransport.sendMediaData(
                packetInfo.getPacket().getBuffer(),
                packetInfo.getPacket().getOffset(),
                packetInfo.getPacket().getLength(),
                targets,
                conference.getGid(),
                packetInfo.getEndpointId()
            );
        });
    }

    /**
     * Adds a {@link PayloadType}
     */
    public void addPayloadType(PayloadType payloadType)
    {
        transceiver.addPayloadType(payloadType);
    }

    /**
     * Sets the list of remote relays to send packets to.
     * @param relays the list of relay IDs, which are converted to addresses
     * using the logic in {@link OctoUtils}.
     */
    public void setRelays(Collection<String> relays)
    {
        Objects.requireNonNull(
                octoTransport,
                "Octo requested but not configured");

        Set<SocketAddress> socketAddresses = new HashSet<>();
        for (String relay : relays)
        {
            SocketAddress socketAddress = OctoUtils.Companion.relayIdToSocketAddress(relay);
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
    public boolean wants(PacketInfo packetInfo)
    {
        // Cthulhu devours everything (as long as it's not coming from
        // itself, and we have targets).
        return !(packetInfo instanceof OctoPacketInfo) && !targets.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void send(PacketInfo packetInfo)
    {
        transceiver.sendPacket(packetInfo);
    }

    /**
     * Sets the list of sources and source groups which describe the RTP streams
     * we expect to receive from remote Octo relays.
     *
     * @param audioSources the list of audio sources.
     * @param videoSources the list of video sources.
     * @param videoSourceGroups the list of source groups for video.
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

        // Jicofo sends an empty "source" when it wants to clear the sources.
        // This manifests as a failure to find an 'owner', hence we clear the
        // nulls here.
        Set<String> endpointIds
                = allSources.stream()
                    .map(MediaStreamTrackFactory::getOwner)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

        octoEndpoints.setEndpoints(endpointIds);

        // We only need to call this if the tracks of any endpoint actually
        // changed, but that's not easy to detect. It's safe to call it more
        // often.
        conference.endpointTracksChanged(null);

        endpointIds.forEach(endpointId ->
        {
            Map<MediaType, Set<Long>> endpointSsrcsByMediaType = new HashMap<>();
            Set<Long> epAudioSsrcs = audioSources.stream()
                    .filter(source -> endpointId.equals(MediaStreamTrackFactory.getOwner(source)))
                    .filter(Objects::nonNull)
                    .map(SourcePacketExtension::getSSRC)
                    .collect(Collectors.toSet());
            endpointSsrcsByMediaType.put(MediaType.AUDIO, epAudioSsrcs);

            Set<Long> epVideoSsrcs = videoSources.stream()
                    .filter(source -> endpointId.equals(MediaStreamTrackFactory.getOwner(source)))
                    .filter(Objects::nonNull)
                    .map(SourcePacketExtension::getSSRC)
                    .collect(Collectors.toSet());
            endpointSsrcsByMediaType.put(MediaType.VIDEO, epVideoSsrcs);

            AbstractEndpoint endpoint = conference.getEndpoint(endpointId);
            if (endpoint instanceof OctoEndpoint)
            {
                ((OctoEndpoint) endpoint).setReceiveSsrcs(endpointSsrcsByMediaType);
            }
            else
            {
                logger.warn("No OctoEndpoint for SSRCs");
            }
        });
    }

    /**
     * Called when a local endpoint is expired.
     */
    public void endpointExpired(String endpointId)
    {
        transceiver.endpointExpired(endpointId);
    }

    public MediaStreamTrackDesc[] getMediaStreamTracks()
    {
        return transceiver.getMediaStreamTracks();
    }

    /**
     * Handles a message received from an Octo relay.
     * @param message the incoming message
     */
    public void handleMessage(String message)
    {
        octoEndpoints.messageTransport.onMessage(null /* source */ , message);
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
                octoTransport.removeHandler(conference.getGid(), transceiver);
            }
            else
            {
                octoTransport.addHandler(conference.getGid(), transceiver);
            }
        }
    }

    /**
     * Adds an RTP header extension.
     * @param rtpExtension the {@link RtpExtension} to add
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
        logger.info("Expiring");
        setRelays(new LinkedList<>());
        octoEndpoints.setEndpoints(Collections.emptySet());
    }

    /**
     * Sends a data message through the Octo relay.
     * @param message the message to send
     */
    public void sendMessage(String message)
    {
        octoTransport.sendString(
            message,
            targets,
            conference.getGid()
        );
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("octoEndpoints", octoEndpoints.getDebugState());
        debugState.put("transceiver", transceiver.getDebugState());
        debugState.put("octoTransport", octoTransport.getStatsJson());
        debugState.put("targets", targets.toString());

        return debugState;
    }

    public void requestKeyframe(long mediaSsrc)
    {
        transceiver.requestKeyframe(mediaSsrc);
    }
}
