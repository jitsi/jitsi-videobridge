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

import com.typesafe.config.*;
import org.ice4j.ice.harvest.*;
import org.jitsi.utils.logging.*;

import java.io.*;
import java.util.*;

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
     * The "mapped port" added to {@link #tcpHarvester}, or -1.
     */
    private static int tcpHarvesterMappedPort = -1;

    /**
     * The {@link Logger} used by the {@link Harvesters} class to
     * print debug information.
     */
    private static final Logger classLogger
            = Logger.getLogger(Harvesters.class);

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
     * @param iceConfig the {@link Config} which provides values to
     * configurable properties of the behavior/logic of the method
     * implementation
     */
    public static void initializeStaticConfiguration(Config iceConfig)
    {
        synchronized (Harvesters.class)
        {
            if (staticConfigurationInitialized)
            {
                return;
            }
            staticConfigurationInitialized = true;


            int singlePort = iceConfig.getInt("single-port-harvester-port");
            if (singlePort != -1)
            {
                singlePortHarvesters
                        = SinglePortUdpHarvester.createHarvesters(singlePort);
                if (singlePortHarvesters.isEmpty())
                {
                    singlePortHarvesters = null;
                    classLogger.info("No single-port harvesters created.");
                }

                healthy = singlePortHarvesters != null;
            }

            if (!iceConfig.getBoolean("disable-tcp-harvester"))
            {
                List<Integer> ports = iceConfig.getIntList("tcp-harvester-ports");
                boolean ssltcp = iceConfig.getBoolean("use-ssltcp");
                for (Integer port : ports)
                {
                    classLogger.info("Attempting to initialize TCP harvester on port " + port);
                    try
                    {
                        tcpHarvester = new TcpHarvester(port, ssltcp);
                        classLogger.info("Initialized TCP harvester on port " + port
                            + ", using SSLTCP: " + ssltcp);
                    }
                    catch (IOException ioe)
                    {
                        classLogger.warn(
                                "Failed to initialize TCP harvester on port " + port
                                        + ": " + ioe);
                    }
                    if (tcpHarvester != null)
                    {
                        break;
                    }
                }
                if (tcpHarvester == null)
                {
                    classLogger.error("Failed to initialize TCP harvester");
                    return;
                }

                int mappedPort = iceConfig.getInt("tcp-harvester-mapped-port");
                if (mappedPort != -1)
                {
                    tcpHarvesterMappedPort = mappedPort;
                    tcpHarvester.addMappedPort(mappedPort);
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
