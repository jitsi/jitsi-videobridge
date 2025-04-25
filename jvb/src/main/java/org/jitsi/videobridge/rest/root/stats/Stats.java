/*
 * Copyright @ 2025 - present 8x8, Inc.
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
package org.jitsi.videobridge.rest.root.stats;

import jakarta.inject.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jitsi.nlj.*;
import org.jitsi.utils.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.rest.annotations.*;

@Path("/stats")
@EnabledByConfig(RestApis.RTCSTATS)
public class Stats
{
    @Inject
    private Videobridge videobridge;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String rtcstats()
    {
        OrderedJsonObject debugState = videobridge.getDebugState(
                null,
                null,
                DebugStateMode.STATS);

        return debugState.toJSONString();
    }
}
