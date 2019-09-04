/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
import org.jitsi.nlj.util.*;
import org.jitsi.utils.logging2.*;
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
     * Information about the streams belonging to this {@link OctoEndpoint}
     */
    private StreamInformationStore streamInformationStore = new StreamInformationStoreImpl();

    /**
     * The {@link OctoEndpoints} instance for the conference.
     */
    private final OctoEndpoints octoEndpoints;

    /**
     * Initializes a new {@link OctoEndpoint} with a specific ID in a specific
     * conference.
     * @param conference the conference.
     * @param id the ID of the endpoint.
     */
    OctoEndpoint(Conference conference, String id, OctoEndpoints octoEndpoints, Logger parentLogger)
    {
        super(conference, id, parentLogger);

        this.octoEndpoints = octoEndpoints;
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
    public void requestKeyframe(long mediaSsrc)
    {
        // just making sure the tentacle hasn't expired
        OctoTentacle tentacle = getConference().getTentacle();
        if (tentacle != null)
        {
            tentacle.requestKeyframe(mediaSsrc);
        }
    }

    @Override
    public void requestKeyframe()
    {
        streamInformationStore.getPrimaryVideoSsrcs().stream().findFirst().ifPresent(this::requestKeyframe);
    }

    @Override
    public boolean shouldExpire()
    {
        return streamInformationStore.getReceiveSsrcs().isEmpty();
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
        if (epId.equalsIgnoreCase(getID()))
        {
            streamInformationStore.addSsrcAssociation(new LocalSsrcAssociation(primarySsrc, secondarySsrc, type));
        }
        else
        {
            streamInformationStore.addSsrcAssociation(new RemoteSsrcAssociation(primarySsrc, secondarySsrc, type));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean receivesSsrc(long ssrc)
    {
        return streamInformationStore.getReceiveSsrcs().contains(ssrc);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addReceiveSsrc(long ssrc, MediaType mediaType)
    {
        // This is controlled through setReceiveSsrcs.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void expire()
    {
        if (super.isExpired())
        {
            return;
        }

        octoEndpoints.endpointExpired(this);
        super.expire();
    }

    /**
     * Sets the set SSRCs we expect to receive from this endpoint.
     */
    void setReceiveSsrcs(Map<MediaType, Set<Long>> ssrcsByMediaType)
    {
        streamInformationStore.getReceiveSsrcs().forEach(streamInformationStore::removeReceiveSsrc);
        ssrcsByMediaType.forEach((mediaType, ssrcs) -> {
            ssrcs.forEach(ssrc -> streamInformationStore.addReceiveSsrc(ssrc, mediaType));
        });
    }
}
