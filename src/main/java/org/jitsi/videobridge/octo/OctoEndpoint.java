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

import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi.utils.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * Represents an endpoint in a conference, which is connected to another
 * jitsi-videobridge instance.
 *
 * @author Boris Grozev
 */
public class OctoEndpoint
    extends AbstractEndpoint
{
    OctoEndpoint(Conference conference, String id)
    {
        super(conference, id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendMessage(String msg)
    {
        // This is intentionally a no-op. Since a conference can have
        // multiple OctoEndpoint instances, but we want a single message
        // to be sent through Octo, the message should be sent through the
        // single OctoEndpoints instance.
    }

    /**
     * {@inheritDoc}
     * </p>
     * {@link OctoEndpoint}s are added/removed solely based on signaling. An
     * endpoint is expired when the signaled media stream tracks for the
     * Octo channels do not include any tracks for this endpoint.
     */
    @Override
    protected void maybeExpire()
    {
        MediaStreamTrackDesc[] audioTracks
            = getMediaStreamTracks(MediaType.AUDIO);
        MediaStreamTrackDesc[] videoTracks
            = getMediaStreamTracks(MediaType.VIDEO);

        if (ArrayUtils.isNullOrEmpty(audioTracks)
            && ArrayUtils.isNullOrEmpty(videoTracks))
        {
            expire();
        }
    }

    /**
     * @return the list of all {@link MediaStreamTrackDesc} (both audio and
     * video) of this endpoint.
     */
    List<MediaStreamTrackDesc> getMediaStreamTracks()
    {
        List<MediaStreamTrackDesc> tracks = new LinkedList<>();
        tracks.addAll(Arrays.asList(getMediaStreamTracks(MediaType.AUDIO)));
        tracks.addAll(Arrays.asList(getMediaStreamTracks(MediaType.VIDEO)));

        return tracks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaStreamTrackDesc[] getMediaStreamTracks(MediaType mediaType)
    {
        // With Octo a channel can have tracks belonging to different endpoints,
        // so filter out only those that belong to this endpoint.
        String id = getID();
        return getAllMediaStreamTracks(mediaType).stream()
            .filter(track -> id.equals(track.getOwner()))
            .toArray(MediaStreamTrackDesc[]::new);
    }
}
