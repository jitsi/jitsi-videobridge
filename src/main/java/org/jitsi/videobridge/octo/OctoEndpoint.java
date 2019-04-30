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

import org.jitsi.nlj.rtp.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.jitsi.utils.*;
import org.jitsi.videobridge.*;

import java.util.*;
import java.util.stream.*;

/**
 * Represents an endpoint in a conference, which is connected to another
 * jitsi-videobridge instance.
 *
 * @author Boris Grozev
 */
public class OctoEndpoint
    extends AbstractEndpoint
{
    /**
     * The SSRCs that this endpoint has.
     */
    private final Set<Long> receiveSsrcs = new HashSet<>();

    /**
     * Initializes a new {@link OctoEndpoint} with a specific ID in a specific
     * conference.
     * @param conference the conference.
     * @param id the ID of the endpoint.
     */
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

    @Override
    public boolean shouldExpire()
    {
        return ArrayUtils.isNullOrEmpty(getMediaStreamTracks());
    }

    /**
     * @return the list of all {@link MediaStreamTrackDesc} (both audio and
     * video) of this endpoint.
     */
    @Override
    public MediaStreamTrackDesc[] getMediaStreamTracks()
    {
        List<MediaStreamTrackDesc> l = Arrays.stream(getConference().getTentacle().transceiver.getMediaStreamTracks())
                .filter(t -> t.getOwner() == getID()).collect(Collectors.toList());
        return l.toArray(new MediaStreamTrackDesc[0]);
    }

    @Override
    public void onNewSsrcAssociation(
            String epId,
            long primarySsrc,
            long secondarySsrc,
            SsrcAssociationType type)
    {

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean receivesSsrc(long ssrc)
    {
        return receiveSsrcs.contains(ssrc);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addReceiveSsrc(long ssrc)
    {
        receiveSsrcs.add(ssrc);
    }

}
