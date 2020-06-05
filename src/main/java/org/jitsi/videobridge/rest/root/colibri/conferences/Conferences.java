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

package org.jitsi.videobridge.rest.root.colibri.conferences;

import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.rest.exceptions.*;
import org.jitsi.videobridge.rest.root.colibri.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jivesoftware.smack.packet.*;
import org.json.simple.*;
import org.json.simple.parser.*;

import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.*;

@Path("/colibri/conferences")
public class Conferences extends ColibriResource
{
    @Inject
    private VideobridgeProvider videobridgeProvider;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getConferences()
    {
        Videobridge videobridge = videobridgeProvider.get();
        List<ColibriConferenceIQ> conferenceIQs = new ArrayList<>();

        for (Conference conference : videobridge.getConferences())
        {
            ColibriConferenceIQ conferenceIQ = new ColibriConferenceIQ();

            conferenceIQ.setID(conference.getID());
            conferenceIQs.add(conferenceIQ);
        }

        JSONArray conferencesJSONArray
                = JSONSerializer.serializeConferences(conferenceIQs);

        if (conferencesJSONArray == null)
        {
            conferencesJSONArray = new JSONArray();
        }


        return conferencesJSONArray.toJSONString();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{confId}")
    public String getConference(@PathParam("confId") String confId)
    {
        Conference conference = videobridgeProvider.get().getConference(confId);

        if (conference == null)
        {
            throw new NotFoundException();
        }

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
        Conference conference = videobridgeProvider.get().getConference(confId);

        if (conference == null)
        {
            throw new NotFoundException();
        }

        ConferenceSpeechActivity conferenceSpeechActivity
                = conference.getSpeechActivity();

        return conferenceSpeechActivity.doGetDominantSpeakerIdentificationJSON().toJSONString();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String createConference(String requestBody)
    {
        Object requestJson;
        try
        {
            requestJson = new JSONParser().parse(requestBody);
            if (!(requestJson instanceof JSONObject))
            {
                throw new BadRequestException();
            }
        }
        catch (ParseException pe)
        {
            throw new BadRequestExceptionWithMessage(
                    "Failed to create conference, could not parse JSON: " + pe.getMessage());
        }

        ColibriConferenceIQ requestConferenceIQ
                = JSONDeserializer.deserializeConference((JSONObject) requestJson);

        if ((requestConferenceIQ == null) || requestConferenceIQ.getID() != null)
        {
            throw new BadRequestExceptionWithMessage("Must not include conference ID");
        }

        return getVideobridgeIqResponseAsJson(requestConferenceIQ);
    }

    @PATCH
    @Path("/{confId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String patchConference(@PathParam("confId") String confId, String requestBody)
    {
        Conference conference = videobridgeProvider.get().getConference(confId);
        if (conference == null)
        {
            throw new NotFoundException();
        }
        Object requestJson;
        try
        {
            requestJson = new JSONParser().parse(requestBody);
        }
        catch (ParseException e)
        {
            throw new BadRequestException();
        }

        ColibriConferenceIQ requestIq = JSONDeserializer.deserializeConference((JSONObject)requestJson);

        if (requestIq == null || ((requestIq.getID() != null) &&
                !requestIq.getID().equalsIgnoreCase(conference.getID())))
        {
            throw new BadRequestException();
        }

        return getVideobridgeIqResponseAsJson(requestIq);
    }

    private String getVideobridgeIqResponseAsJson(ColibriConferenceIQ request)
    {
        IQ responseIq = videobridgeProvider.get()
                .handleColibriConferenceIQ(request);

        if (responseIq.getError() != null)
        {
            throw new BadRequestExceptionWithMessage(
                    "Failed to create conference: " + responseIq.getError().getDescriptiveText());
        }

        if (!(responseIq instanceof ColibriConferenceIQ))
        {
            throw new InternalServerErrorExceptionWithMessage("Non-error, non-colibri IQ result");
        }

        JSONObject responseJson = JSONSerializer.serializeConference((ColibriConferenceIQ)responseIq);

        if (responseJson == null)
        {
            responseJson = new JSONObject();
        }

        return responseJson.toJSONString();
    }
}
