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

package org.jitsi.videobridge.signaling.api

import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import io.ktor.server.jetty.JettyApplicationEngine
import org.jitsi.osgi.ServiceUtils2
import org.jitsi.videobridge.Videobridge
import org.osgi.framework.BundleActivator
import org.osgi.framework.BundleContext
import java.time.Duration
import org.jitsi.videobridge.api.server.module
import org.jitsi.videobridge.api.types.v1.ConferenceManager
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.health.HealthCheckIQ
import org.jivesoftware.smack.packet.IQ

@Suppress("unused") // Used in BundleConfig.java
class SignalingApiBundleActivator : BundleActivator {
    private var server: JettyApplicationEngine? = null

    override fun start(bundleContext: BundleContext) {
        val videobridge = ServiceUtils2.getService(bundleContext, Videobridge::class.java)
        println("STARTING KTOR")

        // TODO: check if it's enabled, get port, etc.
        server = embeddedServer(Jetty, port = 9099) {
            module(object : ConferenceManager {
                override fun handleColibriConferenceIQ(conferenceIQ: ColibriConferenceIQ): IQ {
                    return videobridge.handleColibriConferenceIQ(conferenceIQ)
                }

                override fun handleHealthIq(healthCheckIQ: HealthCheckIQ): IQ {
                    return videobridge.handleHealthCheckIQ(healthCheckIQ)
                }
            })
        }.start()
    }

    override fun stop(p0: BundleContext) {
        server?.stop(Duration.ofSeconds(5).toMillis(), Duration.ofSeconds(5).toMillis())
    }
}
