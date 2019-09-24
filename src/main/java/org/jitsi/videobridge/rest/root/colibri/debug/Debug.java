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

import org.jitsi.nlj.transform.node.*;
import org.jitsi.nlj.util.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.util.*;

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
@Path("/colibri/debug")
public class Debug
{
    @Inject
    private VideobridgeProvider videobridgeProvider;

    private Logger logger = new LoggerImpl(Debug.class.getName());

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
        logger.info("Enabling " + feature.getValue());
        setFeature(feature, true);
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
                PacketQueue.setEnableStatisticsDefault(true);
                break;
            }
            case TRANSIT_STATS: {
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
                return videobridgeProvider.get().getQueueStats().toJSONString();
            }
            case TRANSIT_STATS: {
                return PacketTransitStats.getStatsJson().toJSONString();
            }
            default: {
                throw new NotFoundException();
            }
        }
    }
}
