/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

import org.jitsi.util.*;
import org.jitsi.videobridge.*;

import java.util.*;
import java.util.stream.*;

/**
 * Manages the list of remote/foreign/Octo endpoints for a specific
 * {@link Conference}.
 *
 * @author Boris Grozev
 */
 public class OctoEndpoints
 {
     /**
      * The {@link Logger} used by the {@link RtpChannel} class to print debug
      * information. Note that instances should use {@link #logger} instead.
      */
     private static final Logger classLogger
         = Logger.getLogger(OctoEndpoints.class);

     /**
      * The owning conference.
      */
     private Conference conference;

     private Set<String> octoEndpointIds = new HashSet<>();
     /**
      * The {@link OctoEndpointMessageTransport} used to parse and handle
      * incoming data messages from Octo.
      */
     final OctoEndpointMessageTransport messageTransport
         = new OctoEndpointMessageTransport(this);

     /**
      * The {@link Logger} to be used by this instance to print debug
      * information.
      */
     private final Logger logger;

     OctoEndpoints(Conference conference)
     {
         this.conference = conference;
         logger = Logger.getLogger(classLogger, conference.getLogger());
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
      * Gets the list of all Octo endpoints from the associated
      * {@link Conference}. That is returns all conference endpoints which are
      * instances of {@link OctoEndpoint}.
      * @return the list of all {@link OctoEndpoint}s in the conference.
      */
     private List<OctoEndpoint> getOctoEndpoints()
     {
         return
             conference.getEndpoints().stream()
                 .filter(e -> e instanceof OctoEndpoint)
                 .map(e -> (OctoEndpoint) e)
                 .collect(Collectors.toList());
     }

     /**
      * Updates the list of {@link OctoEndpoint}s maintained by this instance.
      * The list {@code endpointIds} specifies the current list of endpoints
      * associated with a particular channel. If the list contains IDs which
      * this instance does not know about, then an associated endpoint should
      * be added. But if an {@link OctoEndpoint}'s ID is missing from the list,
      * this does not necessarily mean that we should expire/remove it (because
      * it might have tracks in the other channel).
      */
     public void updateEndpoints(Set<String> endpointIds)
     {
         Set<String> existingEndpointIds = octoEndpointIds;

         // Create new OctoEndpoint instances for endpoint IDs which we
         // don't yet have in the conference.
         endpointIds.forEach(endpointId ->
         {
             if (!existingEndpointIds.contains(endpointId))
             {
                 addEndpoint(endpointId);
             }
         });

         octoEndpointIds = Collections.unmodifiableSet(endpointIds);

         // The endpoints that are not signaled anymore will be left to be
         // expired by the expire thread. We could expire them right now.
         // Should we?
     }

     /**
      * Creates a new {@link OctoEndpoint} instance and adds it to the
      * conference.
      * @param id the ID for the new instance.
      * @return the newly created instance.
      */
     private OctoEndpoint addEndpoint(String id)
     {
         OctoEndpoint endpoint
                 = new OctoEndpoint(conference, id, this);

         conference.addEndpoint(endpoint);

         return endpoint;
     }

     /**
      * Sends a message through the Octo channel.
      * @param msg the message to send.
      */
     public void sendMessage(String msg)
     {
         logger.warn("Can not send a message, no channels.");
     }
 }
