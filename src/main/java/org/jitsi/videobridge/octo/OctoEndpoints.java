/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;
import org.jitsi.videobridge.*;

import java.util.*;
import java.util.stream.*;

/**
 * Manages the list of remote/foreign/Octo endpoints for a specific
 * {@link Conference}.
 *
 * @author Boris Grozev
 */
public class OctoEndpoints
{
    /**
     * The {@link Logger} used by the {@link RtpChannel} class to print debug
     * information. Note that instances should use {@link #logger} instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(OctoEndpoints.class);

    /**
     * The owning conference.
     */
    private Conference conference;

    /**
     * The conference's Octo channel for audio, if there is one, or {@code null}.
     */
    private OctoChannel audioChannel;

    /**
     * The conference's Octo channel for video, if there is one, or {@code null}.
     */
    private OctoChannel videoChannel;

    /**
     * Used to synchronize access to the {@link Endpoint}s of the conference
     * while accessed through {@link OctoEndpoints}. Note that some of the
     * operations access the {@link Conference}'s list of endpoints which
     * uses a separate lock. Therefore, to avoid possible deadlocks this
     * class' API MUST NOT be called while holding a lock on the conference
     * endpoints list.
     */
    private final Object endpointsSyncRoot = new Object();

    /**
     * The {@link OctoEndpointMessageTransport} used to parse and handle
     * incoming data messages from Octo.
     */
    final OctoEndpointMessageTransport messageTransport
        = new OctoEndpointMessageTransport(this);

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    public OctoEndpoints(Conference conference)
    {
        this.conference = conference;
        logger = Logger.getLogger(classLogger, conference.getLogger());
    }

    /**
     * @return  the {@link Conference} associated with this
     * {@link OctoEndpoints}.
     */
    Conference getConference()
    {
        return conference;
    }

    /**
     * Removes all endpoints managed by this instance.
     */
    private void removeAll()
    {
        synchronized (endpointsSyncRoot)
        {
            List<OctoEndpoint> octoEndpoints = getOctoEndpoints();
            octoEndpoints.forEach(OctoEndpoint::expire);
        }
    }

    /**
     * Gets the list of all Octo endpoints from the associated
     * {@link Conference}. That is returns all conference endpoints which are
     * instances of {@link OctoEndpoint}.
     * @return the list of all {@link OctoEndpoint}s in the conference.
     */
    private List<OctoEndpoint> getOctoEndpoints()
    {
        return
            conference.getEndpoints().stream()
                .filter(e -> e instanceof OctoEndpoint)
                .map(e -> (OctoEndpoint) e)
                .collect(Collectors.toList());
    }

    /**
     * Sets the {@link OctoChannel} for a particular media type.
     * @param mediaType the media type of the channel.
     * @param channel the channel.
     */
    void setChannel(MediaType mediaType, OctoChannel channel)
    {
        synchronized (endpointsSyncRoot)
        {
            List<OctoEndpoint> octoEndpoints = getOctoEndpoints();
            if (MediaType.VIDEO.equals(mediaType))
            {
                if (videoChannel != null)
                {
                    logger.error("Replacing an existing video channel");

                    octoEndpoints.forEach(e -> e.removeChannel(videoChannel));
                }
                videoChannel = channel;
                if (channel != null)
                {
                    octoEndpoints.forEach(e -> e.addChannel(videoChannel));
                }
            }
            else if (MediaType.AUDIO.equals(mediaType))
            {
                if (audioChannel != null)
                {
                    logger.error("Replacing an existing audio channel");

                    octoEndpoints.forEach(e -> e.removeChannel(audioChannel));
                }
                audioChannel = channel;
                if (channel != null)
                {
                    octoEndpoints.forEach(e -> e.addChannel(audioChannel));
                }
            }
            else
            {
                throw new IllegalArgumentException("mediaType: " + mediaType);
            }

            // If the channels have been removed/expired, remove all Octo
            // endpoints from the conference.
            if (videoChannel == null && audioChannel == null)
            {
                removeAll();
            }
        }
    }

    /**
     * Updates the list of {@link OctoEndpoint}s maintained by this instance.
     * The list {@code endpointIds} specifies the current list of endpoints
     * associated with a particular channel. If the list contains IDs which
     * this instance does not know about, then an associated endpoint should
     * be added. But if an {@link OctoEndpoint}'s ID is missing from the list,
     * this does not necessarily mean that we should expire/remove it (because
     * it might have tracks in the other channel).
     */
    void updateEndpoints(Set<String> endpointIds)
    {
        synchronized (endpointsSyncRoot)
        {
            List<OctoEndpoint> octoEndpoints = getOctoEndpoints();
            List<String> octoEndpointIds
                = octoEndpoints.stream()
                    .map(AbstractEndpoint::getID)
                    .collect(Collectors.toList());

            // Create new OctoEndpoint instances for endpoint IDs which we
            // don't yet have in the conference.
            endpointIds.removeAll(octoEndpointIds);
            endpointIds.forEach(this::addEndpoint);

            // Some of the existing endpoints might have their underlying state
            // (i.e. list of tracks) updated. Expire the ones which don't have
            // any tracks anymore.
            octoEndpoints.forEach(OctoEndpoint::maybeExpire);

        }
    }

    /**
     * Creates a new {@link OctoEndpoint} instance and adds it to the
     * conference.
     * @param id the ID for the new instance.
     * @return the newly created instance.
     */
    private OctoEndpoint addEndpoint(String id)
    {
        OctoEndpoint endpoint;
        synchronized (endpointsSyncRoot)
        {
            endpoint = new OctoEndpoint(conference, id);
            if (audioChannel != null)
            {
                endpoint.addChannel(audioChannel);
            }
            if (videoChannel != null)
            {
                endpoint.addChannel(videoChannel);
            }
        }

        conference.addEndpoint(endpoint);

        return endpoint;
    }

    /**
     * Finds an {@link OctoEndpoint} in this conference which owns a particular
     * SSRC (i.e. one of its tracks contains the SSRC).
     * @param ssrc the SSRC.
     * @return the {@link OctoEndpoint} which owns SSRC {@code ssrc}, or
     * {@code null}.
     */
    AbstractEndpoint findEndpoint(long ssrc)
    {
        synchronized (endpointsSyncRoot)
        {
            return
                getOctoEndpoints()
                    .stream()
                    .filter(e -> e.getMediaStreamTracks().stream().anyMatch(
                        track -> track.matches(ssrc)))
                    .findFirst().orElse(null);
        }
    }

    /**
     * Sends a message through the Octo channel.
     * @param msg the message to send.
     */
    public void sendMessage(String msg)
    {
        OctoChannel channel = audioChannel;
        if (channel == null)
        {
            channel = videoChannel;
        }

        if (channel != null)
        {
            channel.sendMessage(msg, null);
        }
        else
        {
            logger.warn("Can not send a message, no channels.");
        }
    }
}
