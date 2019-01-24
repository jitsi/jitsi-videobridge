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
package org.jitsi.videobridge.shim;

import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * Handles Colibri-related logic for a {@code Content}, e.g.
 * creates and expires channels.
 *
 * @author Brian Baldino
 */
public class ContentShim
{
    private final MediaType type;
    private final Conference conference;
    private final Map<String, ChannelShim> channels = new HashMap<>();
    //TODO(brian): this used to be called 'initialLocalSSRC' in the content.  'Initial' presumably because
    // it could be changed in the event of a collision (I think by the other lib?).  Since we send it out
    // in the initial offer, assuming it's up to the other side not to conflict with us and therefore it
    // won't need to be changed.
    final long localSsrc = Videobridge.RANDOM.nextLong() & 0xffff_ffffL;

    public ContentShim(Conference conference, MediaType type)
    {
        this.type = type;
        this.conference = conference;
    }

    /**
     * Generates a new <tt>Channel</tt> ID which is not guaranteed to be unique.
     *
     * @return a new <tt>Channel</tt> ID which is not guaranteed to be unique
     */
    private String generateChannelID()
    {
        return
                Long.toHexString(
                        System.currentTimeMillis() + Videobridge.RANDOM.nextLong());
    }

    /**
     * @return a new channel ID, unique in the list of this {@link ContentShim}'s
     * channels.
     */
    private String generateUniqueChannelID()
    {
        synchronized (channels)
        {
            String id;
            do
            {
                id = generateChannelID();
            }
            while (channels.containsKey(id));

            return id;
        }
    }

    public MediaType getType() {
        return type;
    }


    public ChannelShim createRtpChannel(
            ConferenceShim conference,
            String endpointId,
            boolean isOcto) {
        synchronized (channels)
        {
            String channelId = generateUniqueChannelID();

            ChannelShim channelShim =
                    new ChannelShim(channelId, conference.getOrCreateEndpoint(endpointId), localSsrc, isOcto);
            channelShim.endpoint.setLocalSsrc(type, localSsrc);
            channels.put(channelId, channelShim);
            return channelShim;
        }
    }

    public SctpConnectionShim createSctpConnection(String conferenceId, String endpointId)
    {
        synchronized (channels)
        {
            AbstractEndpoint endpoint
                    = conference.getOrCreateEndpoint(endpointId);
            if (endpoint instanceof Endpoint)
            {
                String sctpConnId = generateUniqueChannelID();
                SctpConnectionShim connection = new SctpConnectionShim(sctpConnId, endpoint);
                channels.put(sctpConnId, connection);

                // Trigger the creation of the actual new SCTP connection
                ((Endpoint)endpoint).createSctpConnection();

                return connection;
            }
            else
            {
                throw new Error("Tried to create an SCTP connection on invalid ep type: " + endpoint.getClass());
            }
        }
    }

    public ChannelShim getChannel(String channelId)
    {
        synchronized (channels)
        {
            return channels.get(channelId);
        }
    }

    public Collection<ChannelShim> getChannels()
    {
        synchronized (channels)
        {
            return new ArrayList<>(channels.values());
        }
    }

    public SctpConnectionShim getSctpConnection(String id)
    {
        synchronized (channels)
        {
            ChannelShim channel = channels.get(id);
            if (channel instanceof SctpConnectionShim)
            {
                return (SctpConnectionShim)channel;
            }
            return null;
        }
    }

    void expire()
    {
        synchronized (channels)
        {
            channels.values().forEach(channel -> channel.setExpire(0));
            channels.clear();
        }
    }
}
