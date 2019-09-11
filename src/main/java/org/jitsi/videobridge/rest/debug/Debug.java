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

package org.jitsi.videobridge.rest.debug;

import org.eclipse.jetty.http.*;
import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.util.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.util.*;

import javax.ws.rs.*;
import javax.ws.rs.core.*;

/**
 * A REST interface for retrieving debug information about the bridge.
 *
 * Note that using this interface MAY disrupt running conferences or even
 * cause a deadlock. It is really meant only for debugging, which is why it is
 * disabled by default. Use at your own risk.
 *
 * @author bbaldino
 */
@Path("/")
public class Debug
{
    private final VideobridgeProvider videobridgeProvider;
    private Logger logger = new LoggerImpl(Debug.class.getName());

    public Debug(VideobridgeProvider videobridgeProvider)
    {
        this.videobridgeProvider = videobridgeProvider;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String bridgeDebug()
    {
        OrderedJsonObject confJson = videobridgeProvider.get().getDebugState(null, null);
        return confJson.toJSONString();
    }

    @POST
    @Path("/enable/{feature}")
    public Response enableFeature(@PathParam("feature") DebugFeatures feature)
    {
        switch (feature)
        {
            case PAYLOAD_VERIFICATION: {
                logger.info("Enabling payload verification");
                Node.Companion.enablePayloadVerification(true);
                break;
            }
            default: {
                return Response.status(HttpStatus.NOT_FOUND_404).build();
            }
        }

        return Response.ok().build();
    }

    @POST
    @Path("/disable/{feature}")
    public Response disableFeature(@PathParam("feature") DebugFeatures feature)
    {
        switch (feature)
        {
            case PAYLOAD_VERIFICATION: {
                logger.info("Disabling payload verification");
                Node.Companion.enablePayloadVerification(false);
                break;
            }
            default: {
                return Response.status(HttpStatus.NOT_FOUND_404).build();
            }
        }

        return Response.ok().build();
    }

    @GET
    @Path("/{confId}")
    @Produces(MediaType.APPLICATION_JSON)
    public String confDebug(@PathParam("confId") String confId)
    {
        OrderedJsonObject confJson = videobridgeProvider.get().getDebugState(confId, null);
        return confJson.toJSONString();
    }

    @GET
    @Path("/{confId}/{epId}")
    @Produces(MediaType.APPLICATION_JSON)
    public String epDebug(@PathParam("confId") String confId, @PathParam("epId") String epId)
    {
        OrderedJsonObject confJson = videobridgeProvider.get().getDebugState(confId, epId);
        return confJson.toJSONString();
    }
}
