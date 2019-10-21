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

package org.jitsi.videobridge.rest.root.about.health;

import org.jitsi.videobridge.util.*;

import javax.inject.*;
import javax.servlet.http.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

@Path("/about/unhealthy")
public class Health
{
    @Inject
    protected VideobridgeProvider videobridgeProvider;

    @GET
    public Response getHealth()
    {
        try
        {
            videobridgeProvider.get().healthCheck();
        }
        catch (Exception ex)
        {
            return Response
                    .status(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
                    .entity(ex.getMessage())
                    .build();
        }
        return Response.ok().build();
    }
}
