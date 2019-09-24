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
import org.jitsi.service.configuration.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import javax.inject.*;
import javax.servlet.http.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

@Path("/colibri/shutdown")
public class Shutdown
{
    /**
     * The name of the <tt>System</tt> and <tt>ConfigurationService</tt>
     * boolean property which enables graceful shutdown through REST API.
     * It is disabled by default.
     */
    public static final String ENABLE_REST_SHUTDOWN_PNAME
            = "org.jitsi.videobridge.ENABLE_REST_SHUTDOWN";

    @Inject
    protected VideobridgeProvider videobridgeProvider;

    @Inject
    protected ConfigProvider configProvider;

    protected Boolean enabled()
    {
        //TODO: see if we can enforce this with a filter instead?
        return configProvider.get().getBoolean(ENABLE_REST_SHUTDOWN_PNAME, true);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response shutdown(ShutdownJson shutdown, @Context HttpServletRequest request) throws XmppStringprepException
    {
        if (!enabled())
        {
            throw new NotFoundException();
        }
        ShutdownIQ shutdownIq = shutdown.toIq();

        String ipAddress = request.getHeader("X-FORWARDED-FOR");
        if (ipAddress == null)
        {
            ipAddress = request.getRemoteAddr();
        }
        shutdownIq.setFrom(JidCreate.from(ipAddress));
        IQ responseIq = videobridgeProvider.get().handleShutdownIQ(shutdownIq);
        if (IQ.Type.result.equals(responseIq.getType()))
        {
            return Response.ok().build();
        }
        XMPPError.Condition condition = responseIq.getError().getCondition();
        if (XMPPError.Condition.not_authorized.equals(condition))
        {
            return Response.status(HttpStatus.UNAUTHORIZED_401).build();
        }
        else if (XMPPError.Condition.service_unavailable.equals(condition))
        {
            return Response.status(HttpStatus.SERVICE_UNAVAILABLE_503).build();
        }
        return Response.status(HttpStatus.INTERNAL_SERVER_ERROR_500).build();
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

        ShutdownIQ toIq()
        {
            if (isGraceful)
            {
                return ShutdownIQ.createGracefulShutdownIQ();
            }
            return ShutdownIQ.createForceShutdownIQ();
        }
    }
}

