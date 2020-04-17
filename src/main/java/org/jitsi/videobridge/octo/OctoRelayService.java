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
import org.jitsi.videobridge.transport.octo.*;
import org.jitsi.videobridge.transport.udp.*;
import org.jitsi.videobridge.util.*;
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
     * The {@link Logger} used by the {@link OctoRelayService} class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = new LoggerImpl(OctoRelayService.class.getName());

    /**
     * The receive buffer size for the Octo socket
     */
    private static final int OCTO_SO_RCVBUF = 10 * 1024 * 1024;

    /**
     * The send buffer size for the Octo socket
     */
    private static final int OCTO_SO_SNDBUF = 10 * 1024 * 1024;

    /**
     * The {@link UdpTransport} used to send and receive Octo data
     */
    private UdpTransport udpTransport;

    /**
     * The {@link OctoTransport} for handling incominga nd outgoing Octo data
     */
    private OctoTransport octoTransport;

    /**
     * @return the {@link OctoTransport} managed by this
     * {@link OctoRelayService}.
     */
    public OctoTransport getOctoTransport()
    {
        return octoTransport;
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
            udpTransport = new UdpTransport(address, port, logger, OCTO_SO_RCVBUF, OCTO_SO_SNDBUF);
            logger.info("Created Octo UDP transport");
        }
        catch (UnknownHostException | SocketException e)
        {
            logger.error("Failed to initialize Octo UDP transport with " +
                "address " + address + ":" + port + ".", e);
            return;
        }

        octoTransport = new OctoTransport(publicAddress + ":" + port, logger);

        // Wire the data coming from the UdpTransport to the OctoTransport
        udpTransport.setIncomingDataHandler(octoTransport::dataReceived);
        // Wire the data going out of OctoTransport to UdpTransport
        octoTransport.setOutgoingDataHandler(udpTransport::send);
        TaskPools.IO_POOL.submit(udpTransport::startReadingData);

        bundleContext.registerService(
            OctoRelayService.class.getName(),
            this,
            null
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop(BundleContext bundleContext) throws Exception
    {
        if (udpTransport != null)
        {
            udpTransport.stop();
        }
        if (octoTransport != null)
        {
            octoTransport.stop();
        }
    }

    public OctoRelayServiceStats getStats()
    {
        UdpTransport.StatsSnapshot octoUdpTransportStats = udpTransport.getStats();
        OctoTransport.StatsSnapshot octoTransportStats = octoTransport.getStats();
        return new OctoRelayServiceStats.Builder()
            .packetsReceived(octoUdpTransportStats.getPacketsReceived())
            .receivePacketRate(octoUdpTransportStats.getReceivePacketRate())
            .packetsDropped(
                octoUdpTransportStats.getIncomingPacketsDropped() +
                    octoTransportStats.getNumInvalidPackets() +
                    octoTransportStats.getNumIncomingDroppedNoHandler()
            )
            .packetsSent(octoUdpTransportStats.getPacketsSent())
            .sendPacketRate(octoUdpTransportStats.getSendPacketRate())
            .bytesReceived(octoUdpTransportStats.getBytesReceived())
            .receiveBitrate(octoUdpTransportStats.getReceiveBitRate())
            .sendBitrate(octoUdpTransportStats.getSendBitRate())
            .bytesSent(octoUdpTransportStats.getBytesSent())
            .relayId(octoTransport.getRelayId())
            .build();
    }
}
