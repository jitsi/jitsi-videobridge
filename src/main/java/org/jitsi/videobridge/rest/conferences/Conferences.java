/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge.rest.conferences;

import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.json.simple.*;

import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.*;

@Path("/")
public class Conferences
{
    private final VideobridgeProvider videobridgeProvider;

    public Conferences(VideobridgeProvider videobridgeProvider)
    {
        this.videobridgeProvider = videobridgeProvider;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getConferences()
    {
        Videobridge videobridge = videobridgeProvider.get();
        Conference[] conferences = videobridge.getConferences();
        List<ColibriConferenceIQ> conferenceIQs = new ArrayList<>();

        for (Conference conference : conferences)
        {
            ColibriConferenceIQ conferenceIQ = new ColibriConferenceIQ();

            conferenceIQ.setID(conference.getID());
            conferenceIQs.add(conferenceIQ);
        }

        JSONArray conferencesJSONArray
                = JSONSerializer.serializeConferences(conferenceIQs);

        if (conferencesJSONArray == null)
            conferencesJSONArray = new JSONArray();


        return conferencesJSONArray.toJSONString();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{confId}")
    public String getConference(@PathParam("confId") String confId)
    {
        Conference conference
                = videobridgeProvider.get().getConference(confId, null);

        ColibriConferenceIQ conferenceIQ = new ColibriConferenceIQ();

        conference.getShim().describeDeep(conferenceIQ);

        JSONObject conferenceJSONObject
                = JSONSerializer.serializeConference(conferenceIQ);

        return conferenceJSONObject.toJSONString();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{confId}/dominant-speaker-identification")
    public String getDominantSpeakerIdentification(@PathParam("confId") String confId)
    {
        Conference conference
                = videobridgeProvider.get().getConference(confId, null);

        ConferenceSpeechActivity conferenceSpeechActivity
                = conference.getSpeechActivity();

        return conferenceSpeechActivity.doGetDominantSpeakerIdentificationJSON().toJSONString();
    }
}
