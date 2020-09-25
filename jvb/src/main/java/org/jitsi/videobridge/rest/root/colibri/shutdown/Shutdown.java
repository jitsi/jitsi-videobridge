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

package org.jitsi.videobridge.rest.root.colibri.shutdown;

import com.fasterxml.jackson.annotation.*;
import org.eclipse.jetty.http.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.rest.*;
import org.jitsi.videobridge.rest.annotations.*;

import javax.inject.*;
import javax.servlet.http.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

/**
 * A resource for shutting down the videobridge via REST.
 */
@Path("/colibri/shutdown")
@EnabledByConfig(RestApis.SHUTDOWN)
public class Shutdown
{
    @Inject
    protected Videobridge videobridge;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response shutdown(ShutdownJson shutdown, @Context HttpServletRequest request)
    {
        try
        {
            videobridge.shutdown(shutdown.isGraceful);
            return Response.ok().build();
        }
        catch (Throwable t)
        {
            return Response.status(HttpStatus.INTERNAL_SERVER_ERROR_500).build();
        }
    }

    /**
     * A class binding for the shutdown JSON passed to the shutdown request
     * Currently, it only has a single field:
     * {
     *     graceful-shutdown: Boolean [required]
     * }
     */
    public static class ShutdownJson
    {
        // Unfortunately, using @JsonProperty here alone is not enough to throw an error
        // if the property is missing from the JSON, but we can't leave it out entirely
        // as it's needed for serialization (since the constructor param JsonProperty
        // is only for the parameter, not the member, so it isn't used when
        // serializing).
        @JsonProperty(value = "graceful-shutdown", required = true)
        private Boolean isGraceful;

        @JsonCreator
        public ShutdownJson(@JsonProperty(value = "graceful-shutdown", required = true) Boolean isGraceful)
        {
            this.isGraceful = isGraceful;
        }
    }
}

