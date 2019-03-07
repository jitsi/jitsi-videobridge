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
import org.jitsi.nlj.transform.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.transform.node.incoming.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.*;
import org.jitsi.rtp.rtp.*;
import org.jitsi.rtp.util.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.util.*;
import org.jitsi_modified.impl.neomedia.rtp.*;

import java.nio.*;

/**
 * Parses and handles incoming RTP/RTCP packets from an Octo source for a
 * specific {@link Conference}/{@link OctoTentacle}.
 *
 * @author Boris Grozev
 */
class OctoTransceiver
        implements OctoRelay.PacketHandler
{
    /**
     * The {@link Logger} used by the {@link OctoTransceiver} class to print
     * debug information.
     */
    private static final Logger logger = Logger.getLogger(OctoTransceiver.class);

    /**
     * The owning tentacle.
     */
    private final OctoTentacle tentacle;

    /**
     * The queue which passes packets through the incoming chain/tree.
     */
    private PacketInfoQueue incomingPacketQueue;

    /**
     * The tree of {@link Node}s which handles incoming packets.
     */
    private final Node inputTreeRoot = createInputTree();

    /**
     * The set of media stream tracks that have been signaled to us.
     */
    private final MediaStreamTracks mediaStreamTracks = new MediaStreamTracks();

    /**
     * Initializes a new {@link OctoTransceiver} instance.
     * @param tentacle
     */
    OctoTransceiver(OctoTentacle tentacle)
    {
        this.tentacle = tentacle;
        incomingPacketQueue = new PacketInfoQueue(
            "octo-tranceiver-q-"+hashCode(),
            TaskPools.CPU_POOL,
            this::processPacket);
    }

    /**
     * Sets the list of media stream tracks that is signalled to us. Note that
     * because of quirks in the underlying implementation, a subsequent call to
     * {@link #getMediaStreamTracks()} is necessary to get the currently used
     * tracks.
     *
     * @param tracks the tracks to set
     * @return {@code true} if the call resulted in any changes in our list
     * of tracks, and {@code false} otherwise.
     */
    boolean setMediaStreamTracks(MediaStreamTrackDesc[] tracks)
    {
        return mediaStreamTracks.setMediaStreamTracks(tracks);
    }

    /**
     * Gets the current list of
     * @return
     */
    MediaStreamTrackDesc[] getMediaStreamTracks()
    {
        return mediaStreamTracks.getMediaStreamTracks();
    }

    /**
     * Handles a packet for this conference coming from a remote Octo relay.
     * @param buf the buffer which contains the packet.
     * @param off the offset at which data starts
     * @param len the length of the packet
     */
    @Override
    public void handlePacket(byte[] buf, int off, int len)
    {
        // XXX memory management
        ByteBuffer packetBuf = ByteBuffer.allocate(len);
        packetBuf.put(ByteBuffer.wrap(buf, off, len)).flip();
        Packet pkt = new UnparsedPacket(packetBuf);
        PacketInfo pktInfo = new PacketInfo(pkt);
        pktInfo.setReceivedTime(System.currentTimeMillis());
        incomingPacketQueue.add(pktInfo);
    }

    /**
     * Process a packet in the {@link #incomingPacketQueue} thread.
     * @param packetInfo the packet to process.
     * @return
     */
    private boolean processPacket(PacketInfo packetInfo)
    {
        inputTreeRoot.processPacket(packetInfo);
        return true; // what are the semantics of the PacketQueue handler?
    }

    /**
     * Creates the tree of {@link Node} to use for processing incoming packets.
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
                tentacle.handleIncomingRtp(packetInfo);
            }
        };

        Node videoRoot = new VideoParser();
        videoRoot.attach(new Vp8Parser()).attach(terminationNode);

        Node audioRoot = new AudioLevelReader();
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

        Node rtpRoot
            = new PacketParser(
                "RTP parser",
                packet -> RtpPacket.Companion.fromBuffer(packet.getBuffer()));
        rtpRoot.attach(new MediaTypeParser()).attach(audioVideoDemuxer);

        DemuxerNode root
            = new ExclusivePathDemuxer("RTP/RTCP")
                .addPacketPath(
                    "RTP",
                    pkt -> RtpProtocol.Companion.isRtp(pkt.getBuffer()),
                    rtpRoot)
                .addPacketPath(
                    "RTCP",
                    pkt -> RtpProtocol.Companion.isRtcp(pkt.getBuffer()),
                    new ConsumerNode("OctoRTCPHandler (no-op)")
                    {
                        /**
                         * Are we going to need this in the future?
                         */
                        @NotNull
                        @Override
                        protected void consume(PacketInfo packetInfo)
                        {
                            logger.info("Ignoring an RTCP packet ");
                        }
                    });

        return root;
    }

    /**
     * Adds a payload type to this transceiver.
     * @param payloadType
     */
    void addPayloadType(PayloadType payloadType)
    {
        RtpPayloadTypeAddedEvent event
                = new RtpPayloadTypeAddedEvent(payloadType);
        new NodeEventVisitor(event).visit(inputTreeRoot);
    }
}
