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

package org.jitsi.videobridge;

import org.jitsi.nlj.rtp.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Process information signaled about encodings (payload types, SSRCs, SSRC
 * associations, etc.) and gather it in a single place where it can be published
 * out to all interested parties.
 *
 * The idea is to alleviate the following problem:
 * When a new endpoint joins, it can use the notification of new local encoding
 * information to trigger updating all other endpoints with that new information,
 * but how does this new endpoint learn of this information from all existing
 * endpoints?  Existing endpoints don't currently have a good trigger to notify
 * new endpoints about their encoding information, so this was devised as a
 * single location to handle dispersing this information to all interested
 * parties.
 *
 * @author Brian Baldino
 */
public class EncodingsManager
{
    /**
     * Maps an endpoint ID to the list of its SSRC associations.
     */
    private Map<String, List<SsrcAssociation>> ssrcAssociations
            = new ConcurrentHashMap<>();

    /**
     * Set of listeners to be notified of associations.
     */
    private Set<EncodingsUpdateListener> listeners
        = ConcurrentHashMap.newKeySet();

    /**
     * Adds an SSRC association for a specific endpoint.
     *
     * @param endpointId the ID of the endpoint.
     * @param primarySsrc the primary SSRC in the SSRC association.
     * @param secondarySsrc the secondary SSRC in the SSRC association.
     * @param type the association type.
     */
    public void addSsrcAssociation(
            String endpointId,
            long primarySsrc,
            long secondarySsrc,
            SsrcAssociationType type)
    {
        List<SsrcAssociation> epSsrcAssociations
            = ssrcAssociations.computeIfAbsent(
                    endpointId, k -> new ArrayList<>());
        epSsrcAssociations.add(
                new SsrcAssociation(primarySsrc, secondarySsrc, type));

        listeners.forEach(
            listener
                -> listener.onNewSsrcAssociation(
                        endpointId, primarySsrc, secondarySsrc, type));
    }

    /**
     * Subscribe to future updates and be notified of any existing SSRC
     * associations.
     * @param listener
     */
    public void subscribe(EncodingsUpdateListener listener)
    {
        listeners.add(listener);

        ssrcAssociations.forEach(
            (endpointId, ssrcAssociations)
                -> ssrcAssociations.forEach(ssrcAssociation ->
                    {
                        listener.onNewSsrcAssociation(
                                endpointId,
                                ssrcAssociation.primarySsrc,
                                ssrcAssociation.secondarySsrc,
                                ssrcAssociation.type);
                    }));
    }

    /**
     * Unsubscribe from SSRC updates.
     */
    public void unsubscribe(EncodingsUpdateListener listener)
    {
        listeners.remove(listener);
    }

    /**
     * An interface for listening to new associations.
     */
    public interface EncodingsUpdateListener
    {
        /**
         * Notify this listener of a new association.
         */
        void onNewSsrcAssociation(
                String endpointId,
                long primarySsrc,
                long secondarySsrc,
                SsrcAssociationType type);
    }

    /**
     * Represents an SSRC association.
     */
    private static class SsrcAssociation
    {
        /**
         * The primary SSRC.
         */
        private long primarySsrc;

        /**
         * The secondary SSRC.
         */
        private long secondarySsrc;

        /**
         * The association type.
         */
        private SsrcAssociationType type;

        /**
         * Initializes a new {@link SsrcAssociation} instance.
         */
        private SsrcAssociation(
                long primarySsrc,
                long secondarySsrc,
                SsrcAssociationType type)
        {
            this.primarySsrc = primarySsrc;
            this.secondarySsrc = secondarySsrc;
            this.type = type;
        }
    }
}
