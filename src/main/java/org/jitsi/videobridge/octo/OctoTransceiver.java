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
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.octo.config.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.json.simple.*;

/**
 * Parses and handles incoming RTP/RTCP packets from an Octo source for a
 * specific {@link Conference}/{@link OctoTentacle}.
 *
 * @author Boris Grozev
 */
public class OctoTransceiver {
    /**
     * The {@link Logger} used by the {@link OctoTransceiver} class to print
     * debug information.
     */
    private final Logger logger;

    /**
     * The set of media stream tracks that have been signaled to us.
     */
    private final MediaStreamTracks mediaStreamTracks = new MediaStreamTracks();

    private final StreamInformationStore streamInformationStore
            = new StreamInformationStoreImpl();

    private final OctoRtpReceiver octoReceiver;

    private final OctoRtpSender octoSender;

    /**
     * Initializes a new {@link OctoTransceiver} instance.
     */
    OctoTransceiver(Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(this.getClass().getName());
        this.octoReceiver = new OctoRtpReceiver(streamInformationStore, logger);
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

            octoReceiver.handleEvent(setMediaStreamTracksEvent);
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

    public void handleIncomingPacket(@NotNull OctoPacketInfo packetInfo)
    {
        octoReceiver.enqueuePacket(packetInfo);
    }

    void sendPacket(PacketInfo packetInfo)
    {
        octoSender.processPacket(packetInfo);
    }

    /**
     * Called when a local endpoint is expired.
     */
    public void endpointExpired(String endpointId)
    {
        octoSender.endpointExpired(endpointId);
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

    void setAudioLevelListener(AudioLevelListener audioLevelListener)
    {
        octoReceiver.setAudioLevelListener(audioLevelListener);
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    JSONObject getDebugState()
    {

        JSONObject debugState = new JSONObject();
        debugState.put("octoReceiver", octoReceiver.getDebugState());
        debugState.put("mediaStreamTracks", mediaStreamTracks.getNodeStats().toJson());
        return debugState;
    }
}
