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

package org.jitsi.videobridge.rest.root.colibri.v2.conferences;

import org.jitsi.nlj.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.rest.annotations.*;
import org.jitsi.videobridge.rest.exceptions.*;
import org.jitsi.xmpp.extensions.colibri2.*;
import org.jitsi.xmpp.extensions.colibri2.json.*;
import org.jivesoftware.smack.packet.*;
import org.json.simple.*;
import org.json.simple.parser.*;

import jakarta.inject.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;

@Path("/colibri/v2/conferences")
@EnabledByConfig(RestApis.COLIBRI)
public class Conferences
{
    @Inject
    private Videobridge videobridge;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getConferences()
    {
        /* Not clear what this should do.  If you have a use case please contact us with requirements. */
        throw new ServerErrorException(Response.Status.NOT_IMPLEMENTED);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{meetingId}")
    public String getConference(@PathParam("meetingId") String meetingId)
    {
        Conference conference = videobridge.getConferenceByMeetingId(meetingId);

        if (conference == null)
        {
            throw new NotFoundException();
        }

        /* Not clear what this should do.  If you have a use case please contact us with requirements. */
        throw new ServerErrorException(Response.Status.NOT_IMPLEMENTED);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{meetingId}/dominant-speaker-identification")
    public String getDominantSpeakerIdentification(@PathParam("meetingId") String meetingId)
    {
        Conference conference = videobridge.getConferenceByMeetingId(meetingId);

        if (conference == null)
        {
            throw new NotFoundException();
        }

        ConferenceSpeechActivity conferenceSpeechActivity = conference.getSpeechActivity();

        return conferenceSpeechActivity.getDebugState(DebugStateMode.FULL).toJSONString();
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

        ConferenceModifyIQ conferenceModifyIQ;

        try
        {
            conferenceModifyIQ
                = Colibri2JSONDeserializer.deserializeConferenceModify((JSONObject) requestJson).build();
        }
        catch (Exception e)
        {
            throw new BadRequestExceptionWithMessage("Failed to parse ConferenceModify JSON:" + e.getMessage());
        }

        return getVideobridgeIqResponseAsJson(conferenceModifyIQ);
    }

    @PATCH
    @Path("/{meetingId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String patchConference(@PathParam("meetingId") String meetingId, String requestBody)
    {
        Conference conference = videobridge.getConferenceByMeetingId(meetingId);
        if (conference == null)
        {
            throw new NotFoundException();
        }
        Object requestJson;
        try
        {
            requestJson = new JSONParser().parse(requestBody);
            if (!(requestJson instanceof JSONObject))
            {
                throw new BadRequestException();
            }
        }
        catch (ParseException e)
        {
            throw new BadRequestException();
        }

        ConferenceModifyIQ.Builder conferenceModifyIQBuilder;
        try
        {
            conferenceModifyIQBuilder
                = Colibri2JSONDeserializer.deserializeConferenceModify((JSONObject) requestJson);
        }
        catch (Exception e)
        {
            throw new BadRequestExceptionWithMessage("Failed to parse ConferenceModify JSON:" + e.getMessage());
        }

        conferenceModifyIQBuilder.setMeetingId(meetingId);

        ConferenceModifyIQ conferenceModifyIQ = conferenceModifyIQBuilder.build();

        return getVideobridgeIqResponseAsJson(conferenceModifyIQ);
    }

    private String getVideobridgeIqResponseAsJson(ConferenceModifyIQ request)
    {
        IQ responseIq = videobridge.handleConferenceModifyIq(request);

        if (responseIq.getError() != null)
        {
            throw new BadRequestExceptionWithMessage(
                    "Failed to create conference: " + responseIq.getError().getDescriptiveText());
        }

        if (!(responseIq instanceof ConferenceModifiedIQ))
        {
            throw new InternalServerErrorExceptionWithMessage("Non-error, non-colibri IQ result");
        }

        JSONObject responseJson = Colibri2JSONSerializer.serializeConferenceModified((ConferenceModifiedIQ)responseIq);

        return responseJson.toJSONString();
    }
}
