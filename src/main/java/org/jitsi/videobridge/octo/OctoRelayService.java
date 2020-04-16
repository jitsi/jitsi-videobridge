/*
 * Copyright @ 2015-2017 Atlassian Pty Ltd
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

import org.jitsi.utils.logging2.*;
import org.osgi.framework.*;

import java.net.*;

import static org.jitsi.videobridge.octo.config.OctoConfig.*;

/**
 * A {@link BundleActivator} for a bridge-to-bridge (Octo) relay.
 *
 * @author Boris Grozev
 */
public class OctoRelayService
    implements BundleActivator
{
    /**
     * The {@link Logger} used by the {@link OctoRelay} class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = new LoggerImpl(OctoRelayService.class.getName());

    /**
     * The Octo relay instance used by this {@link OctoRelayService}.
     */
    private OctoRelay relay;

    /**
     * @return the {@link OctoRelay} managed by this
     * {@link OctoRelayService}.
     */
    public OctoRelay getRelay()
    {
        return relay;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void start(BundleContext bundleContext)
    {
        if (!Config.enabled()) {
            logger.info("Octo relay is disabled.");
            return;
        }
        String address = Config.bindAddress();
        String publicAddress = Config.publicAddress();
        int port = Config.bindPort();

        try
        {
            relay = new OctoRelay(address, port);
            relay.setPublicAddress(publicAddress);
            bundleContext
                .registerService(OctoRelayService.class.getName(), this,
                                 null);
        }
        catch (UnknownHostException | SocketException e)
        {
            logger.error("Failed to initialize Octo relay with address "
                             + address + ":" + port + ". ", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop(BundleContext bundleContext) throws Exception
    {
        if (relay != null)
        {
            relay.stop();
        }
    }

    public OctoRelayServiceStats getStats()
    {
        return new OctoRelayServiceStats(
            relay.getBytesReceived(),
            relay.getBytesSent(),
            relay.getPacketsReceived(),
            relay.getPacketsSent(),
            relay.getPacketsDropped(),
            relay.getReceiveBitrate(),
            relay.getReceivePacketRate(),
            relay.getSendBitrate(),
            relay.getSendPacketRate(),
            relay.getId()
        );
    }
}
