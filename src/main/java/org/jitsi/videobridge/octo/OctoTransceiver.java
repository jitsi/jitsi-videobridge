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
import org.jitsi.nlj.transform.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.transform.node.incoming.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.extensions.*;
import org.jitsi.rtp.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.util.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.json.simple.*;

import static org.jitsi.videobridge.octo.config.OctoConfig.*;

/**
 * Parses and handles incoming RTP/RTCP packets from an Octo source for a
 * specific {@link Conference}/{@link OctoTentacle}.
 *
 * @author Boris Grozev
 */
public class OctoTransceiver
        implements OctoRelay.PacketHandler
{
    /**
     * The {@link Logger} used by the {@link OctoTransceiver} class to print
     * debug information.
     */
    private final Logger logger;

    /**
     * Count the number of dropped packets and exceptions.
     */
    public static final CountingErrorHandler queueErrorCounter
            = new CountingErrorHandler();

    /**
     * The owning tentacle.
     */
    private final OctoTentacle tentacle;

    /**
     * The queue which passes packets through the incoming chain/tree.
     */
    private final PacketInfoQueue incomingPacketQueue;

    /**
     * Statistics observer for the incoming packet queue.
     */
    private final QueueStatistics queueStatistics;

    /**
     * The tree of {@link Node}s which handles incoming packets.
     */
    private final Node inputTreeRoot;

    /**
     * The set of media stream tracks that have been signaled to us.
     */
    private final MediaStreamTracks mediaStreamTracks = new MediaStreamTracks();

    private final StreamInformationStore streamInformationStore
            = new StreamInformationStoreImpl();

    /**
     * Initializes a new {@link OctoTransceiver} instance.
     *
     * @param tentacle
     */
    OctoTransceiver(OctoTentacle tentacle, Logger parentLogger)
    {
        this.tentacle = tentacle;
        this.logger = parentLogger.createChildLogger(this.getClass().getName());
        inputTreeRoot = createInputTree();
        incomingPacketQueue = new PacketInfoQueue(
                "octo-tranceiver-incoming-packet-queue",
                TaskPools.CPU_POOL,
                this::processPacket,
                Config.queueSize());
        queueStatistics = new QueueStatistics(incomingPacketQueue);
        incomingPacketQueue.setErrorHandler(queueErrorCounter);
        incomingPacketQueue.setObserver(queueStatistics);
    }

    /**
     * Sets the list of media stream tracks that is signalled to us. Note that
     * because of quirks in the underlying implementation, a subsequent call to
     * {@link #getMediaStreamTracks()} is necessary to get the currently used
     * tracks.
     *
     * @param tracks the tracks to set
     *
     * @return {@code true} if the call resulted in any changes in our list of
     * tracks, and {@code false} otherwise.
     */
    boolean setMediaStreamTracks(MediaStreamTrackDesc[] tracks)
    {
        boolean changed = mediaStreamTracks.setMediaStreamTracks(tracks);

        if (changed)
        {

            SetMediaStreamTracksEvent setMediaStreamTracksEvent
                    = new SetMediaStreamTracksEvent(getMediaStreamTracks());

            new NodeEventVisitor(setMediaStreamTracksEvent).visit(inputTreeRoot);
        }

        return changed;
    }

    /**
     * @return the current list of media stream tracks.
     */
    MediaStreamTrackDesc[] getMediaStreamTracks()
    {
        return mediaStreamTracks.getMediaStreamTracks();
    }

    /**
     * Handles a packet for this conference coming from a remote Octo relay.
     *
     * @param packet the packet.
     */
    @Override
    public void handlePacket(Packet packet, String sourceEndpointId)
    {
        PacketInfo packetInfo = new OctoPacketInfo(packet);
        packetInfo.setReceivedTime(System.currentTimeMillis());
        packetInfo.setEndpointId(sourceEndpointId);
        incomingPacketQueue.add(packetInfo);
    }
    @Override
    public void handleMessage(String message)
    {
        tentacle.handleMessage(message);
    }

    /**
     * Process a packet in the {@link #incomingPacketQueue} thread.
     *
     * @param packetInfo the packet to process.
     *
     * @return
     */
    private boolean processPacket(PacketInfo packetInfo)
    {
        inputTreeRoot.processPacket(packetInfo);
        return true; // what are the semantics of the PacketQueue handler?
    }

    /**
     * Creates the tree of {@link Node} to use for processing incoming packets.
     *
     * @return
     */
    private Node createInputTree()
    {
        // TODO: we need a better scheme for creating these in Java. Luckily
        // the tree for Octo is not very complex.
        Node terminationNode = new ConsumerNode("Octo termination node")
        {
            @NotNull
            @Override
            protected void consume(@NotNull PacketInfo packetInfo)
            {
                tentacle.handleIncomingPacket(packetInfo);
            }

            @Override
            public void trace(@NotNull Function0<Unit> f)
            {
                f.invoke();
            }
        };

        Node videoRoot = new VideoParser(streamInformationStore, logger);
        videoRoot.attach(new Vp8Parser(logger))
            .attach(new VideoBitrateCalculator(logger))
            .attach(terminationNode);

        AudioLevelReader audioLevelReader
                = new AudioLevelReader(streamInformationStore);
        audioLevelReader.setAudioLevelListener(tentacle.getAudioLevelListener());

        Node audioRoot = audioLevelReader;
        audioRoot.attach(terminationNode);

        DemuxerNode audioVideoDemuxer
                = new ExclusivePathDemuxer("Audio/Video")
                .addPacketPath(
                        "Video",
                        pkt -> pkt instanceof VideoRtpPacket,
                        videoRoot)
                .addPacketPath(
                        "Audio",
                        pkt -> pkt instanceof AudioRtpPacket,
                        audioRoot);

        Node rtpRoot = new RtpParser(streamInformationStore, logger);
        rtpRoot.attach(audioVideoDemuxer);

        // We currently only have single RTCP packets in Octo.
        Node rtcpRoot = new SingleRtcpParser(logger);
        rtcpRoot.attach(terminationNode);
        DemuxerNode root
            = new ExclusivePathDemuxer("RTP/RTCP")
                .addPacketPath(
                        "RTP",
                        PacketExtensionsKt::looksLikeRtp,
                        rtpRoot)
                .addPacketPath(
                        "RTCP",
                        PacketExtensionsKt::looksLikeRtcp,
                        rtcpRoot);

        return root;
    }

    /**
     * Adds a payload type to this transceiver.
     *
     * @param payloadType
     */
    void addPayloadType(PayloadType payloadType)
    {
        streamInformationStore.addRtpPayloadType(payloadType);
    }

    StreamInformationStore getStreamInformationStore()
    {
        return streamInformationStore;
    }

    /**
     * Adds an RTP header extension to this transceiver.
     * @param extensionId
     * @param rtpExtension
     */
    void addRtpExtension(RtpExtension rtpExtension)
    {
        streamInformationStore.addRtpExtensionMapping(rtpExtension);
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    JSONObject getDebugState()
    {

        JSONObject debugState = new JSONObject();
        NodeSetVisitor nodeSetVisitor = new NodeSetVisitor();
        nodeSetVisitor.visit(inputTreeRoot);

        nodeSetVisitor.getNodeSet().forEach(
                node -> debugState.put(
                        node.getName(),
                        node.getNodeStats().toJson()));

        debugState.put("incomingPacketQueue", incomingPacketQueue.getDebugState().put("statistics", queueStatistics.getStats()));
        debugState.put("mediaStreamTracks", mediaStreamTracks.getNodeStats().toJson());
        return debugState;
    }
}
