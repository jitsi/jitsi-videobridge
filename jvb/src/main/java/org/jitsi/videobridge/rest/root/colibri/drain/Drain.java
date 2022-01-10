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

package org.jitsi.videobridge.rest.root.colibri.drain;

import org.eclipse.jetty.http.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.rest.annotations.*;
import org.json.simple.*;

import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

/**
 * A resource for putting the videobridge in drain via REST.
 */
@Path("/colibri/drain")
@EnabledByConfig(RestApis.DRAIN)
public class Drain
{
    @Inject
    protected Videobridge videobridge;

    private Response setDrainMode(boolean enable)
    {
        try
        {
            videobridge.setDrainMode(enable);
            return Response.ok().build();
        }
        catch (Throwable t)
        {
            return Response.status(HttpStatus.INTERNAL_SERVER_ERROR_500).build();
        }
    }

    @POST
    @Path("/enable")
    public Response drainEnable()
    {
        return setDrainMode(true);
    }

    @POST
    @Path("/disable")
    public Response drainDisable()
    {
        return setDrainMode(false);
    }

    @SuppressWarnings("unchecked")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getDrainState()
    {
        JSONObject obj = new JSONObject();
        obj.put("drain", videobridge.getDrainMode());
        return obj.toJSONString();
    }
}
