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

import kotlin.*;
import kotlin.jvm.functions.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtcp.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.util.*;
import org.jitsi.osgi.*;
import org.jitsi.rtp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.event.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.octo.config.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.json.simple.*;
import org.osgi.framework.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

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
    final OctoTransceiver transceiver;

    /**
     * The {@link OctoRelay} used to actually send and receive Octo packets.
     */
    private final OctoRelay relay;

    /**
     * The instance that will request keyframes on behalf of this tentable. Note
     * that we don't have bridge-to-bridge rtt measurements atm so we use a
     * default value of 100ms.
     */
    private final KeyframeRequester keyframeRequester;

    /**
     * The list of remote Octo targets.
     */
    private Set<SocketAddress> targets
            = Collections.unmodifiableSet(new HashSet<>());

    /**
     * Count the number of dropped packets and exceptions.
     */
    public static final CountingErrorHandler queueErrorCounter
        = new CountingErrorHandler();

    /**
     * The queues which pass packets to be sent.
     */
    private Map<String, PacketInfoQueue> outgoingPacketQueues =
        new ConcurrentHashMap<>();

    /**
     * Initializes a new {@link OctoTentacle} instance.
     * @param conference the conference.
     */
    public OctoTentacle(Conference conference)
    {
        this.conference = conference;
        this.logger = conference.getLogger().createChildLogger(this.getClass().getName());
        octoEndpoints = new OctoEndpoints(conference);
        transceiver = new OctoTransceiver(this, logger);

        BundleContext bundleContext = conference.getBundleContext();
        OctoRelayService octoRelayService
            = bundleContext == null ? null :
                ServiceUtils2.getService(bundleContext, OctoRelayService.class);


        if (octoRelayService != null)
        {
            relay = octoRelayService.getRelay();
            keyframeRequester = new KeyframeRequester(transceiver.getStreamInformationStore(), logger);
            keyframeRequester.attach(new ConsumerNode("octo keyframe relay node")
            {
                @Override
                protected void consume(@NotNull PacketInfo packetInfo)
                {
                    relay.sendPacket(packetInfo.getPacket(), targets,
                        conference.getGid(), packetInfo.getEndpointId());
                }

                @Override
                public void trace(@NotNull Function0<Unit> f)
                {
                    f.invoke();
                }
            });
        }
        else
        {
            relay = null;
            keyframeRequester = null;
        }
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
     * Creates a PacketInfoQueue for an endpoint.
     */
    private PacketInfoQueue createQueue(String epId)
    {
        PacketInfoQueue q = new PacketInfoQueue(
            "octo-tentacle-outgoing-packet-queue",
            TaskPools.IO_POOL,
            this::doSend,
            OctoConfig.Config.sendQueueSize());
        q.setObserver(new QueueObserver(q));
        q.setErrorHandler(queueErrorCounter);
        return q;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void send(PacketInfo packetInfo)
    {
        /* We queue packets separately by their *source* endpoint.
         * This achieves parallelization while guaranteeing that we don't
         * reorder things that shouldn't be reordered.
         */
        PacketInfoQueue queue =
            outgoingPacketQueues.computeIfAbsent(packetInfo.getEndpointId(),
             this::createQueue);

        queue.add(packetInfo);
    }

    /**
     * Send Octo packet out.
     */
    private boolean doSend(PacketInfo packetInfo)
    {
        Packet packet = packetInfo.getPacket();
        if (packet != null)
        {
            relay.sendPacket(
                packet,
                targets,
                conference.getGid(),
                packetInfo.getEndpointId());
        }
        return true;
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
    public boolean wants(PacketInfo packetInfo)
    {
        // Cthulhu devours everything (as long as it's not coming from
        // itself, and we have targets).
        return !(packetInfo instanceof OctoPacketInfo) && !targets.isEmpty();
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
                    .map(source -> MediaStreamTrackFactory.getOwner(source))
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
        outgoingPacketQueues.remove(endpointId);
    }

    /**
     * Handles and RTP packet coming from a remote Octo relay after it has
     * been parsed and handled by our {@link #transceiver}.
     * @param packetInfo the packet to handle.
     */
    void handleIncomingPacket(PacketInfo packetInfo)
    {
        conference.handleIncomingPacket(packetInfo);
    }

    /**
     * Handles a message received from an Octo relay.
     * @param message
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
                relay.removeHandler(conference.getGid(), transceiver);
            }
            else
            {
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
        logger.info("Expiring");
        setRelays(new LinkedList<>());
        octoEndpoints.setEndpoints(Collections.emptySet());
    }

    /**
     * Sends a data message through the Octo relay.
     * @param message
     */
    public void sendMessage(String message)
    {
        relay.sendString(
                message,
                targets,
                conference.getGid(),
                null);
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
        debugState.put("relay", relay.getDebugState());
        debugState.put("targets", targets.toString());

        /* Only include queues' debug state if queue debugging is enabled. */
        if (QueueObserver.DEBUG)
        {
            JSONObject queueDebugState = new JSONObject();
            for (Map.Entry<String, PacketInfoQueue> entry: outgoingPacketQueues.entrySet())
            {
                QueueObserver o = (QueueObserver)entry.getValue().getObserver();
                queueDebugState.put(entry.getKey(), o.getQueueDebugState());
            }
            debugState.put("outgoingPacketQueues", queueDebugState);
        }

        return debugState;
    }

    public void requestKeyframe(long mediaSsrc)
    {
        if (keyframeRequester != null)
        {
            keyframeRequester.requestKeyframe(mediaSsrc);
        }
        else
        {
            logger.warn("Failed to request a keyframe from a foreign endpoint.");
        }
    }
}
