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
import org.jitsi.osgi.ServiceUtils2
import org.jitsi.videobridge.Videobridge
import org.osgi.framework.BundleActivator
import org.osgi.framework.BundleContext

class SignalingApiBundleActivator : BundleActivator {

    override fun start(bundleContext: BundleContext) {
        val videobridge = ServiceUtils2.getService(bundleContext, Videobridge::class.java)
        println("STARTING KTOR")

        // TODO: check if it's enabled, get port, etc.
        embeddedServer(Jetty, port = 9090) { module(videobridge) }.start()
    }

    override fun stop(p0: BundleContext) {
        TODO("Not yet implemented")
    }
}