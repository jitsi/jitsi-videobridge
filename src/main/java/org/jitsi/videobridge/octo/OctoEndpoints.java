/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.videobridge.octo;

import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.json.simple.*;

import java.util.*;

/**
 * Manages the list of remote/foreign/Octo endpoints for a specific
 * {@link Conference}.
 *
 * @author Boris Grozev
 */
 class OctoEndpoints
 {
     /**
      * The owning conference.
      */
     private Conference conference;

     private Set<String> octoEndpointIds = new HashSet<>();

     /**
      * The {@link OctoEndpointMessageTransport} used to parse and handle
      * incoming data messages from Octo.
      */
     final OctoEndpointMessageTransport messageTransport;

     /**
      * The {@link Logger} to be used by this instance to print debug
      * information.
      */
     private final Logger logger;

     /**
      * Initializes a new {@link OctoEndpoints} instance for a specific
      * {@link Conference}.
      * @param conference the conference.
      */
     OctoEndpoints(Conference conference)
     {
         this.conference = conference;
         logger = conference.getLogger().createChildLogger(OctoEndpoint.class.getName());
         messageTransport = new OctoEndpointMessageTransport(this, logger);
     }

     /**
      * @return  the {@link Conference} associated with this
      * {@link OctoEndpoints}.
      */
     Conference getConference()
     {
         return conference;
     }

     /**
      * Updates the list of {@link OctoEndpoint}s maintained by this instance.
      */
     boolean setEndpoints(Set<String> endpointIds)
     {
         Set<String> toExpire = new HashSet<>(octoEndpointIds);
         toExpire.removeAll(endpointIds);

         Set<String> toCreate = new HashSet<>(endpointIds);
         toCreate.removeAll(octoEndpointIds);

         toCreate.forEach((id) -> addEndpoint(id, logger));
         toExpire.forEach(id ->
         {
             AbstractEndpoint endpoint = conference.getEndpoint(id);
             if (endpoint != null)
             {
                 endpoint.expire();
             }
         });


         octoEndpointIds = new HashSet<>(endpointIds);

         return !toCreate.isEmpty() || !toExpire.isEmpty();
     }

     /**
      * Notifies this instance that one of its {@link OctoEndpoint}s expired.
      * @param endpoint
      */
     void endpointExpired(@NotNull OctoEndpoint endpoint)
     {
        octoEndpointIds.remove(endpoint.getID());
     }

     /**
      * Creates a new {@link OctoEndpoint} instance and adds it to the
      * conference.
      * @param id the ID for the new instance.
      * @return the newly created instance.
      */
     private OctoEndpoint addEndpoint(String id, Logger parentLogger)
     {
         OctoEndpoint endpoint = new OctoEndpoint(conference, id, this, parentLogger);

         conference.addEndpoint(endpoint);

         return endpoint;
     }

     /**
      * Gets a JSON representation of the parts of this object's state that
      * are deemed useful for debugging.
      */
     JSONObject getDebugState()
     {
         JSONObject debugState = new JSONObject();
         debugState.put("ids", octoEndpointIds.toString());
         return debugState;
     }
 }
