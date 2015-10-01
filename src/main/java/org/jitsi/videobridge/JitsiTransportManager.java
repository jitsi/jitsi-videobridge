/*
 * Copyright @ 2015 Atlassian Pty Ltd
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

import java.net.*;
import net.java.sip.communicator.service.protocol.media.*;
import net.java.sip.communicator.util.*;
import org.jitsi.service.neomedia.*;

/**
 * Extends
 * {@code net.java.sip.communicator.service.protocol.media.TransportManager} in
 * order to get access to certain protected static methods and thus avoid
 * duplicating their implementations here.
 *
 * @author Lyubomir Marinov
 */
class JitsiTransportManager
    extends
        net.java.sip.communicator.service.protocol.media.TransportManager
            <MediaAwareCallPeer<?,?,?>>
{
    public static PortTracker getPortTracker(MediaType mediaType)
    {
        return
            net.java.sip.communicator.service.protocol.media.TransportManager
                .getPortTracker(mediaType);
    }

    public static void initializePortNumbers()
    {
        net.java.sip.communicator.service.protocol.media.TransportManager
            .initializePortNumbers();
    }

    private JitsiTransportManager(MediaAwareCallPeer<?,?,?> callPeer)
    {
        super(callPeer);
    }

    @Override
    public long getHarvestingTime(String arg0)
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public String getICECandidateExtendedType(String arg0)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public InetSocketAddress getICELocalHostAddress(String arg0)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public InetSocketAddress getICELocalReflexiveAddress(String arg0)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public InetSocketAddress getICELocalRelayedAddress(String arg0)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public InetSocketAddress getICERemoteHostAddress(String arg0)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public InetSocketAddress getICERemoteReflexiveAddress(String arg0)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public InetSocketAddress getICERemoteRelayedAddress(String arg0)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getICEState()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected InetAddress getIntendedDestination(MediaAwareCallPeer<?,?,?> arg0)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int getNbHarvesting()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getNbHarvesting(String arg0)
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public long getTotalHarvestingTime()
    {
        // TODO Auto-generated method stub
        return 0;
    }
}
