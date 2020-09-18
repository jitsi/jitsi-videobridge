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

package org.jitsi.videobridge.rest.root.debug;

import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.transform.node.debug.*;
import org.jitsi.nlj.util.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.rest.annotations.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.xmpp.*;

import javax.inject.*;
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
@Path("/debug")
@EnabledByConfig(RestApis.DEBUG)
public class Debug
{
    @Inject
    @SuppressWarnings("unused")
    private VideobridgeSupplier videobridgeSupplier;

    private Logger logger = new LoggerImpl(Debug.class.getName());

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String bridgeDebug(@DefaultValue("false") @QueryParam("full") boolean full)
    {
        OrderedJsonObject confJson = videobridgeSupplier.get().getDebugState(null, null, full);
        return confJson.toJSONString();
    }

    @POST
    @Path("/enable/{feature}")
    public Response enableFeature(@PathParam("feature") DebugFeatures feature)
    {
        logger.info("Enabling " + feature.getValue());
        setFeature(feature, true);
        return Response.ok().build();
    }

    /**
     *
     * @param confId the conference id
     * @param epId the endpoint id
     * @param feature the Feature to enable or disable
     * @param state the feature state in String form. Note that we don't rely on Jersey's automatic parsing here because
     *              we want /debug/foo/bar/broken/ to return and HTTP 500 error and without the special handling
     *              inside the method it returns 404.
     * @return the Response
     * @throws IllegalArgumentException when parsing the state fails.
     */
    @POST
    @Path("/{confId}/{epId}/{state}/{feature}")
    public Response toggleEndpointFeature(
            @PathParam("confId") String confId,
            @PathParam("epId") String epId,
            @PathParam("feature") EndpointDebugFeatures feature,
            @PathParam("state") String state)
    {
        Conference conference = videobridgeSupplier.get().getConference(confId);
        if (conference == null)
        {
            throw new NotFoundException("No conference was found with the specified id.");
        }

        AbstractEndpoint endpoint = conference.getEndpoint(epId);
        if (endpoint == null)
        {
            throw new NotFoundException("No endpoint was found with the specified id.");
        }

        // the only exception possible here is the IllegalArgumentException which comes with
        // a handy error message and gets translated to a HTTP 500 error.
        FeatureState featureState = FeatureState.fromString(state);

        logger.info("Setting feature state: feature=" + feature.getValue() + ", state=" + featureState.getValue());
        try
        {
            endpoint.setFeature(feature, featureState.getValue());
        }
        catch (IllegalStateException e)
        {
            return Response.status(403, e.getMessage()).build();
        }

        return Response.ok().build();
    }

    @POST
    @Path("/disable/{feature}")
    public Response disableFeature(@PathParam("feature") DebugFeatures feature)
    {
        logger.info("Disabling " + feature.getValue());
        setFeature(feature, false);
        return Response.ok().build();
    }

    private void setFeature(DebugFeatures feature, boolean enabled)
    {
        switch (feature)
        {
            case PAYLOAD_VERIFICATION: {
                Node.Companion.enablePayloadVerification(enabled);
                break;
            }
            case NODE_STATS: {
                StatsKeepingNode.Companion.setEnableStatistics(enabled);
                break;
            }
            case POOL_STATS: {
                ByteBufferPool.enableStatistics(enabled);
                break;
            }
            case QUEUE_STATS: {
                PacketQueue.setEnableStatisticsDefault(enabled);
                break;
            }
            case NODE_TRACING: {
                Node.Companion.enableNodeTracing(enabled);
            }
            case TRANSIT_STATS: {
                //TODO
                break;
            }
            case TASK_POOL_STATS: {
                //TODO
                break;
            }
            default: {
                throw new NotFoundException();
            }
        }
    }

    @GET
    @Path("/{confId}")
    @Produces(MediaType.APPLICATION_JSON)
    public String confDebug(
            @PathParam("confId") String confId,
            @DefaultValue("true") @QueryParam("full") boolean full)
    {
        OrderedJsonObject confJson = videobridgeSupplier.get().getDebugState(confId, null, full);
        return confJson.toJSONString();
    }

    @GET
    @Path("/{confId}/{epId}")
    @Produces(MediaType.APPLICATION_JSON)
    public String epDebug(
            @PathParam("confId") String confId,
            @PathParam("epId") String epId,
            @DefaultValue("true") @QueryParam("full") boolean full)
    {
        OrderedJsonObject confJson = videobridgeSupplier.get().getDebugState(confId, epId, full);
        return confJson.toJSONString();
    }

    @GET
    @Path("/stats/{feature}")
    @Produces(MediaType.APPLICATION_JSON)
    public String getStats(@PathParam("feature") DebugFeatures feature)
    {
        switch (feature)
        {
            case NODE_STATS: {
                return StatsKeepingNode.Companion.getStatsJson().toJSONString();
            }
            case POOL_STATS: {
                return ByteBufferPool.getStatsJson().toJSONString();
            }
            case QUEUE_STATS: {
                return videobridgeSupplier.get().getQueueStats().toJSONString();
            }
            case TRANSIT_STATS: {
                return PacketTransitStats.getStatsJson().toJSONString();
            }
            case TASK_POOL_STATS: {
                return TaskPools.getStatsJson().toJSONString();
            }
            case XMPP_DELAY_STATS: {
                return ClientConnection.getStatsJson().toJSONString();
            }
            case PAYLOAD_VERIFICATION: {
                return PayloadVerificationPlugin.getStatsJson().toJSONString();
            }
            default: {
                throw new NotFoundException();
            }
        }
    }
}
