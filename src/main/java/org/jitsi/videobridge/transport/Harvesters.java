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
     * The name of the property which disables the use of a
     * <tt>TcpHarvester</tt>.
     */
    public static final String DISABLE_TCP_HARVESTER
            = "org.jitsi.videobridge.DISABLE_TCP_HARVESTER";

    /**
     * The name of the property which controls the port number used for
     * <tt>SinglePortUdpHarvester</tt>s.
     */
    public static final String SINGLE_PORT_HARVESTER_PORT
            = "org.jitsi.videobridge.SINGLE_PORT_HARVESTER_PORT";

    /**
    /**
     * The default value of the port to be used for
     * {@code SinglePortUdpHarvester}.
     */
    private static final int SINGLE_PORT_DEFAULT_VALUE = 10000;

    /**
     * The {@link Logger} used by the {@link Harvesters} class to
     * print debug information.
     */
    private static final Logger classLogger
            = new LoggerImpl(Harvesters.class.getName());

    /**
     * The default port that the <tt>TcpHarvester</tt> will
     * bind to.
     */
    private static final int TCP_DEFAULT_PORT = 443;

    /**
     * The port on which the <tt>TcpHarvester</tt> will bind to
     * if no port is specifically configured, and binding to
     * <tt>DEFAULT_TCP_PORT</tt> fails (for example, if the process doesn't have
     * the required privileges to bind to a port below 1024).
     */
    private static final int TCP_FALLBACK_PORT = 4443;

    /**
     * The name of the property which specifies an additional port to be
     * advertised by the TCP harvester.
     */
    public static final String TCP_HARVESTER_MAPPED_PORT
            = "org.jitsi.videobridge.TCP_HARVESTER_MAPPED_PORT";

    /**
     * The name of the property which controls the port to which the
     * <tt>TcpHarvester</tt> will bind.
     */
    public static final String TCP_HARVESTER_PORT
            = "org.jitsi.videobridge.TCP_HARVESTER_PORT";

    /**
     * The name of the property which controls the use of ssltcp candidates by
     * <tt>TcpHarvester</tt>.
     */
    public static final String TCP_HARVESTER_SSLTCP
            = "org.jitsi.videobridge.TCP_HARVESTER_SSLTCP";


    /**
     * The default value of the <tt>TCP_HARVESTER_SSLTCP</tt> property.
     */
    private static final boolean TCP_HARVESTER_SSLTCP_DEFAULT = true;

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


            int singlePort = cfg.getInt(SINGLE_PORT_HARVESTER_PORT,
                    SINGLE_PORT_DEFAULT_VALUE);
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

            if (!cfg.getBoolean(DISABLE_TCP_HARVESTER, false))
            {
                int port = cfg.getInt(TCP_HARVESTER_PORT, -1);
                boolean fallback = false;
                boolean ssltcp = cfg.getBoolean(TCP_HARVESTER_SSLTCP,
                        TCP_HARVESTER_SSLTCP_DEFAULT);

                if (port == -1)
                {
                    port = TCP_DEFAULT_PORT;
                    fallback = true;
                }

                try
                {
                    tcpHarvester = new TcpHarvester(port, ssltcp);
                }
                catch (IOException ioe)
                {
                    classLogger.warn(
                            "Failed to initialize TCP harvester on port " + port
                                    + ": " + ioe
                                    + (fallback
                                    ? ". Retrying on port " + TCP_FALLBACK_PORT
                                    : "")
                                    + ".");
                    // If no fallback is allowed, the method will return.
                }
                if (tcpHarvester == null)
                {
                    // If TCP_HARVESTER_PORT specified a port, then fallback was
                    // disabled. However, if the binding on the port (above)
                    // fails, then the method should return.
                    if (!fallback)
                        return;

                    port = TCP_FALLBACK_PORT;
                    try
                    {
                        tcpHarvester
                                = new TcpHarvester(port, ssltcp);
                    }
                    catch (IOException ioe)
                    {
                        classLogger.warn(
                                "Failed to initialize TCP harvester on fallback"
                                        + " port " + port + ": " + ioe);
                        return;
                    }
                }

                if (classLogger.isInfoEnabled())
                {
                    classLogger.info("Initialized TCP harvester on port " + port
                            + ", using SSLTCP:" + ssltcp);
                }

                int mappedPort = cfg.getInt(TCP_HARVESTER_MAPPED_PORT, -1);
                if (mappedPort != -1)
                {
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
