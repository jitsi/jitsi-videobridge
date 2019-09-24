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

package org.jitsi.videobridge.rest.stats;

import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.stats.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;

import javax.ws.rs.*;
import javax.ws.rs.core.*;

@Path("/")
public class Stats
{
    protected final StatsManagerProvider statsManagerProvider;

    public Stats(StatsManagerProvider statsManagerProvider)
    {
        this.statsManagerProvider = statsManagerProvider;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String getStats()
    {
        StatsManager statsManager = statsManagerProvider.get();

        JSONArray jsonStats = new JSONArray();

        for (Statistics stat : statsManager.getStatistics())
        {
            jsonStats.add(JSONSerializer.serializeStatistics(stat));
        }

        return jsonStats.toJSONString();
    }
}
