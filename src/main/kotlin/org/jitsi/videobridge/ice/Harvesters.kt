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

package org.jitsi.videobridge.ice

import org.ice4j.ice.harvest.TcpHarvester
import org.ice4j.ice.harvest.SinglePortUdpHarvester
import org.ice4j.ice.harvest.AbstractUdpListener

import org.jitsi.utils.logging2.LoggerImpl

import java.io.IOException

object Harvesters {
    /**
     * The flag which indicates whether application-wide harvesters, stored
     * in the static fields {@link #tcpHarvester} and
     * {@link #singlePortHarvesters} have been initialized.
     */
    private var staticConfigurationInitialized = false

    /**
     * Global variable do we consider this transport manager as healthy.
     * By default we consider healthy, if we fail to bind to the single port
     * port we consider the bridge as unhealthy.
     */
    private var healthy = true

    @JvmStatic fun isHealthy() = healthy

    /**
     * The {@link Logger} used by the {@link Harvesters} class to
     * print debug information.
     */
    private val classLogger = LoggerImpl(Harvesters::class.simpleName)

    /**
     * The single <tt>TcpHarvester</tt> instance for the
     * application.
     */
    var tcpHarvester: TcpHarvester? = null

    /**
     * The <tt>SinglePortUdpHarvester</tt>s which will be appended to ICE
     * <tt>Agent</tt>s managed by <tt>IceTransport</tt> instances.
     */
    var singlePortHarvesters: List<SinglePortUdpHarvester>? = null

    /**
     * Initializes the static <tt>Harvester</tt> instances used by all
     * <tt>IceTransport</tt> instances, that is
     * {@link #tcpHarvester} and {@link #singlePortHarvesters}.
     */
    @JvmStatic @Synchronized fun initializeStaticConfiguration() {
        if (staticConfigurationInitialized) {
            return
        }
        staticConfigurationInitialized = true

        singlePortHarvesters = SinglePortUdpHarvester.createHarvesters(IceConfig.Config.port())
        if (singlePortHarvesters?.isEmpty() ?: true) {
            singlePortHarvesters = null
            classLogger.info("No single-port harvesters created.")
        }

        healthy = singlePortHarvesters != null

        if (IceConfig.Config.tcpEnabled()) {
            val port = IceConfig.Config.tcpPort()
            try {
                tcpHarvester = TcpHarvester(port, IceConfig.Config.iceSslTcp())
                classLogger.info("Initialized TCP harvester on port " + port + ", ssltcp=" + IceConfig.Config.iceSslTcp())
            } catch (ioe: IOException) {
                classLogger.warn("Failed to initialize TCP harvester on port " + port)
            }

            val mappedPort = IceConfig.Config.tcpMappedPort()
            if (mappedPort != null) {
                tcpHarvester?.addMappedPort(mappedPort)
            }
        }
    }

    /**
     * Stops the static <tt>Harvester</tt> instances used by all
     * <tt>IceTransport</tt> instances, that is
     * {@link #tcpHarvester} and {@link #singlePortHarvesters}.
     */
    @JvmStatic @Synchronized fun closeStaticConfiguration() {
        if (!staticConfigurationInitialized) {
            return
        }
        staticConfigurationInitialized = false

        singlePortHarvesters?.forEach(AbstractUdpListener::close)
        singlePortHarvesters = null

        tcpHarvester?.close()
        tcpHarvester = null

        // Reset the flag to initial state.
        healthy = true
    }
}
