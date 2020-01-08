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

package org.jitsi.videobridge.transport;

import org.ice4j.ice.harvest.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging2.*;

import java.io.*;
import java.util.*;

import static org.jitsi.videobridge.transport.HarvestersConfig.*;

public class Harvesters
{
    /**
     * The flag which indicates whether application-wide harvesters, stored
     * in the static fields {@link #tcpHarvester} and
     * {@link #singlePortHarvesters} have been initialized.
     */
    private static boolean staticConfigurationInitialized = false;

    /**
     * Global variable do we consider this transport manager as healthy.
     * By default we consider healthy, if we fail to bind to the single port
     * port we consider the bridge as unhealthy.
     */
    public static boolean healthy = true;

    /**
     * The {@link Logger} used by the {@link Harvesters} class to
     * print debug information.
     */
    private static final Logger classLogger
            = new LoggerImpl(Harvesters.class.getName());

    /**
     * The single <tt>TcpHarvester</tt> instance for the
     * application.
     */
    public static TcpHarvester tcpHarvester = null;

    /**
     * The <tt>SinglePortUdpHarvester</tt>s which will be appended to ICE
     * <tt>Agent</tt>s managed by <tt>IceTransport</tt> instances.
     */
    public static List<SinglePortUdpHarvester> singlePortHarvesters = null;


    /**
     * Initializes the static <tt>Harvester</tt> instances used by all
     * <tt>IceTransport</tt> instances, that is
     * {@link #tcpHarvester} and {@link #singlePortHarvesters}.
     *
     * @param cfg the {@link ConfigurationService} which provides values to
     * configurable properties of the behavior/logic of the method
     * implementation
     */
    public static void initializeStaticConfiguration(ConfigurationService cfg)
    {
        synchronized (Harvesters.class)
        {
            if (staticConfigurationInitialized)
            {
                return;
            }
            staticConfigurationInitialized = true;


            singlePortHarvesters
                    = SinglePortUdpHarvester.createHarvesters(Config.port());
            if (singlePortHarvesters.isEmpty())
            {
                singlePortHarvesters = null;
                classLogger.info("No single-port harvesters created.");
            }

            healthy = singlePortHarvesters != null;

            if (Config.tcpEnabled())
            {
                for (int port : Config.tcpPortsToTry())
                {
                    try
                    {
                        tcpHarvester
                                = new TcpHarvester(port, Config.iceSslTcp());
                        classLogger.info("Initialized TCP harvester on port "
                                + port + ", ssltcp=" + Config.iceSslTcp());

                        // We just want the first successful port.
                        break;
                    }
                    catch (IOException ioe)
                    {
                        classLogger.warn(
                                "Failed to initialize TCP harvester on port " + port);
                    }
                }

                if (Config.tcpMappedPort() != null)
                {
                    tcpHarvester.addMappedPort(Config.tcpMappedPort());
                }
            }
        }
    }

    /**
     * Stops the static <tt>Harvester</tt> instances used by all
     * <tt>IceTransport</tt> instances, that is
     * {@link #tcpHarvester} and {@link #singlePortHarvesters}.
     */
    public static void closeStaticConfiguration()
    {
        synchronized (Harvesters.class)
        {
            if (!staticConfigurationInitialized)
            {
                return;
            }
            staticConfigurationInitialized = false;

            if (singlePortHarvesters != null)
            {
                singlePortHarvesters.forEach(AbstractUdpListener::close);
                singlePortHarvesters = null;
            }

            if (tcpHarvester != null)
            {
                tcpHarvester.close();
                tcpHarvester = null;
            }

            // Reset the flag to initial state.
            healthy = true;
        }
    }
}
