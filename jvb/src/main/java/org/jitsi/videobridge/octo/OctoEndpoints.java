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
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.json.simple.*;

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
      * A cache of the signaled payload types, since these are only signaled
      * at the top level but apply to all Octo endpoints
      */
     private final List<PayloadType> payloadTypes = new ArrayList<>();

     /**
      * A cache of the signaled rtp extensions, since these are only signaled
      * at the top level but apply to all Octo endpoints
      */
     private final List<RtpExtension> rtpExtensions = new ArrayList<>();

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
        octoEndpointIds.remove(endpoint.getId());
     }

     void addPayloadType(PayloadType payloadType)
     {
         payloadTypes.add(payloadType);
         octoEndpointIds.stream()
             .map(epId -> conference.getEndpoint(epId))
             .filter(Objects::nonNull)
             .forEach(ep -> ep.addPayloadType(payloadType));
     }

     void addRtpExtension(RtpExtension rtpExtension)
     {
         rtpExtensions.add(rtpExtension);
         octoEndpointIds.stream()
             .map(epId -> conference.getEndpoint(epId))
             .filter(Objects::nonNull)
             .forEach(ep -> ep.addRtpExtension(rtpExtension));
     }

     void setMediaSources(MediaSourceDesc[] tracks) {
         // Split the tracks up by owner
         Map<String, List<MediaSourceDesc>> sourcesByOwner =
             Arrays.stream(tracks).collect(Collectors.groupingBy(
                 MediaSourceDesc::getOwner));

         sourcesByOwner.forEach((epId, epTracks) -> {
             OctoEndpoint ep = (OctoEndpoint)conference.getEndpoint(epId);
             if (ep != null)
             {
                 ep.setMediaSources(epTracks.toArray(new MediaSourceDesc[0]));
             }
             else
             {
                 logger.error("Error finding endpoint " + epId);
             }
         });
     }

     /**
      * Creates a new {@link OctoEndpoint} instance and adds it to the
      * conference.
      * @param id the ID for the new instance.
      * @return the newly created instance.
      */
     private OctoEndpoint addEndpoint(String id, Logger parentLogger)
     {
         logger.info("Creating Octo endpoint " + id);
         OctoEndpoint endpoint = new OctoEndpoint(conference, id, this, parentLogger);

         payloadTypes.forEach(endpoint::addPayloadType);
         rtpExtensions.forEach(endpoint::addRtpExtension);

         conference.addEndpoint(endpoint);

         return endpoint;
     }

     /**
      * Gets a JSON representation of the parts of this object's state that
      * are deemed useful for debugging.
      */
     @SuppressWarnings("unchecked")
     JSONObject getDebugState()
     {
         JSONObject debugState = new JSONObject();
         debugState.put("ids", octoEndpointIds.toString());
         debugState.put("messageTransport", messageTransport.getDebugState());
         return debugState;
     }
 }
