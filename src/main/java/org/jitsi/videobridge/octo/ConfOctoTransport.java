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
import org.jitsi.osgi.*;
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
import org.osgi.framework.*;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import static org.jitsi.videobridge.transport.octo.OctoUtils.JVB_EP_ID;

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
     * The list of remote Octo targets.
     */
    private Set<SocketAddress> targets
            = Collections.unmodifiableSet(new HashSet<>());

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
        BundleContext bundleContext = conference.getBundleContext();
        OctoRelayService octoRelayService
            = bundleContext == null ? null :
            ServiceUtils2.getService(bundleContext, OctoRelayService.class);

        if (octoRelayService == null)
        {
            throw new IllegalStateException("Couldn't get OctoRelayService");
        }

        bridgeOctoTransport = octoRelayService.getBridgeOctoTransport();
        if (bridgeOctoTransport == null)
        {
            throw new IllegalStateException("Couldn't get OctoTransport");
        }

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
     * @param relays the list of relay IDs, which are converted to addresses
     * using the logic in {@link OctoUtils}.
     */
    public void setRelays(Collection<String> relays)
    {
        if (!running.get())
        {
            return;
        }
        Objects.requireNonNull(
            bridgeOctoTransport,
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
        return running.get() &&
            !(packetInfo instanceof OctoPacketInfo) &&
            !targets.isEmpty();
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
            targets,
            conferenceId,
            packetInfo.getEndpointId()
        );

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
        List<SourcePacketExtension> allSources = new LinkedList<>(audioSources);
        allSources.addAll(videoSources);

        // Jicofo sends an empty "source" when it wants to clear the sources.
        // This manifests as a failure to find an 'owner', hence we clear the
        // nulls here.
        Set<String> endpointIds
                = allSources.stream()
                    .map(MediaSourceFactory::getOwner)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

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
            Map<MediaType, Set<Long>> endpointSsrcsByMediaType = new HashMap<>();
            Set<Long> epAudioSsrcs = audioSources.stream()
                    .filter(source -> endpointId.equals(MediaSourceFactory.getOwner(source)))
                    .filter(Objects::nonNull)
                    .map(SourcePacketExtension::getSSRC)
                    .collect(Collectors.toSet());
            endpointSsrcsByMediaType.put(MediaType.AUDIO, epAudioSsrcs);

            Set<Long> epVideoSsrcs = videoSources.stream()
                    .filter(source -> endpointId.equals(MediaSourceFactory.getOwner(source)))
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
        PacketInfoQueue removed = outgoingPacketQueues.remove(endpointId);
        if (removed != null)
        {
            removed.close();
        }
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
                bridgeOctoTransport.removeHandler(conferenceId, this);
            }
            else
            {
                bridgeOctoTransport.addHandler(conferenceId, this);
            }
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
            setTargets(Collections.emptySet());
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
            targets,
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
            OctoConfig.Config.sendQueueSize());
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
        debugState.put("targets", targets.toString());

        return debugState;
    }

    static class Stats
    {
        // RX
        private Long packetsReceived = 0L;
        private final RateStatistics receivePacketRate =
            new RateStatistics(60000, 1000.0f);
        private Long bytesReceived = 0L;
        private final RateStatistics receiveBitRate = new RateStatistics(60000);
        private long incomingPacketsDropped = 0;

        // TX
        private final LongAdder packetsSent = new LongAdder();
        private final RateStatistics sendPacketRate =
            new RateStatistics(60000, 1000.0f);
        private final LongAdder bytesSent = new LongAdder();
        private final RateStatistics sendBitRate = new RateStatistics(60000);

        void packetReceived(int size, Instant time)
        {
            long timeMs = time.toEpochMilli();
            packetsReceived++;
            receivePacketRate.update(1, timeMs);
            bytesReceived += size;
            receiveBitRate.update(size, timeMs);
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
            sendBitRate.update(size, timeMs);
        }

        OrderedJsonObject toJson()
        {
            OrderedJsonObject debugState = new OrderedJsonObject();
            debugState.put("packets_received", packetsReceived);
            debugState.put("receive_packet_rate_pps", receivePacketRate.getRate());
            debugState.put("bytes_received", bytesReceived);
            debugState.put("receive_bitrate_bps", receiveBitRate.getRate());
            debugState.put("incoming_packets_dropped", incomingPacketsDropped);

            debugState.put("packets_sent", packetsSent.sum());
            debugState.put("send_packet_rate_pps", sendPacketRate.getRate());
            debugState.put("bytes_sent", bytesSent.sum());
            debugState.put("send_bitrate_bps", sendBitRate.getRate());

            return debugState;
        }
    }

    interface IncomingOctoEpPacketHandler {
        void handleIncomingPacket(@NotNull OctoPacketInfo packetInfo);
    }
}
