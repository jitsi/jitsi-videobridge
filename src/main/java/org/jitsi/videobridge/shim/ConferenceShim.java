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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * Handles Colibri-related logic for a {@link Conference}, e.g.
 * creates/expires contents, describes the conference in XML.
 *
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class ConferenceShim
{
    public final Conference conference;

    private final Map<MediaType, ContentShim> contents = new HashMap<>();
    private final Map<String, ChannelBundleShim> channelBundles = new HashMap<>();

    public ConferenceShim(Conference conference)
    {
        this.conference = conference;
        System.out.println("ConferenceShim created conference " + conference.getID());
    }

    public ContentShim getOrCreateContent(MediaType type) {
        synchronized (contents)
        {
            return contents.computeIfAbsent(type,
                    key -> new ContentShim(getConference(), type));
        }
    }
    public Conference getConference()
    {
        return conference;
    }

    public Collection<ContentShim> getContents()
    {
        synchronized (contents)
        {
            return new ArrayList<>(contents.values());
        }
    }

    public AbstractEndpoint getOrCreateEndpoint(String endpointId)
    {
        System.out.println("ConferenceShim " + getId() + " creating endpoint " + endpointId);
        return conference.getOrCreateEndpoint(endpointId);
    }

    public ChannelBundleShim getOrCreateChannelBundle(String channelBundleId)
    {
        synchronized (channelBundles)
        {
            return channelBundles.computeIfAbsent(
                    channelBundleId,
                    id -> new ChannelBundleShim(conference, channelBundleId));
        }
    }

    public ChannelBundleShim getChannelBundle(String channelBundleId)
    {
        synchronized (channelBundles)
        {
            return channelBundles.get(channelBundleId);
        }
    }

    public Collection<AbstractEndpoint> getEndpoints()
    {
        return conference.getEndpoints();
    }

    public boolean shouldExpire()
    {
        return conference.shouldExpire();
    }

    /**
     * Expire this conference and everything within it
     * NOTE: this should only be called from {@link ColibriShim} so that the conference shim can be removed
     * from the list of conferences
     */
    private void expire()
    {
        synchronized (contents)
        {
            contents.values().forEach(ContentShim::expire);
            contents.clear();
        }
        synchronized (channelBundles)
        {
            channelBundles.clear();
        }

        conference.getEndpoints().forEach(AbstractEndpoint::expire);
        conference.safeExpire();
    }

    public void describeChannelBundles(ColibriConferenceIQ iq, Set<String> channelBundleIdsToDescribe)
    {
        synchronized (channelBundles)
        {
            channelBundleIdsToDescribe.forEach(bundleId -> {
                ChannelBundleShim channelBundle = channelBundles.get(bundleId);
                if (channelBundle != null)
                {
                    ColibriConferenceIQ.ChannelBundle responseBundleIQ
                            = new ColibriConferenceIQ.ChannelBundle(bundleId);
                    channelBundle.describe(responseBundleIQ);

                    iq.addChannelBundle(responseBundleIQ);
                }
            });
        }
    }

    /**
     * Adds the endpoint of this <tt>Conference</tt> as
     * <tt>ColibriConferenceIQ.Endpoint</tt> instances in <tt>iq</tt>.
     * @param iq the <tt>ColibriConferenceIQ</tt> in which to describe.
     */
    void describeEndpoints(ColibriConferenceIQ iq)
    {
        getEndpoints().forEach(
                en -> iq.addEndpoint(
                        new ColibriConferenceIQ.Endpoint(
                                en.getID(), en.getStatsId(), en.getDisplayName())));
    }

    public void describeShallow(ColibriConferenceIQ iq)
    {
        iq.setID(conference.getID());
        iq.setName(conference.getName());
    }

    public String getId()
    {
        return conference.getID();
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     * <p>
     * <b>Note</b>: The copying of the values is deep i.e. the
     * <tt>Contents</tt>s of this instance are described in the specified
     * <tt>iq</tt>.
     * </p>
     *
     * @param iq the <tt>ColibriConferenceIQ</tt> to set the values of the
     * properties of this instance on
     */
    //TODO(brian): port this logic over to the shim
    public void describeDeep(ColibriConferenceIQ iq)
    {
        describeShallow(iq);

//        for (Content content : getContents())
//        {
//            ColibriConferenceIQ.Content contentIQ
//                = iq.getOrCreateContent(content.getName());
//
//            for (Channel channel : content.getChannels())
//            {
//                ColibriConferenceIQ.Channel channelIQ
//                    = new ColibriConferenceIQ.Channel();
//
//                channel.describe(channelIQ);
//                contentIQ.addChannel(channelIQ);
//            }
//        }
    }

}
