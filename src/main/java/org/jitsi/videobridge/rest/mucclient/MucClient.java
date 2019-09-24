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

package org.jitsi.videobridge.rest.mucclient;

import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;
import org.json.simple.*;
import org.json.simple.parser.*;

import javax.inject.*;
import javax.servlet.http.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

/**
 * Add or remove XMPP environments to which the bridge will connect
 */
@Path("/colibri/muc-client")
public class MucClient
{
    @Inject
    protected ClientConnectionProvider clientConnectionProvider;

    @Path("/add")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addMucClient(String requestBody) throws ParseException
    {
        //NOTE: unfortunately MucClientConfiguration is not a compliant bean (it doesn't have
        // a no-arg ctor) so we can't parse the json directly into a MucClientConfiguration
        // instance and just take that as an argument here, we have to read the json
        // ourselves.
        Object o = new JSONParser().parse(requestBody);
        if (!(o instanceof JSONObject))
        {
            return Response.status(HttpServletResponse.SC_BAD_REQUEST).build();
        }
        ClientConnectionImpl clientConnection = clientConnectionProvider.get();
        if (clientConnection.addMucClient((JSONObject)o))
        {
            return Response.ok().build();
        }
        return Response.status(HttpServletResponse.SC_BAD_REQUEST).build();
    }

    @Path("/remove")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response removeMucClient(String requestBody) throws ParseException
    {
        Object o = new JSONParser().parse(requestBody);
        if (!(o instanceof JSONObject))
        {
            return Response.status(HttpServletResponse.SC_BAD_REQUEST).build();
        }
        ClientConnectionImpl clientConnection = clientConnectionProvider.get();
        if (clientConnection.removeMucClient((JSONObject)o))
        {
            return Response.ok().build();
        }
        return Response.status(HttpServletResponse.SC_BAD_REQUEST).build();
    }
}
