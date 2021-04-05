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
import org.jitsi.nlj.stats.*;
import org.jitsi.nlj.transform.*;
import org.jitsi.nlj.util.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.octo.config.*;
import org.json.simple.*;

import java.util.*;

/**
 * Parses and handles incoming RTP/RTCP packets from an Octo source for a
 * specific {@link Conference}/{@link ConfOctoTransport}.
 *
 * @author Boris Grozev
 */
public class OctoTransceiver implements Stoppable, NodeStatsProducer
{
    /**
     * The {@link Logger} used by the {@link OctoTransceiver} class to print
     * debug information.
     */
    private final Logger logger;

    private final String id;

    /**
     * The set of media stream tracks that have been signaled to us.
     */
    private final MediaSources mediaSources = new MediaSources();

    private final StreamInformationStore streamInformationStore
            = new StreamInformationStoreImpl();

    final OctoRtpReceiver octoReceiver;

    private final OctoRtpSender octoSender;

    OctoTransceiver(String id, Logger parentLogger)
    {
        this(id, null, parentLogger);
    }

    /**
     * Initializes a new {@link OctoTransceiver} instance.
     */
    OctoTransceiver(String id, TransceiverEventHandler eventHandler, Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(this.getClass().getName());
        this.id = id;
        this.octoReceiver = new OctoRtpReceiver(streamInformationStore, eventHandler, logger);
        this.octoSender = new OctoRtpSender(streamInformationStore, logger);
    }

    void setIncomingPacketHandler(PacketHandler handler)
    {
        octoReceiver.setPacketHandler(handler);
    }

    void setOutgoingPacketHandler(PacketHandler handler)
    {
        octoSender.onOutgoingPacket(handler);
    }

    void requestKeyframe(long mediaSsrc)
    {
        octoSender.requestKeyframe(mediaSsrc);
    }

    void requestKeyframe()
    {
        octoSender.requestKeyframe(null);
    }

    boolean receivesSsrc(long ssrc)
    {
        return streamInformationStore.getReceiveSsrcs().contains(ssrc);
    }

    void setReceiveSsrcs(Map<MediaType, Set<Long>> ssrcsByMediaType)
    {
        streamInformationStore.getReceiveSsrcs().forEach(streamInformationStore::removeReceiveSsrc);
        ssrcsByMediaType.forEach((mediaType, ssrcs) -> {
            ssrcs.forEach(ssrc -> {
                streamInformationStore.addReceiveSsrc(ssrc, mediaType);
            });
        });
    }

    boolean hasReceiveSsrcs()
    {
        return !streamInformationStore.getReceiveSsrcs().isEmpty();
    }

    /**
     * Sets the list of media stream tracks that is signalled to us. Note that
     * because of quirks in the underlying implementation, a subsequent call to
     * {@link #getMediaSources()} is necessary to get the currently used
     * tracks.
     *
     * @param tracks the tracks to set
     *
     * @return {@code true} if the call resulted in any changes in our list of
     * tracks, and {@code false} otherwise.
     */
    boolean setMediaSources(MediaSourceDesc[] tracks)
    {
        boolean changed = mediaSources.setMediaSources(tracks);

        if (changed)
        {

            SetMediaSourcesEvent setMediaSourcesEvent
                    = new SetMediaSourcesEvent(getMediaSources());

            octoReceiver.handleEvent(setMediaSourcesEvent);
        }

        return changed;
    }

    /**
     * @return the current list of media stream tracks.
     */
    @NotNull
    MediaSourceDesc[] getMediaSources()
    {
        return mediaSources.getMediaSources();
    }

    public void handleIncomingPacket(@NotNull OctoPacketInfo packetInfo)
    {
        octoReceiver.enqueuePacket(packetInfo);
    }

    void sendPacket(PacketInfo packetInfo)
    {
        octoSender.processPacket(packetInfo);
    }

    /**
     * Adds a payload type to this transceiver.
     *
     * @param payloadType the payload type to add
     */
    void addPayloadType(PayloadType payloadType)
    {
        streamInformationStore.addRtpPayloadType(payloadType);
    }

    /**
     * Adds an RTP header extension to this transceiver.
     * @param rtpExtension the extension to add
     */
    void addRtpExtension(RtpExtension rtpExtension)
    {
        streamInformationStore.addRtpExtensionMapping(rtpExtension);
    }

    @Override
    public void stop()
    {
        octoReceiver.stop();
        octoReceiver.tearDown();
        octoSender.stop();
        octoSender.tearDown();
    }

    @NotNull
    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock nodeStats = new NodeStatsBlock("OctoTransceiver " + id);
        nodeStats.addBlock(streamInformationStore.getNodeStats());
        nodeStats.addBlock(octoReceiver.getNodeStats());
        nodeStats.addBlock(octoSender.getNodeStats());
        nodeStats.addBlock(mediaSources.getNodeStats());

        return nodeStats;
    }

    boolean isReceivingAudio()
    {
        return octoReceiver.isReceivingAudio();
    }

    boolean isReceivingVideo()
    {
        return octoReceiver.isReceivingVideo();
    }
}
