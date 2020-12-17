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

import org.jitsi.health.*;
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
import java.net.*;

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
    private Videobridge videobridge;

    @Inject
    private HealthCheckServiceSupplier healthCheckServiceSupplier;

    private final Logger logger = new LoggerImpl(Debug.class.getName());

    // Functions to enable or disable features

    /**
     * Set the state of a given JVB feature
     * @param feature the feature to enable or disable
     * @param enabled whether the feature should be enabled
     * @return HTTP response
     */
    @POST
    @Path("/features/jvb/{feature}/{enabled}")
    public Response setJvbFeatureState(
        @PathParam("feature") DebugFeatures feature,
        @PathParam("enabled") Boolean enabled)
    {
        logger.info((enabled ? "Enabling" : "Disabling") + " feature " + feature.getValue());
        setFeature(feature, enabled);
        return Response.ok().build();
    }

    @GET
    @Path("/features/jvb/{feature}/{enabled}")
    public Response setJvbFeatureState2(
        @PathParam("feature") DebugFeatures feature,
        @PathParam("enabled") Boolean enabled)
    {
        System.out.println("Here with get instead of post!");
        logger.info((enabled ? "Enabling" : "Disabling") + " feature " + feature.getValue());
        setFeature(feature, enabled);
        return Response.ok().build();
    }

    /**
     * Find out whether the given JVB feature is currently enabled or disabled
     * @param feature the feature to check
     * @return true if the feature is enabled, false otherwise
     */
    @GET
    @Path("/features/jvb/{feature}")
    public Boolean getJvbFeatureState(@PathParam("feature") DebugFeatures feature)
    {
        switch (feature)
        {
            case PAYLOAD_VERIFICATION: {
                return Node.Companion.isPayloadVerificationEnabled();
            }
            case NODE_STATS: {
                return StatsKeepingNode.Companion.getEnableStatistics();
            }
            case POOL_STATS: {
                return ByteBufferPool.statisticsEnabled();
            }
            case QUEUE_STATS: {
                return PacketQueue.getEnableStatisticsDefault();
            }
            case NODE_TRACING: {
                return Node.Companion.isNodeTracingEnabled();
            }
            case TRANSIT_STATS: {
                // Always enabled (worth modeling as a 'feature' then?)
                return true;
            }
            case TASK_POOL_STATS: {
                // Always enabled (worth modeling as a 'feature' then?)
                return true;
            }
            default: {
                throw new NotFoundException();
            }
        }
    }

    @POST
    @Path("/features/endpoint/{confId}/{epId}/{feature}/{enabled}")
    public Response setEndpointFeatureState(
        @PathParam("confId") String confId,
        @PathParam("epId") String epId,
        @PathParam("feature") EndpointDebugFeatures feature,
        @PathParam("enabled") Boolean enabled)
    {
        Conference conference = videobridge.getConference(confId);
        if (conference == null)
        {
            throw new NotFoundException("No conference was found with the specified id.");
        }

        AbstractEndpoint endpoint = conference.getEndpoint(epId);
        if (endpoint == null)
        {
            throw new NotFoundException("No endpoint was found with the specified id.");
        }

        logger.info("Setting feature state: feature=" + feature.getValue() + ", enabled? " + enabled);
        try
        {
            endpoint.setFeature(feature, enabled);
        }
        catch (IllegalStateException e)
        {
            return Response.status(403, e.getMessage()).build();
        }

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
                break;
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

    // Functions to actually get statistics

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String bridgeDebug(@DefaultValue("false") @QueryParam("full") boolean full)
    {
        OrderedJsonObject debugState = videobridge.getDebugState(null, null, full);

        // Append the health status.
        Exception result = healthCheckServiceSupplier.get().getResult();
        debugState.put("health", result == null ? "OK" : result.getMessage());

        return debugState.toJSONString();
    }

    @GET
    @Path("/{confId}")
    @Produces(MediaType.APPLICATION_JSON)
    public String confDebug(
            @PathParam("confId") String confId,
            @DefaultValue("true") @QueryParam("full") boolean full)
    {
        OrderedJsonObject confJson = videobridge.getDebugState(confId, null, full);
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
        OrderedJsonObject confJson = videobridge.getDebugState(confId, epId, full);
        return confJson.toJSONString();
    }

    @GET
    @Path("/stats/jvb/{feature}")
    @Produces(MediaType.APPLICATION_JSON)
    public String getJvbFeatureStats(@PathParam("feature") DebugFeatures feature)
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
                return videobridge.getQueueStats().toJSONString();
            }
            case TRANSIT_STATS: {
                return PacketTransitStats.getStatsJson().toJSONString();
            }
            case TASK_POOL_STATS: {
                return TaskPools.getStatsJson().toJSONString();
            }
            case XMPP_DELAY_STATS: {
                return XmppConnection.getStatsJson().toJSONString();
            }
            case PAYLOAD_VERIFICATION: {
                return PayloadVerificationPlugin.getStatsJson().toJSONString();
            }
            default: {
                throw new NotFoundException();
            }
        }
    }

    // Old deprecated paths

    /**
     * Depreacted, use {@link Debug#getJvbFeatureStats(DebugFeatures)}
     * @param featureName the feature name
     * @param uriInfo the URI info of the request
     * @return HTTP response
     */
    @Deprecated
    @GET
    @Path("/stats/{feature_name:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStats(@PathParam("feature_name") String featureName, @Context UriInfo uriInfo)
    {
        // Redirect to the new location
        String newTarget = uriInfo.getBaseUri() + "debug/stats/jvb/" + featureName;
        return Response.status(302).location(URI.create(newTarget)).build();
    }
}
