/*
 * Copyright @ 2019 - Present 8x8, Inc.
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
package org.jitsi.videobridge.rest;

import org.jitsi.nlj.transform.node.*;
import org.jitsi.osgi.*;
import org.jitsi.rest.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.queue.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;
import org.json.simple.parser.*;
import org.osgi.framework.*;

import javax.servlet.http.*;
import java.io.*;
import java.util.*;

import static org.jitsi.videobridge.rest.HandlerImpl.STATISTICS;

/**
 * Handles requests for "/colibri/stats*".
 *
 * @author Boris Grozev
 */
class StatisticsRequestHandler
{
    /**
     * The logger instance used by {@link StatisticsRequestHandler}.
     */
    private static final Logger logger
            = Logger.getLogger(StatisticsRequestHandler.class);

    /**
     * The {@link HandlerImpl}.
     */
    private final HandlerImpl handlerImpl;

    /**
     * The string that designates the request is for all stats types.
     */
    private static final String ALL = "all";

    /**
     * The string for {@link Node} stats.
     */
    private static final String NODE = "node";

    /**
     * The string for {@link ByteBufferPool} stats.
     */
    private static final String POOL = "pool";

    /**
     * The string for {@link PacketQueue} stats.
     */
    private static final String QUEUE = "queue";

    /**
     * The string for transit time stats.
     */
    private static final String TRANSIT = "transit";

    /**
     * Initializes a new {@link StatisticsRequestHandler} instance.
     * @param handlerImpl
     */
    StatisticsRequestHandler(HandlerImpl handlerImpl)
    {
        this.handlerImpl = handlerImpl;
    }

    /**
     * Handles a specific request.
     * @param target the target with "/colibri/" removed
     * @param request
     * @param response
     * @throws IOException
     */
    void handleStatsRequest(
            String target,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException
    {
        if ("GET".equals(request.getMethod()))
        {
            JSONStreamAware statsJsonObject;
            if (target.equals(STATISTICS))
            {
                statsJsonObject = doGetStatisticsJSON();
            }
            else if (target.equals(STATISTICS + "/" + NODE))
            {
                statsJsonObject = StatsKeepingNode.Companion.getStatsJson();
            }
            else if (target.equals(STATISTICS + "/" + POOL))
            {
                statsJsonObject = ByteBufferPool.getStatsJson();
            }
            else if (target.equals(STATISTICS + "/" + QUEUE))
            {
                statsJsonObject = handlerImpl.getVideobridge().getQueueStats();
            }
            else if (target.equals(STATISTICS + "/" + TRANSIT))
            {
                statsJsonObject = PacketTransitStats.getStatsJson();
            }
            else
            {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }


            response.setStatus(HttpServletResponse.SC_OK);
            statsJsonObject.writeJSONString(response.getWriter());
        }
        else if ("POST".equals(request.getMethod()))
        {
            if (!RESTUtil.isJSONContentType(request.getContentType()))
            {
                response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
                return;
            }

            JSONObject requestJSONObject;
            try
            {
                Object o = new JSONParser().parse(request.getReader());
                if (o instanceof JSONObject)
                {
                    requestJSONObject = (JSONObject) o;
                }
                else
                {
                    requestJSONObject = null;
                }
            }
            catch (Exception e)
            {
                requestJSONObject = null;
            }

            if (requestJSONObject == null)
            {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            Boolean all = getBoolean(requestJSONObject.get(ALL));
            Boolean node = getBoolean(requestJSONObject.get(NODE));
            Boolean queue = getBoolean(requestJSONObject.get(QUEUE));
            Boolean pool = getBoolean(requestJSONObject.get(POOL));
            Boolean transit = getBoolean(requestJSONObject.get(TRANSIT));

            if (node != null || (node = all) != null)
            {
                enableNodeStats(node);
            }
            if (queue != null || (queue = all) != null)
            {
                enableQueueStats(queue);
            }
            if (pool != null || (pool = all) != null)
            {
                enablePoolStats(pool);
            }
            if (transit != null || (transit = all) != null)
            {
                enableTransitStats(transit);
            }

            response.setStatus(HttpServletResponse.SC_OK);
        }
        else
        {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        }
    }

    /**
     * Enables or disables the {@link Node} statistics.
     *
     * @param enable whether to enable or disable.
     */
    private void enableNodeStats(boolean enable)
    {
        logger.info("Enabling node stats: " + enable);
        StatsKeepingNode.Companion.setEnableStatistics(enable);
    }

    /**
     * Enables or disables the {@link PacketQueue} statistics.
     *
     * @param enable whether to enable or disable.
     */
    private void enableQueueStats(boolean enable)
    {
        logger.info("Enabling queue stats: " + enable);
        PacketQueue.setEnableStatisticsDefault(enable);
    }

    /**
     * Enables or disables the {@link ByteBufferPool} statistics.
     *
     * @param enable whether to enable or disable.
     */
    private void enablePoolStats(boolean enable)
    {
        logger.info("Enabling pool stats: " + enable);
        ByteBufferPool.enableStatistics(enable);
    }

    /**
     * Enables or disables the transit time statistics.
     *
     * @param enable whether to enable or disable.
     */
    private void enableTransitStats(boolean enable)
    {
        // TODO
    }

    /**
     * Parses an object in a {@link Boolean}
     * @param o the object.
     * @return
     */
    private Boolean getBoolean(Object o)
    {
        return o == null ? null : Boolean.valueOf(o.toString());
    }


    /**
     * Gets a JSON representation of the <tt>VideobridgeStatistics</tt> of the
     * associated <tt>Videobridge</tt>.
     */
    private JSONObject doGetStatisticsJSON()
    {
        BundleContext bundleContext = handlerImpl.getBundleContext();

        if (bundleContext != null)
        {
            StatsManager statsManager
                = ServiceUtils2.getService(bundleContext, StatsManager.class);

            if (statsManager != null)
            {
                Iterator<Statistics> i
                        = statsManager.getStatistics().iterator();
                Statistics statistics = null;

                if (i.hasNext())
                {
                    statistics = i.next();
                }

                JSONObject statsJsonObjets
                        = JSONSerializer.serializeStatistics(statistics);
                return statsJsonObjets != null
                        ? statsJsonObjets : new JSONObject();
            }
            else
            {
                logger.warn(
                    "No StatsManager, statistics not enabled in the config?");
            }
        }
        else
        {
            logger.warn("No bundle context, can not get statistics.");
        }

        return new JSONObject();
    }
}
