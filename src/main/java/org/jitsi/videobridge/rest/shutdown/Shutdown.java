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

package org.jitsi.videobridge.rest.shutdown;

import com.fasterxml.jackson.annotation.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import javax.servlet.http.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

@Path("/")
public class Shutdown
{
    protected final VideobridgeProvider videobridgeProvider;
    public Shutdown(VideobridgeProvider videobridgeProvider)
    {
        this.videobridgeProvider = videobridgeProvider;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response shutdown(ShutdownJson shutdown, @Context HttpServletRequest request) throws XmppStringprepException
    {
        ShutdownIQ shutdownIq = shutdown.toIq();

        String ipAddress = request.getHeader("X-FORWARDED-FOR");
        if (ipAddress == null)
        {
            ipAddress = request.getRemoteAddr();
        }
        shutdownIq.setFrom(JidCreate.from(ipAddress));
        videobridgeProvider.get().handleShutdownIQ(shutdownIq);

        return Response.ok().build();
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
        @JsonProperty(value = "graceful-shutdown", required = true)
        Boolean isGraceful;

        public ShutdownJson() {}

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

