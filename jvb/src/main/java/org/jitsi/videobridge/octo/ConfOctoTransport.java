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

import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.util.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.queue.*;
import org.jitsi.utils.stats.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.message.*;
import org.jitsi.videobridge.octo.config.*;
import org.jitsi.videobridge.transport.octo.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.json.simple.*;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import static org.jitsi.videobridge.transport.octo.OctoUtils.*;

/**
 * The single class in the octo package which serves as a link between a
 * {@link Conference} and its Octo-related functionality.  The Tentacle
 * acts as a handler for all of the Octo packets for a given conference, and
 * gives a place for {@link OctoEndpoint}s to register as handlers for the
 * packets coming from their remote counterparts.
 *
 * @author Boris Grozev
 */
public class ConfOctoTransport
    implements PotentialPacketHandler, BridgeOctoTransport.IncomingOctoPacketHandler
{
    /**
     * The {@link Logger} used by the {@link ConfOctoTransport} class and its
     * instances to print debug information.
     */
    private final Logger logger;

    private final MultiStreamConfig multiStreamConfig = new MultiStreamConfig();

    /**
     * The conference for this {@link ConfOctoTransport}.
     */
    private final Conference conference;

    /**
     * The ID of the conference to use for everything Octo-related. It is set
     * to the GID of the conference (see {@link Conference#gid}).
     */
    private final long conferenceId;

    /**
     * The {@link OctoEndpoints} instance which maintains the list of Octo
     * endpoints in the conference.
     */
    private final OctoEndpoints octoEndpoints;

    /**
     * The {@link BridgeOctoTransport} used to actually send and receive Octo packets.
     */
    private final BridgeOctoTransport bridgeOctoTransport;

    /**
     * The IDs of the remote bridges, mapped to their address.
     */
    private Map<String, SocketAddress> remoteBridges
            = Collections.unmodifiableMap(new HashMap<>());

    /**
     * Handlers for incoming Octo media packets, looked up by the
     * source endpoint ID field in the Octo header.
     */
    private final Map<String, IncomingOctoEpPacketHandler> incomingPacketHandlers =
        new ConcurrentHashMap<>();

    /**
     * Count the number of dropped packets and exceptions.
     */
    public static final CountingErrorHandler queueErrorCounter
        = new CountingErrorHandler();

    /**
     * We queue up outgoing packets by their *source* endpoint ID.
     */
    private final Map<String, PacketInfoQueue> outgoingPacketQueues =
        new ConcurrentHashMap<>();

    /**
     * An {@link OctoTransceiver} to handle packets which originate from
     * a remote bridge (and have a special 'source endpoint ID').
     */
    private final OctoTransceiver octoTransceiver;

    private final Stats stats = new Stats();

    private final Clock clock;

    /**
     * Whether or not this {@link ConfOctoTransport} is currently active
     */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * The ID of this bridge in this octo conference.
     */
    private final String bridgeId;

    /**
     * Initializes a new {@link ConfOctoTransport} instance.
     * @param conference the conference.
     */
    public ConfOctoTransport(Conference conference)
    {
        this(conference, Clock.systemUTC());
    }

    public ConfOctoTransport(Conference conference, Clock clock)
    {
        this.conference = conference;
        this.conferenceId = conference.getGid();
        this.clock = clock;
        this.logger = conference.getLogger().createChildLogger(this.getClass().getName());
        OctoRelayService octoRelayService = OctoRelayServiceProviderKt.singleton().get();

        if (octoRelayService == null)
        {
            throw new IllegalStateException("Couldn't get OctoRelayService");
        }

        bridgeOctoTransport = octoRelayService.getBridgeOctoTransport();
        if (bridgeOctoTransport == null)
        {
            throw new IllegalStateException("Couldn't get OctoTransport");
        }

        bridgeId = bridgeOctoTransport.getRelayId();

        octoEndpoints = new OctoEndpoints(conference);
        octoTransceiver = new OctoTransceiver("tentacle-" + conferenceId, logger);
        octoTransceiver.setIncomingPacketHandler(conference::handleIncomingPacket);
        octoTransceiver.setOutgoingPacketHandler(packetInfo ->
        {
            throw new RuntimeException("This should not be used for sending");
        });

        // Some remote packets didn't originate from an endpoint, but from
        // the bridge (like keyframe requests).
        addHandler(JVB_EP_ID, new IncomingOctoEpPacketHandler()
        {
            @Override
            public void handleIncomingPacket(@NotNull OctoPacketInfo packetInfo)
            {
                octoTransceiver.handleIncomingPacket(packetInfo);
            }
        });
    }

    public String getBridgeId()
    {
        return bridgeId;
    }

    /**
     * Adds a {@link PayloadType}
     */
    public void addPayloadType(PayloadType payloadType)
    {
        if (running.get())
        {
            octoEndpoints.addPayloadType(payloadType);
        }
    }

    /**
     * Sets the list of remote relays to send packets to.
     * @param newRelayIds the list of relay IDs, which are converted to addresses
     * using the logic in {@link OctoUtils}.
     */
    public void setRelays(Collection<String> newRelayIds)
    {
        if (!running.get())
        {
            return;
        }
        Objects.requireNonNull(
            bridgeOctoTransport,
            "Octo requested but not configured");

        HashMap<String, SocketAddress> newRelays = new HashMap<>();
        for (String relayId : newRelayIds)
        {
            SocketAddress socketAddress = OctoUtils.Companion.relayIdToSocketAddress(relayId);
            if (socketAddress != null)
            {
                newRelays.put(relayId, socketAddress);
            }
        }

        if (!remoteBridges.equals(newRelays))
        {
            remoteBridges.keySet().stream()
                    .filter(r -> !newRelays.containsKey(r))
                    .forEach(this::remoteRelayRemoved);

            Collection<SocketAddress> newBridgeAddresses = newRelays.entrySet().stream()
                .filter(e -> !remoteBridges.containsKey(e.getKey()))
                .map(Map.Entry::getValue).collect(Collectors.toList());

            if (newRelays.isEmpty())
            {
                bridgeOctoTransport.removeHandler(conferenceId, this);
            }
            else if (remoteBridges.isEmpty())
            {
                bridgeOctoTransport.addHandler(conferenceId, this);
            }
            remoteBridges = Collections.unmodifiableMap(newRelays);

            if (!newBridgeAddresses.isEmpty())
            {
                newRelaysAdded(newBridgeAddresses);
            }
        }
    }

    private void remoteRelayRemoved(String relayId)
    {
        conference.getLocalEndpoints().forEach(e -> e.removeReceiver(relayId));
    }

    private void newRelaysAdded(Collection<SocketAddress> newBridgeAddresses)
    {
        /* Wait a second to make sure the other bridges know about this conference already. */
        TaskPools.SCHEDULED_POOL.schedule(() ->
            /* Inform new bridges of existing local endpoints' video types. */
            conference.getLocalEndpoints().forEach((e) -> {
                    if (multiStreamConfig.getEnabled())
                    {
                        Arrays.stream(e.getMediaSources()).forEach((msd) -> {
                            String sourceName = msd.getSourceName();
                            if (sourceName != null)
                            {
                                VideoType videoType = msd.getVideoType();
                                // Do not send the initial value for CAMERA, because it's the default
                                if (VideoType.CAMERA != videoType)
                                {
                                    SourceVideoTypeMessage videoTypeMsg = new SourceVideoTypeMessage(
                                            videoType,
                                            sourceName,
                                            e.getId()
                                    );
                                    bridgeOctoTransport.sendString(
                                            videoTypeMsg.toJson(),
                                            newBridgeAddresses,
                                            conferenceId
                                    );
                                }
                            }
                            else
                            {
                                // Legacy mobile clients which do not signal the source name yet
                                VideoTypeMessage msg = new VideoTypeMessage(e.getVideoType(), e.getId());
                                bridgeOctoTransport.sendString(
                                    msg.toJson(),
                                    newBridgeAddresses,
                                    conferenceId
                                );
                            }
                        });
                    }
                    else
                    {
                        VideoTypeMessage msg = new VideoTypeMessage(e.getVideoType(), e.getId());
                        bridgeOctoTransport.sendString(
                            msg.toJson(),
                            newBridgeAddresses,
                            conferenceId
                        );
                    }
                }
            ), 1, TimeUnit.SECONDS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean wants(PacketInfo packetInfo)
    {
        // Cthulhu devours everything (as long as it's not coming from
        // itself, and we have targets).
        return running.get() &&
            !(packetInfo instanceof OctoPacketInfo) &&
            !remoteBridges.isEmpty();
    }

    @Override
    public void send(PacketInfo packet)
    {
        if (!running.get())
        {
            ByteBufferPool.returnBuffer(packet.getPacket().getBuffer());
            return;
        }

        if (packet.getEndpointId() != null)
        {
            /* We queue packets separately by their *source* endpoint.
             * This achieves parallelization while guaranteeing that we don't
             * reorder things that shouldn't be reordered.
             */
            PacketInfoQueue queue =
                outgoingPacketQueues.computeIfAbsent(packet.getEndpointId(),
                    this::createQueue);

            queue.add(packet);
        }
        else
        {
            // Packets without an endpoint ID originated from within the bridge
            // itself and, in practice, are things like keyframe requests.  We
            // send them out directly (without queueing).
            doSend(packet);
        }
    }

    private boolean doSend(PacketInfo packetInfo)
    {
        stats.packetSent(packetInfo.getPacket().getLength(), clock.instant());
        packetInfo.sent();
        bridgeOctoTransport.sendMediaData(
            packetInfo.getPacket().getBuffer(),
            packetInfo.getPacket().getOffset(),
            packetInfo.getPacket().getLength(),
            remoteBridges.values(),
            conferenceId,
            packetInfo.getEndpointId()
        );
        ByteBufferPool.returnBuffer(packetInfo.getPacket().getBuffer());

        return true;
    }

    @Override
    public void handleMediaPacket(@NotNull OctoPacketInfo packetInfo)
    {
        if (!running.get())
        {
            ByteBufferPool.returnBuffer(packetInfo.getPacket().getBuffer());
            return;
        }
        stats.packetReceived(packetInfo.getPacket().length, clock.instant());
        IncomingOctoEpPacketHandler handler = incomingPacketHandlers.get(packetInfo.getEndpointId());
        if (handler != null)
        {
            handler.handleIncomingPacket(packetInfo);
        }
        else
        {
            stats.incomingPacketDropped();
            ByteBufferPool.returnBuffer(packetInfo.getPacket().getBuffer());
        }
    }

    @Override
    public void handleMessagePacket(@NotNull String message, @NotNull String sourceEpId)
    {
        if (!running.get())
        {
            return;
        }
        octoEndpoints.messageTransport.onMessage(null /* source */ , message);
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
        if (!running.get())
        {
            return;
        }
        // Jicofo sends an empty "source" when it wants to clear the sources.
        // This manifests as a failure to find an 'owner', so we ignore any sources
        // for which that is the case here
        Map<String, Map<MediaType, Set<Long>>> ssrcsByMediaTypeByEpId = new HashMap<>();
        audioSources.forEach(audioSource -> {
            String owner;
            if ((owner = MediaSourceFactory.getOwner(audioSource)) != null)
            {
                Map<MediaType, Set<Long>> epSsrcs =
                    ssrcsByMediaTypeByEpId.computeIfAbsent(owner, key -> new HashMap<>());
                epSsrcs.computeIfAbsent(MediaType.AUDIO, key -> new HashSet<>()).add(audioSource.getSSRC());
            }
        });
        videoSources.forEach(videoSource -> {
            String owner;
            if ((owner = MediaSourceFactory.getOwner(videoSource)) != null)
            {
                Map<MediaType, Set<Long>> epSsrcs =
                    ssrcsByMediaTypeByEpId.computeIfAbsent(owner, key -> new HashMap<>());
                epSsrcs.computeIfAbsent(MediaType.VIDEO, key -> new HashSet<>()).add(videoSource.getSSRC());
            }
        });
        Set<String> endpointIds = ssrcsByMediaTypeByEpId.keySet();

        octoEndpoints.setEndpoints(endpointIds);

        // Create the sources after creating the endpoints
        MediaSourceDesc[] sources =
            MediaSourceFactory.createMediaSources(
                videoSources, videoSourceGroups);
        octoEndpoints.setMediaSources(sources);

        // We only need to call this if the sources of any endpoint actually
        // changed, but that's not easy to detect. It's safe to call it more
        // often.
        conference.endpointSourcesChanged(null);

        endpointIds.forEach(endpointId ->
        {
            Map<MediaType, Set<Long>> endpointSsrcsByMediaType = ssrcsByMediaTypeByEpId.get(endpointId);
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
        PacketInfoQueue removed = outgoingPacketQueues.remove(endpointId);
        if (removed != null)
        {
            removed.close();
        }
    }

    /**
     * Adds an RTP header extension.
     * @param rtpExtension the {@link RtpExtension} to add
     */
    public void addRtpExtension(RtpExtension rtpExtension)
    {
        if (!running.get())
        {
            return;
        }

        octoEndpoints.addRtpExtension(rtpExtension);
    }

    /**
     * Expires the Octo-related parts of a conference.
     */
    public void expire()
    {
        if (running.compareAndSet(true, false))
        {
            logger.info("Expiring");
            setRelays(Collections.emptySet());
            octoEndpoints.setEndpoints(Collections.emptySet());
            outgoingPacketQueues.values().forEach(PacketInfoQueue::close);
            outgoingPacketQueues.clear();
        }
    }

    /**
     * Sends a data message through the Octo relay.
     * @param message the message to send
     */
    public void sendMessage(BridgeChannelMessage message)
    {
        if (!running.get())
        {
            return;
        }

        bridgeOctoTransport.sendString(
            message.toJson(),
            remoteBridges.values(),
            conferenceId
        );
    }

    public void addHandler(String epId, IncomingOctoEpPacketHandler handler)
    {
        if (!running.get())
        {
            return;
        }

        logger.info("Adding handler for ep ID " + epId);
        incomingPacketHandlers.put(epId, handler);
    }

    public void removeHandler(String epId, IncomingOctoEpPacketHandler handler)
    {
        if (incomingPacketHandlers.remove(epId, handler))
        {
            logger.info("Removing handler for ep ID " + epId);
        }
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
            OctoConfig.config.getSendQueueSize());
        q.setErrorHandler(queueErrorCounter);
        return q;
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    public OrderedJsonObject getDebugState()
    {
        OrderedJsonObject debugState = new OrderedJsonObject();
        debugState.put("octoEndpoints", octoEndpoints.getDebugState());
        debugState.putAll(stats.toJson());
        debugState.put("bridgeOctoTransport", bridgeOctoTransport.getStatsJson());

        JSONArray remoteRelays = new JSONArray();
        remoteRelays.addAll(remoteBridges.keySet());
        debugState.put("remoteRelays", remoteRelays);

        return debugState;
    }

    static class Stats
    {
        // RX
        private Long packetsReceived = 0L;
        private final RateTracker receivePacketRate = new RateTracker(Duration.ofSeconds(60), Duration.ofSeconds(1));
        private Long bytesReceived = 0L;
        private final BitrateTracker receiveBitRate = new BitrateTracker(Duration.ofSeconds(60), Duration.ofSeconds(1));
        private long incomingPacketsDropped = 0;

        // TX
        private final LongAdder packetsSent = new LongAdder();
        private final RateTracker sendPacketRate = new RateTracker(Duration.ofSeconds(60), Duration.ofSeconds(1));
        private final LongAdder bytesSent = new LongAdder();
        private final BitrateTracker sendBitRate = new BitrateTracker(Duration.ofSeconds(60), Duration.ofSeconds(1));

        void packetReceived(int size, Instant time)
        {
            long timeMs = time.toEpochMilli();
            packetsReceived++;
            receivePacketRate.update(1, timeMs);
            bytesReceived += size;
            receiveBitRate.update(DataSizeKt.getBytes(size), timeMs);
        }

        void incomingPacketDropped()
        {
            incomingPacketsDropped++;
        }

        void packetSent(int size, Instant time)
        {
            long timeMs = time.toEpochMilli();
            packetsSent.increment();
            sendPacketRate.update(1, timeMs);
            bytesSent.add(size);
            sendBitRate.update(DataSizeKt.getBytes(size), timeMs);
        }

        OrderedJsonObject toJson()
        {
            OrderedJsonObject debugState = new OrderedJsonObject();
            debugState.put("packets_received", packetsReceived);
            debugState.put("receive_packet_rate_pps", receivePacketRate.getRate());
            debugState.put("bytes_received", bytesReceived);
            debugState.put("receive_bitrate_bps", receiveBitRate.getRateBps());
            debugState.put("incoming_packets_dropped", incomingPacketsDropped);

            debugState.put("packets_sent", packetsSent.sum());
            debugState.put("send_packet_rate_pps", sendPacketRate.getRate());
            debugState.put("bytes_sent", bytesSent.sum());
            debugState.put("send_bitrate_bps", sendBitRate.getRateBps());

            return debugState;
        }
    }

    interface IncomingOctoEpPacketHandler
    {
        void handleIncomingPacket(@NotNull OctoPacketInfo packetInfo);
    }
}
