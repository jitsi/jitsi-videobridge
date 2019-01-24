/*
 * Copyright @ 2019-Present 8x8, Inc
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
package org.jitsi.videobridge.shim;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.jitsi.videobridge.*;

/**
 * Represents a Colibri {@code channel-bundle}
 *
 * @author Brian Baldino
 */
public class ChannelBundleShim
{
    private final String bundleId;
    private final IceUdpTransportManager transportManager;

    public ChannelBundleShim(Conference conference, String bundleId)
    {
        this.bundleId = bundleId;
        // Create the underlying transport manager
        transportManager =
                conference.getTransportManager(bundleId, true, true);

        // Bundle ID and endpoint ID must be the same
        AbstractEndpoint ep = conference.getEndpoint(bundleId);
        if (ep instanceof Endpoint)
        {
            ((Endpoint)ep).setTransportManager(transportManager);
        }
        else
        {
            throw new Error("Tried to set a transport manager on an invalid ep type: " + ep.getClass());
        }
    }

    public void startConnectivityEstablishment(IceUdpTransportPacketExtension transportExtension)
    {
        transportManager.startConnectivityEstablishment(transportExtension);
    }

    public void describe(ColibriConferenceIQ.ChannelBundle bundleIq)
    {
        transportManager.describe(bundleIq);
    }
}
