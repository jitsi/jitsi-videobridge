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

package org.jitsi.videobridge.rest.root.colibri.debug;

import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.rest.annotations.*;

import javax.ws.rs.*;
import javax.ws.rs.core.*;
import javax.ws.rs.core.Response;
import java.net.*;

/**
 * We moved /colibri/debug to /debug.
 */
@Path("/colibri/debug")
@EnabledByConfig(RestApis.COLIBRI)
public class Debug
{
    @POST
    @Path("{path:.+}")
    public Response post(@PathParam("path") String path, @Context UriInfo uriInfo)
    {
        return create301(path, uriInfo);
    }

    @GET
    @Path("{path:.+}")
    public Response get(@PathParam("path") String path, @Context UriInfo uriInfo)
    {
        return create301(path, uriInfo);
    }

    private Response create301(String path, UriInfo uriInfo)
    {
        String target = uriInfo.getBaseUri() + "debug/" + path;
        if (uriInfo.getQueryParameters().get("full") != null)
        {
            target += "?full=" + uriInfo.getQueryParameters().get("full");
        }
        return Response.status(301).location(URI.create(target)).build();
    }
}
